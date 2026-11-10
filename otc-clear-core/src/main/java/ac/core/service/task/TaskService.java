package ac.core.service.task;

import ac.core.common.ApiException;
import ac.core.common.ErrorCode;
import ac.core.common.PageQuery;
import ac.core.common.PageResult;
import ac.core.persistence.BatchFilter;
import ac.core.persistence.ImportBatchRepository;
import ac.core.persistence.ImportBatchRow;
import ac.core.persistence.ImportTaskRepository;
import ac.core.persistence.ImportTaskRow;
import ac.core.persistence.TaskFilter;
import ac.core.common.JsonSupport;
import ac.core.common.TimeProvider;
import ac.core.persistence.TestCaseRepository;
import ac.core.service.DataLayout;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
    private final JsonSupport json;
    private final TimeProvider time;
    private final DataLayout dataLayout;

    public TaskService(ImportTaskRepository taskRepository, ImportBatchRepository batchRepository,
            TestCaseRepository caseRepository, JsonSupport json, TimeProvider time,
            DataLayout dataLayout) {
        this.taskRepository = taskRepository;
        this.batchRepository = batchRepository;
        this.caseRepository = caseRepository;
        this.json = json;
        this.time = time;
        this.dataLayout = dataLayout;
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

    public Map<String, Integer> statusSummary(String tenantId) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        counts.put("PENDING", 0);
        counts.put("RUNNING", 0);
        counts.put("SUCCEEDED", 0);
        counts.put("PARTIAL", 0);
        counts.put("FAILED", 0);
        taskRepository.statusCounts(tenantId).forEach(item ->
                counts.put(String.valueOf(item.get("status")), ((Number) item.get("total")).intValue()));
        return counts;
    }

    @Transactional
    public void delete(String tenantId, String taskId) {
        ImportTaskRow task = taskRepository.find(tenantId, taskId)
                .orElseThrow(() -> ac.core.common.ApiException.notFound("Task " + taskId + " does not exist"));
        LocalDateTime now = time.nowUtc();
        caseRepository.softDeleteByTask(tenantId, taskId, now);
        batchRepository.softDeleteByTask(tenantId, taskId, now);
        taskRepository.softDelete(tenantId, taskId);
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
