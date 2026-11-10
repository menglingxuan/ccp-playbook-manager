package ac.web.page;

import ac.core.config.AppProperties;
import ac.core.service.task.TaskService;
import ac.web.config.TenantProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.server.ResponseStatusException;

/** Server rendered page shells; the data is loaded by the page scripts through the REST API. */
@Controller
public class PageController {

    private final AppProperties properties;
    private final TenantProvider tenantProvider;
    private final TaskService taskService;

    public PageController(AppProperties properties, TenantProvider tenantProvider, TaskService taskService) {
        this.properties = properties;
        this.tenantProvider = tenantProvider;
        this.taskService = taskService;
    }

    @GetMapping("/")
    public String index(Model model) {
        model.addAttribute("page", "tasks");
        model.addAttribute("title", "导入任务");
        model.addAttribute("tenantId", tenantProvider.tenantId());
        model.addAttribute("mode", properties.getMode().name());
        model.addAttribute("summary", taskService.statusSummary(tenantProvider.tenantId()));
        return "index";
    }

    @GetMapping("/import")
    public String importWizard(Model model) {
        model.addAttribute("page", "import");
        model.addAttribute("title", "导入向导");
        return "import";
    }

    @GetMapping("/tasks/{id}")
    public String taskDetail(@PathVariable String id, Model model) {
        model.addAttribute("page", "tasks");
        model.addAttribute("title", "任务详情");
        model.addAttribute("taskId", id);
        return "task-detail";
    }

    @GetMapping("/batches")
    public String batches(Model model) {
        model.addAttribute("page", "batches");
        model.addAttribute("title", "批次浏览");
        return "batches";
    }

    @GetMapping("/batches/{id}")
    public String batchDetail(@PathVariable String id, Model model) {
        model.addAttribute("page", "batches");
        model.addAttribute("title", "批次详情");
        model.addAttribute("batchId", id);
        return "batch-detail";
    }

    @GetMapping("/exports")
    public String exports(Model model) {
        model.addAttribute("page", "exports");
        model.addAttribute("title", "导出管理");
        return "exports";
    }

    @GetMapping("/outputs")
    public String outputs(Model model) {
        model.addAttribute("page", "outputs");
        model.addAttribute("title", "外部任务");
        return "outputs";
    }

    @GetMapping("/outputs/{id}")
    public String outputDetail(@PathVariable String id, Model model) {
        model.addAttribute("page", "outputs");
        model.addAttribute("title", "外部任务详情");
        model.addAttribute("outputId", id);
        return "output-detail";
    }

    /** Audit panel; hidden entirely when {@code app.audit.enabled} is false. */
    @GetMapping("/audit")
    public String audit(Model model) {
        if (!properties.getAudit().isEnabled()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        model.addAttribute("page", "audit");
        model.addAttribute("title", "审计日志");
        return "audit";
    }
}
