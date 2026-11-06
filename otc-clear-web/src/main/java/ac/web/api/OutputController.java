package ac.web.api;

import ac.core.common.PageQuery;
import ac.core.persistence.CaseFilter;
import ac.core.service.external.ExternalOutputService;
import ac.web.config.TenantProvider;
import ac.web.dto.ViewMapper;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** External output browsing endpoints, kept separate from the case list. */
@RestController
@RequestMapping("/api/outputs")
public class OutputController {

    private final ExternalOutputService outputService;
    private final TenantProvider tenantProvider;

    public OutputController(ExternalOutputService outputService, TenantProvider tenantProvider) {
        this.outputService = outputService;
        this.tenantProvider = tenantProvider;
    }

    @GetMapping
    public Map<String, Object> list(@RequestParam(required = false) String jobId,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        PageQuery pageQuery = PageQuery.of(page, size);
        var result = outputService.page(tenantProvider.tenantId(), jobId, pageQuery);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("items", result.items().stream().map(output -> ViewMapper.output(output,
                outputService.caseCount(tenantProvider.tenantId(), output.importedBatchId()))).toList());
        body.put("page", result.page());
        body.put("size", result.size());
        body.put("total", result.total());
        body.put("totalPages", result.totalPages());
        return body;
    }

    @GetMapping("/{id}")
    public Object detail(@PathVariable String id) {
        var output = outputService.require(tenantProvider.tenantId(), id);
        return ViewMapper.output(output,
                outputService.caseCount(tenantProvider.tenantId(), output.importedBatchId()));
    }

    @GetMapping("/{id}/cases")
    public Map<String, Object> cases(@PathVariable String id,
            @RequestParam(required = false, name = "q") String query,
            @RequestParam(required = false) Boolean hasFormula,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        PageQuery pageQuery = PageQuery.of(page, size);
        var result = outputService.cases(tenantProvider.tenantId(), id,
                new CaseFilter(null, query, hasFormula, pageQuery));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("items", result.items());
        body.put("page", result.page());
        body.put("size", result.size());
        body.put("total", result.total());
        body.put("totalPages", result.totalPages());
        return body;
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@PathVariable String id) {
        outputService.delete(tenantProvider.tenantId(), id);
        return Map.of("deleted", true);
    }
}
