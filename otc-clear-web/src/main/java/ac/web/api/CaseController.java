package ac.web.api;

import ac.core.common.PageQuery;
import ac.core.service.cases.CaseCompareService;
import ac.core.service.cases.CaseSearchService;
import ac.core.service.cases.CaseService;
import ac.core.service.cases.UpdateCaseRequest;
import ac.core.service.validation.ValidationService;
import ac.domain.validation.ValidationOptions;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Case endpoints: detail, vertical view, edit, delete, search and comparison. */
@RestController
@RequestMapping("/api/cases")
public class CaseController {

    private final CaseService caseService;
    private final CaseCompareService caseCompareService;
    private final CaseSearchService caseSearchService;
    private final ValidationService validationService;
    private final TenantProvider tenantProvider;

    public CaseController(CaseService caseService, CaseCompareService caseCompareService,
            CaseSearchService caseSearchService, ValidationService validationService,
            TenantProvider tenantProvider) {
        this.caseService = caseService;
        this.caseCompareService = caseCompareService;
        this.caseSearchService = caseSearchService;
        this.validationService = validationService;
        this.tenantProvider = tenantProvider;
    }

    @GetMapping("/{id}")
    public Object detail(@PathVariable String id) {
        return caseService.detail(tenantProvider.tenantId(), id);
    }

    /**
     * Global case search of the tenant.
     *
     * <p>{@code q} is free text (test case id, trade id, remark, any stored value) or a raw id: a
     * case id resolves to that case, a batch id lists its cases, a task id lists the cases of every
     * batch of that task. Each hit carries its batch and task labels.
     */
    @GetMapping("/search")
    public CaseSearchService.Page search(@RequestParam(required = false, name = "q") String query,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return caseSearchService.search(tenantProvider.tenantId(), query, PageQuery.of(page, size));
    }

    @GetMapping("/{id}/vertical")
    public Object vertical(@PathVariable String id) {
        return caseService.vertical(tenantProvider.tenantId(), id);
    }

    @PatchMapping("/{id}")
    public Object update(@PathVariable String id, @RequestBody UpdateCaseRequest request) {
        return caseService.update(tenantProvider.tenantId(), id, request);
    }

    /**
     * Compares this case with another one of the tenant; the result is returned only and never stored.
     *
     * @param body {@code rightCaseId} of the case to compare against
     */
    @PostMapping("/{id}/compare")
    public CaseCompareService.Result compare(@PathVariable String id,
            @RequestBody CaseCompareService.Request body) {
        return caseCompareService.compare(tenantProvider.tenantId(), id, body);
    }

    /**
     * Validates one case against the field rules of its product; nothing is stored.
     *
     * <p>The result lists the failing fields and the fields that carry no rule at all.
     *
     * @param ignoreCase match values case-insensitively, default {@code false}
     * @param skipBlank  leave blank values unchecked, default {@code true}
     */
    @GetMapping("/{id}/validation")
    public ValidationService.Result validation(@PathVariable String id,
            @RequestParam(required = false, defaultValue = "false") boolean ignoreCase,
            @RequestParam(required = false, defaultValue = "true") boolean skipBlank) {
        return validationService.validateCase(tenantProvider.tenantId(), id,
                new ValidationOptions(ignoreCase, skipBlank));
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
