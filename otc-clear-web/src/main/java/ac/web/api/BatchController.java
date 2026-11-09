package ac.web.api;

import ac.core.common.PageQuery;
import ac.core.persistence.BatchFilter;
import ac.core.persistence.CaseFilter;
import ac.core.service.batch.BatchService;
import ac.web.config.TenantProvider;
import ac.web.dto.ViewMapper;
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

/** Batch endpoints. */
@RestController
@RequestMapping("/api/batches")
public class BatchController {

    private final BatchService batchService;
    private final TenantProvider tenantProvider;

    public BatchController(BatchService batchService, TenantProvider tenantProvider) {
        this.batchService = batchService;
        this.tenantProvider = tenantProvider;
    }

    @GetMapping
    public Map<String, Object> list(@RequestParam(required = false) String product,
            @RequestParam(required = false, name = "q") String query,
            @RequestParam(required = false) String kind,
            @RequestParam(required = false, defaultValue = "false") boolean includeExternal,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        PageQuery pageQuery = PageQuery.of(page, size);
        var result = batchService.search(tenantProvider.tenantId(),
                new BatchFilter(product, query, kind, includeExternal, pageQuery));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("items", result.items().stream().map(ViewMapper::batch).toList());
        body.put("page", result.page());
        body.put("size", result.size());
        body.put("total", result.total());
        body.put("totalPages", result.totalPages());
        return body;
    }

    @GetMapping("/{id}")
    public Map<String, Object> detail(@PathVariable String id) {
        var batch = batchService.require(tenantProvider.tenantId(), id);
        Map<String, Object> body = new LinkedHashMap<>(ViewMapper.batch(batch));
        body.put("activeCases", batchService.activeCaseCount(tenantProvider.tenantId(), id));
        return body;
    }

    @PatchMapping("/{id}")
    public Map<String, Object> updateNote(@PathVariable String id, @RequestBody Map<String, String> body) {
        batchService.updateNote(tenantProvider.tenantId(), id, body.get("note"));
        return Map.of("updated", true);
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@PathVariable String id) {
        batchService.delete(tenantProvider.tenantId(), id);
        return Map.of("deleted", true);
    }

    @GetMapping("/{id}/cases")
    public Map<String, Object> cases(@PathVariable String id,
            @RequestParam(required = false, name = "q") String query,
            @RequestParam(required = false) Boolean hasFormula,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        PageQuery pageQuery = PageQuery.of(page, size);
        var result = batchService.cases(tenantProvider.tenantId(), id,
                new CaseFilter(id, query, hasFormula, pageQuery));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("items", result.items());
        body.put("page", result.page());
        body.put("size", result.size());
        body.put("total", result.total());
        body.put("totalPages", result.totalPages());
        return body;
    }
}
