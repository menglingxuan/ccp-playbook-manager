package ac.web.api;

import ac.core.service.clone.ClonePreview;
import ac.core.service.clone.CloneRequest;
import ac.core.service.clone.CloneResult;
import ac.core.service.clone.CloneService;
import ac.web.config.TenantProvider;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Clone endpoints. */
@RestController
@RequestMapping("/api/clones")
public class CloneController {

    private final CloneService cloneService;
    private final TenantProvider tenantProvider;

    public CloneController(CloneService cloneService, TenantProvider tenantProvider) {
        this.cloneService = cloneService;
        this.tenantProvider = tenantProvider;
    }

    @PostMapping("/preview")
    public ClonePreview preview(@RequestBody CloneRequest request) {
        return cloneService.preview(tenantProvider.tenantId(), request);
    }

    @PostMapping
    public CloneResult execute(@RequestBody CloneRequest request) {
        return cloneService.execute(tenantProvider.tenantId(), request);
    }
}
