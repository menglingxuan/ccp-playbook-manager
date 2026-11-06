package ac.web.api;

import ac.core.common.PageQuery;
import ac.core.persistence.TaskFilter;
import ac.core.service.task.TaskService;
import ac.web.config.TenantProvider;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
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
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        PageQuery pageQuery = PageQuery.of(page, size);
        var result = taskService.search(tenantProvider.tenantId(),
                new TaskFilter(kind, status, product, query, sort, pageQuery));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("items", result.items().stream().map(ac.web.dto.ViewMapper::task).toList());
        body.put("page", result.page());
        body.put("size", result.size());
        body.put("total", result.total());
        body.put("totalPages", result.totalPages());
        return body;
    }

    @GetMapping("/summary")
    public Map<String, Integer> summary() {
        return taskService.statusSummary(tenantProvider.tenantId());
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
