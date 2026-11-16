package ac.core.service.task;

import ac.core.common.ApiException;
import ac.core.common.Notes;
import ac.core.common.ErrorCode;
import ac.core.common.PageQuery;
import ac.core.common.PageResult;
import ac.core.persistence.BatchFilter;
import ac.core.persistence.ExportJobRepository;
import ac.core.persistence.ExportJobRow;
import ac.core.persistence.ExternalOutputRepository;
import ac.core.persistence.ImportBatchRepository;
import ac.core.persistence.ImportBatchRow;
import ac.core.persistence.ImportTaskRepository;
import ac.core.persistence.ImportTaskRow;
import ac.core.persistence.TaskFilter;
import ac.core.common.JsonSupport;
import ac.core.common.TimeProvider;
import ac.core.persistence.TestCaseRepository;
import ac.core.service.DataLayout;
import ac.core.service.importflow.SheetCache;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Import task queries and deletion. */
@Slf4j
@Service
public class TaskService {

    private static final DateTimeFormatter UPLOAD_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");

    /** Upper bound of a task name, matching the column width. */
    public static final int MAX_NAME_LENGTH = 256;

    private final ImportTaskRepository taskRepository;
    private final ImportBatchRepository batchRepository;
    private final TestCaseRepository caseRepository;
    private final ExportJobRepository exportRepository;
    private final ExternalOutputRepository externalOutputs;
    private final SheetCache sheetCache;
    private final JsonSupport json;
    private final TimeProvider time;
    private final DataLayout dataLayout;
    private final ac.core.service.baseline.BaselineStore baselines;

    public TaskService(ImportTaskRepository taskRepository, ImportBatchRepository batchRepository,
            TestCaseRepository caseRepository, ExportJobRepository exportRepository,
            ExternalOutputRepository externalOutputs, SheetCache sheetCache, JsonSupport json,
            TimeProvider time, DataLayout dataLayout, ac.core.service.baseline.BaselineStore baselines) {
        this.taskRepository = taskRepository;
        this.batchRepository = batchRepository;
        this.caseRepository = caseRepository;
        this.exportRepository = exportRepository;
        this.externalOutputs = externalOutputs;
        this.sheetCache = sheetCache;
        this.json = json;
        this.time = time;
        this.dataLayout = dataLayout;
        this.baselines = baselines;
    }

    /**
     * Task detail payload.
     *
     * @param task    the task row
     * @param batches batches created by the task
     * @param issues  issues recorded during import
     */
    public record TaskDetail(ImportTaskRow task, List<ImportBatchRow> batches,
            List<Map<String, Object>> issues) {
    }

    public PageResult<ImportTaskRow> search(String tenantId, TaskFilter filter) {
        var items = taskRepository.search(tenantId, filter);
        long total = taskRepository.count(tenantId, filter);
        return new PageResult<>(items, filter.page().page(), filter.page().size(), total);
    }

    /**
     * Extra list data of the tasks: server side source location, whether that file still exists and
     * the base products behind their batches.
     *
     * @param baseProducts distinct base product codes of the task batches
     * @param sourcePath   stored location of the imported file, {@code null} when unknown
     * @param sourceExists whether the stored file is still on disk
     */
    public record TaskListExtra(List<String> baseProducts, String sourcePath, boolean sourceExists) {

        public TaskListExtra {
            baseProducts = baseProducts == null ? List.of() : List.copyOf(baseProducts);
        }
    }

    /**
     * Resolves the extra list data of one page of tasks.
     *
     * <p>Office workbooks keep an immutable baseline under {@code batches/{batchId}/source.*}; CSV
     * uploads only have the upload copy, which is purged when its TTL expires.
     */
    public Map<String, TaskListExtra> listExtras(String tenantId, List<ImportTaskRow> tasks) {
        if (tasks.isEmpty()) {
            return Map.of();
        }
        List<String> taskIds = tasks.stream().map(ImportTaskRow::id).toList();
        Map<String, ImportBatchRow> firstBatch = new LinkedHashMap<>();
        for (ImportBatchRow batch : batchRepository.listByTaskIds(tenantId, taskIds)) {
            firstBatch.putIfAbsent(batch.taskId(), batch);
        }
        Map<String, List<String>> baseProducts = taskRepository.baseProductsByTask(tenantId, taskIds);
        Map<String, TaskListExtra> extras = new LinkedHashMap<>();
        for (ImportTaskRow task : tasks) {
            String path = sourcePath(tenantId, firstBatch.get(task.id()));
            extras.put(task.id(), new TaskListExtra(baseProducts.get(task.id()),
                    path, path != null && Files.isRegularFile(Path.of(path))));
        }
        return extras;
    }

    /** Replaces the remark of one task; an empty text clears it. */
    @Transactional
    public String updateNote(String tenantId, String taskId, String rawNote) {
        taskRepository.find(tenantId, taskId)
                .filter(row -> !"DELETED".equals(row.status()))
                .orElseThrow(() -> ApiException.notFound("Task " + taskId + " does not exist"));
        String note = Notes.normalize(rawNote);
        taskRepository.updateNote(tenantId, taskId, note);
        return note;
    }

    /** Renames a task; the name stays unique inside the tenant and is capped at 256 characters. */
    @Transactional
    public void rename(String tenantId, String taskId, String rawName) {
        ImportTaskRow task = taskRepository.find(tenantId, taskId)
                .filter(row -> !"DELETED".equals(row.status()))
                .orElseThrow(() -> ApiException.notFound("Task " + taskId + " does not exist"));
        String name = rawName == null ? "" : rawName.trim();
        if (name.isEmpty()) {
            throw ApiException.invalid("Task name must not be empty");
        }
        if (name.length() > MAX_NAME_LENGTH) {
            throw ApiException.invalid("Task name must be at most " + MAX_NAME_LENGTH + " characters");
        }
        if (name.equals(task.name())) {
            return;
        }
        if (taskRepository.nameExists(tenantId, name)) {
            throw new ApiException(ErrorCode.CONFLICT, "Task name '" + name + "' already exists",
                    Map.of("name", name));
        }
        taskRepository.rename(tenantId, taskId, name);
    }

    private String sourcePath(String tenantId, ImportBatchRow batch) {
        if (batch == null) {
            return null;
        }
        String root = dataLayout.root().resolve("tenants").resolve(tenantId).toString().replace('\\', '/');
        String type = batch.sourceType() == null ? "" : batch.sourceType().toLowerCase(Locale.ROOT);
        boolean workbook = "xlsx".equals(type) || "xls".equals(type);
        if (workbook) {
            // the baseline survives the upload TTL and is what export recalculation reads
            return root + "/batches/" + batch.id() + "/source." + type;
        }
        if (batch.uploadId() == null) {
            // clone batches have no source file of their own
            return null;
        }
        LocalDate uploadDate = batch.createdAt().atZone(ZoneOffset.UTC)
                .withZoneSameInstant(TimeProvider.DISPLAY_ZONE).toLocalDate();
        return root + "/uploads/" + uploadDate.format(UPLOAD_DATE) + "/" + batch.uploadId() + "."
                + (type.isEmpty() ? "csv" : type);
    }

    public TaskDetail detail(String tenantId, String taskId) {
        ImportTaskRow task = taskRepository.find(tenantId, taskId)
                .filter(row -> !"DELETED".equals(row.status()))
                .orElseThrow(() -> ac.core.common.ApiException.notFound("Task " + taskId + " does not exist"));
        List<ImportBatchRow> batches = batchRepository.listByTask(tenantId, taskId);
        return new TaskDetail(task, batches, parseIssues(task.issuesJson()));
    }

    public List<ImportBatchRow> batches(String tenantId, String taskId) {
        return batchRepository.listByTask(tenantId, taskId);
    }

    public PageResult<ImportBatchRow> batchesPage(String tenantId, BatchFilter filter) {
        var items = batchRepository.search(tenantId, filter);
        long total = batchRepository.count(tenantId, filter);
        return new PageResult<>(items, filter.page().page(), filter.page().size(), total);
    }

    /**
     * Task counts per status for the tasks that match the given filter.
     *
     * <p>The status of the filter itself is ignored, and the remaining predicates (kind, product,
     * search text, date range) are the ones the list uses, so every card reports exactly the number of
     * rows a click on it shows. Only statuses that an import can actually end up in are reported: a
     * commit is one transaction, so a queued or running task is never visible from outside it.
     *
     * @param tenantId tenant scope
     * @param filter   filter whose status clause is ignored
     * @return status to count, in a stable display order
     */
    public Map<String, Integer> statusSummary(String tenantId, TaskFilter filter) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String status : List.of("RUNNING", "SUCCEEDED", "PARTIAL", "FAILED")) {
            counts.put(status, 0);
        }
        taskRepository.statusCounts(tenantId, filter).forEach(item ->
                counts.put(String.valueOf(item.get("status")), ((Number) item.get("total")).intValue()));
        return counts;
    }

    /**
     * Purges a task: its batches, the cases inside them, the export history of those batches and every
     * file only this task referred to.
     *
     * <p>A task delete is not a reversible soft delete, so the rows are physically removed instead of
     * being marked {@code DELETED}: nothing in the database or on disk keeps pointing at the removed
     * data. Files that another batch still uses (an upload imported twice) are kept.
     *
     * @param tenantId tenant scope
     * @param taskId   task to remove
     */
    @Transactional
    public void delete(String tenantId, String taskId) {
        taskRepository.find(tenantId, taskId)
                .orElseThrow(() -> ApiException.notFound("Task " + taskId + " does not exist"));
        // a batch that was already deleted on its own is part of the task and goes with it
        List<ImportBatchRow> batches = batchRepository.listByTaskForPurge(tenantId, taskId);
        List<String> batchIds = batches.stream().map(ImportBatchRow::id).toList();

        // the export history of these batches belongs to the task: records and archived CSV go as well
        for (ExportJobRow job : exportRepository.listByBatchIds(tenantId, batchIds)) {
            deleteExportFile(tenantId, job);
            exportRepository.delete(tenantId, job.id());
        }
        // an external output keeps its record, only the pointer at the removed batch is cleared
        externalOutputs.detachBatches(tenantId, batchIds);
        caseRepository.purgeByBatches(tenantId, batchIds);
        batchRepository.purgeByTask(tenantId, taskId);
        taskRepository.purge(tenantId, taskId);

        // baselines are only reachable through their batch; uploads may be shared with another task
        batches.forEach(batch -> baselines.delete(tenantId, batch.id()));
        purgeUploads(tenantId, batches);
    }

    /** Removes the archived file of one export record, if it is still there. */
    private void deleteExportFile(String tenantId, ExportJobRow job) {
        try {
            Files.deleteIfExists(dataLayout.resolveInside(dataLayout.tenantRoot(tenantId), job.storagePath()));
        } catch (IOException | RuntimeException e) {
            log.warn("Cannot delete the export file of {}: {}", job.id(), e.toString());
        }
    }

    /**
     * Deletes the upload files that no remaining batch refers to and drops them from the preview cache.
     *
     * @param tenantId tenant scope
     * @param batches  batches that were just removed
     */
    private void purgeUploads(String tenantId, List<ImportBatchRow> batches) {
        Set<String> handled = new HashSet<>();
        for (ImportBatchRow batch : batches) {
            String uploadId = batch.uploadId();
            if (uploadId == null || uploadId.isBlank() || !handled.add(uploadId)) {
                continue;
            }
            // the parsed sheets of that upload must not outlive it in memory
            sheetCache.evict(uploadId);
            if (batchRepository.countByUploadId(tenantId, uploadId) > 0) {
                // another task imported from the same upload, so its source file has to stay
                continue;
            }
            LocalDate uploadDate = batch.createdAt().atZone(ZoneOffset.UTC)
                    .withZoneSameInstant(TimeProvider.DISPLAY_ZONE).toLocalDate();
            Path directory = dataLayout.uploadsDir(tenantId, uploadDate);
            String prefix = uploadId + ".";
            try (Stream<Path> files = Files.list(directory)) {
                files.filter(path -> path.getFileName().toString().startsWith(prefix)).forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (IOException e) {
                        log.warn("Cannot delete the upload file {}: {}", path, e.toString());
                    }
                });
            } catch (IOException e) {
                // the day folder may already be gone; nothing to clean then
                log.debug("No upload files to clean for {}: {}", uploadId, e.toString());
            }
        }
    }

    public PageResult<ImportTaskRow> recent(String tenantId, int limit) {
        TaskFilter filter = new TaskFilter(null, null, null, null, "created", PageQuery.of(1, limit));
        return search(tenantId, filter);
    }

    private List<Map<String, Object>> parseIssues(String issuesJson) {
        List<Map<String, Object>> issues = new ArrayList<>();
        if (issuesJson == null || issuesJson.isBlank()) {
            return issues;
        }
        for (Object item : json.toList(issuesJson)) {
            if (item instanceof Map<?, ?> map) {
                Map<String, Object> entry = new LinkedHashMap<>();
                map.forEach((key, value) -> entry.put(String.valueOf(key), value));
                issues.add(entry);
            }
        }
        return issues;
    }
}
