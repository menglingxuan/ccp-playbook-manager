package ac.core.service.task;

import ac.core.common.PageResult;
import ac.core.persistence.BatchFilter;
import ac.core.persistence.ImportBatchRepository;
import ac.core.persistence.ImportBatchRow;
import ac.core.persistence.ImportTaskRepository;
import ac.core.persistence.ImportTaskRow;
import ac.core.persistence.TaskFilter;
import ac.core.common.PageQuery;
import ac.core.common.JsonSupport;
import ac.core.common.TimeProvider;
import ac.core.persistence.TestCaseRepository;
import ac.core.service.audit.AuditService;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Import task queries and deletion. */
@Slf4j
@Service
public class TaskService {

    private final ImportTaskRepository taskRepository;
    private final ImportBatchRepository batchRepository;
    private final TestCaseRepository caseRepository;
    private final AuditService audit;
    private final JsonSupport json;
    private final TimeProvider time;

    public TaskService(ImportTaskRepository taskRepository, ImportBatchRepository batchRepository,
            TestCaseRepository caseRepository, AuditService audit, JsonSupport json, TimeProvider time) {
        this.taskRepository = taskRepository;
        this.batchRepository = batchRepository;
        this.caseRepository = caseRepository;
        this.audit = audit;
        this.json = json;
        this.time = time;
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
        audit.record(tenantId, AuditService.ENTITY_TASK, taskId, "DELETE",
                Map.of("sourceName", String.valueOf(task.sourceName())));
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
