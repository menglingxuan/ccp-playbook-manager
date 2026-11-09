package ac.web.api;

import ac.core.common.PageQuery;
import ac.core.persistence.TaskFilter;
import ac.core.service.task.TaskService;
import ac.web.config.TenantProvider;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Import task endpoints. */
@RestController
@RequestMapping("/api/tasks")
public class TaskController {

    private final TaskService taskService;
    private final TenantProvider tenantProvider;

    public TaskController(TaskService taskService, TenantProvider tenantProvider) {
        this.taskService = taskService;
        this.tenantProvider = tenantProvider;
    }

    @GetMapping
    public Map<String, Object> list(@RequestParam(required = false) String status,
            @RequestParam(required = false) String kind,
            @RequestParam(required = false) String product,
            @RequestParam(required = false, name = "q") String query,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        String tenantId = tenantProvider.tenantId();
        PageQuery pageQuery = PageQuery.of(page, size);
        var result = taskService.search(tenantId, new TaskFilter(kind, status, product, query, sort,
                parseDate(from), parseDate(to), pageQuery));
        Map<String, TaskService.TaskListExtra> extras = taskService.listExtras(tenantId, result.items());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("items", result.items().stream().map(task -> {
            Map<String, Object> view = new LinkedHashMap<>(ac.web.dto.ViewMapper.task(task));
            TaskService.TaskListExtra extra = extras.get(task.id());
            if (extra != null) {
                view.put("baseProducts", extra.baseProducts());
                view.put("sourceExists", extra.sourceExists());
                if (extra.sourcePath() != null) {
                    view.put("sourcePath", extra.sourcePath());
                }
            }
            return view;
        }).toList());
        body.put("page", result.page());
        body.put("size", result.size());
        body.put("total", result.total());
        body.put("totalPages", result.totalPages());
        return body;
    }

    /** Parses an ISO date from the query string; a blank or malformed value means "no bound". */
    private static java.time.LocalDate parseDate(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return java.time.LocalDate.parse(value.trim());
        } catch (java.time.format.DateTimeParseException e) {
            throw ac.core.common.ApiException.invalid("Invalid date '" + value + "', expected yyyy-MM-dd");
        }
    }

    @GetMapping("/summary")
    public Map<String, Integer> summary() {
        return taskService.statusSummary(tenantProvider.tenantId());
    }

    /** Renames a task; duplicate names are rejected with 409 so the list stays identifiable. */
    @PatchMapping("/{id}/name")
    public Map<String, Object> rename(@PathVariable String id, @RequestBody Map<String, String> body) {
        taskService.rename(tenantProvider.tenantId(), id, body.get("name"));
        return Map.of("id", id, "name", body.get("name").trim());
    }

    @GetMapping("/{id}")
    public Map<String, Object> detail(@PathVariable String id) {
        var detail = taskService.detail(tenantProvider.tenantId(), id);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("task", ac.web.dto.ViewMapper.task(detail.task()));
        body.put("batches", detail.batches().stream().map(ac.web.dto.ViewMapper::batch).toList());
        body.put("issues", detail.issues());
        body.put("params", detail.task().paramsJson());
        return body;
    }

    @GetMapping("/{id}/batches")
    public Map<String, Object> batches(@PathVariable String id) {
        return Map.of("items", taskService.batches(tenantProvider.tenantId(), id).stream()
                .map(ac.web.dto.ViewMapper::batch).toList());
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@PathVariable String id) {
        taskService.delete(tenantProvider.tenantId(), id);
        return Map.of("deleted", true);
    }
}
