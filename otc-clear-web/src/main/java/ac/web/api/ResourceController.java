package ac.web.api;

import ac.core.service.stats.ResourceStats;
import ac.core.service.stats.ResourceStatsService;
import ac.web.config.TenantProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Resource usage of the bound tenant: file areas, database rows and JVM state. */
@RestController
@RequestMapping("/api/resources")
public class ResourceController {

    private final ResourceStatsService resourceStatsService;
    private final TenantProvider tenantProvider;

    public ResourceController(ResourceStatsService resourceStatsService, TenantProvider tenantProvider) {
        this.resourceStatsService = resourceStatsService;
        this.tenantProvider = tenantProvider;
    }

    @GetMapping("/stats")
    public ResourceStats stats() {
        return resourceStatsService.stats(tenantProvider.tenantId());
    }
}
