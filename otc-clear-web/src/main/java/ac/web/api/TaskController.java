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

    /**
     * Task counts per status of the tasks matching the current list filters.
     *
     * <p>The response ignores {@code status} on purpose: the cards show how many tasks each status has
     * under the active search, product and date filters, and clicking a card applies exactly that
     * filter.
     *
     * @param kind    task kind, {@code null} for all
     * @param product base product code, {@code null} for all
     * @param query   free text search, {@code null} for none
     * @param from    earliest creation date, {@code null} for no lower bound
     * @param to      latest creation date, {@code null} for no upper bound
     * @return status to count
     */
    @GetMapping("/summary")
    public Map<String, Integer> summary(@RequestParam(required = false) String kind,
            @RequestParam(required = false) String product,
            @RequestParam(required = false, name = "q") String query,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to) {
        TaskFilter filter = new TaskFilter(kind, null, product, query, null, parseDate(from),
                parseDate(to), PageQuery.of(1, 1));
        return taskService.statusSummary(tenantProvider.tenantId(), filter);
    }

    /** Renames a task; duplicate names are rejected with 409 so the list stays identifiable. */
    @PatchMapping("/{id}/name")
    public Map<String, Object> rename(@PathVariable String id, @RequestBody Map<String, String> body) {
        taskService.rename(tenantProvider.tenantId(), id, body.get("name"));
        return Map.of("id", id, "name", body.get("name").trim());
    }

    /** Sets or clears the free text remark of a task. */
    @PatchMapping("/{id}/note")
    public Map<String, Object> note(@PathVariable String id, @RequestBody Map<String, String> body) {
        String note = taskService.updateNote(tenantProvider.tenantId(), id, body.get("note"));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", id);
        result.put("note", note);
        return result;
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
