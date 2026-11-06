package ac.web.page;

import ac.core.config.AppProperties;
import ac.core.service.task.TaskService;
import ac.web.config.TenantProvider;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

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
        model.addAttribute("externalEnabled", properties.getExternal().isEnabled());
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

    @GetMapping("/batches/{id}")
    public String batchDetail(@PathVariable String id, Model model) {
        model.addAttribute("page", "batches");
        model.addAttribute("title", "批次详情");
        model.addAttribute("batchId", id);
        return "batch-detail";
    }

    @GetMapping("/outputs")
    public String outputs(Model model) {
        model.addAttribute("page", "outputs");
        model.addAttribute("title", "产出浏览");
        return "outputs";
    }

    @GetMapping("/outputs/{id}")
    public String outputDetail(@PathVariable String id, Model model) {
        model.addAttribute("page", "outputs");
        model.addAttribute("title", "产出详情");
        model.addAttribute("outputId", id);
        return "output-detail";
    }

    @GetMapping("/external-jobs")
    public String externalJobs(Model model) {
        model.addAttribute("page", "external");
        model.addAttribute("title", "外部任务");
        model.addAttribute("externalEnabled", properties.getExternal().isEnabled());
        return "external-jobs";
    }

    @GetMapping("/external-jobs/{id}")
    public String externalJobDetail(@PathVariable String id, Model model) {
        model.addAttribute("page", "external");
        model.addAttribute("title", "外部任务详情");
        model.addAttribute("jobId", id);
        model.addAttribute("externalEnabled", properties.getExternal().isEnabled());
        return "external-job-detail";
    }
}
