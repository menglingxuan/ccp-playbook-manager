package ac.web.api;

import ac.core.common.PageQuery;
import ac.core.common.TimeProvider;
import ac.core.service.audit.AuditService;
import ac.web.config.TenantProvider;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Audit trail endpoint. */
@RestController
@RequestMapping("/api/audit")
public class AuditController {

    private final AuditService auditService;
    private final TenantProvider tenantProvider;

    public AuditController(AuditService auditService, TenantProvider tenantProvider) {
        this.auditService = auditService;
        this.tenantProvider = tenantProvider;
    }

    @GetMapping
    public Map<String, Object> list(@RequestParam(required = false) String entityType,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        PageQuery pageQuery = PageQuery.of(page, size);
        var result = auditService.search(tenantProvider.tenantId(), entityType, action, pageQuery);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("items", result.items().stream().map(row -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", row.id());
            item.put("entityType", row.entityType());
            item.put("entityId", row.entityId());
            item.put("action", row.action());
            item.put("detail", row.detail());
            item.put("actor", row.actor());
            item.put("createdAt", TimeProvider.display(row.createdAt()));
            return item;
        }).toList());
        body.put("page", result.page());
        body.put("size", result.size());
        body.put("total", result.total());
        body.put("totalPages", result.totalPages());
        return body;
    }
}
