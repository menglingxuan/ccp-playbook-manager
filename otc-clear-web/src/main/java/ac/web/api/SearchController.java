package ac.web.api;

import ac.core.service.search.GlobalSearchService;
import ac.web.config.TenantProvider;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Global search endpoint used by the batch console. */
@RestController
@RequestMapping("/api/search")
public class SearchController {

    private final GlobalSearchService searchService;
    private final TenantProvider tenantProvider;

    public SearchController(GlobalSearchService searchService, TenantProvider tenantProvider) {
        this.searchService = searchService;
        this.tenantProvider = tenantProvider;
    }

    /**
     * Searches batches and cases.
     *
     * @param q     expression, qualifiers {@code tag: product: batch: case: value: note: file: scope:}
     * @param limit maximum hits per group (1-50, default 20)
     */
    @GetMapping
    public Map<String, Object> search(@RequestParam("q") String query,
            @RequestParam(required = false) Integer limit) {
        return GlobalSearchService.toView(
                searchService.search(tenantProvider.tenantId(), query, limit));
    }
}
