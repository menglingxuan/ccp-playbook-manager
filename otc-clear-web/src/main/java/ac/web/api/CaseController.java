package ac.web.api;

import ac.core.service.cases.CaseService;
import ac.core.service.cases.UpdateCaseRequest;
import ac.web.config.TenantProvider;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Case endpoints: detail, vertical view, edit and delete. */
@RestController
@RequestMapping("/api/cases")
public class CaseController {

    private final CaseService caseService;
    private final TenantProvider tenantProvider;

    public CaseController(CaseService caseService, TenantProvider tenantProvider) {
        this.caseService = caseService;
        this.tenantProvider = tenantProvider;
    }

    @GetMapping("/{id}")
    public Object detail(@PathVariable String id) {
        return caseService.detail(tenantProvider.tenantId(), id);
    }

    @GetMapping("/{id}/vertical")
    public Object vertical(@PathVariable String id) {
        return caseService.vertical(tenantProvider.tenantId(), id);
    }

    @PatchMapping("/{id}")
    public Object update(@PathVariable String id, @RequestBody UpdateCaseRequest request) {
        return caseService.update(tenantProvider.tenantId(), id, request);
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@PathVariable String id) {
        caseService.delete(tenantProvider.tenantId(), id);
        return Map.of("deleted", true);
    }

    @PostMapping("/bulk-delete")
    public Map<String, Object> bulkDelete(@RequestBody Map<String, List<String>> body) {
        int deleted = caseService.bulkDelete(tenantProvider.tenantId(),
                body.getOrDefault("ids", List.of()));
        return Map.of("deleted", deleted);
    }
}
