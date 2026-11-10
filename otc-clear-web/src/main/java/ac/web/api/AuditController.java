package ac.web.api;

import ac.core.common.PageQuery;
import ac.core.common.TimeProvider;
import ac.core.persistence.AuditLogFilter;
import ac.core.service.audit.AuditService;
import ac.web.config.TenantProvider;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Audit trail endpoints; both answer 404 when {@code app.audit.enabled} is false. */
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
    public Map<String, Object> list(@RequestParam(required = false) String module,
            @RequestParam(required = false) String entityType,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        requireEnabled();
        AuditLogFilter filter = filter(module, entityType, action, q, from, to, PageQuery.of(page, size));
        var result = auditService.search(tenantProvider.tenantId(), filter);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("items", result.items().stream().map(row -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", row.id());
            item.put("module", row.module());
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

    /** Facet counts for the filter dropdowns (each dimension ignores its own filter). */
    @GetMapping("/facets")
    public Map<String, Object> facets(@RequestParam(required = false) String module,
            @RequestParam(required = false) String entityType,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        requireEnabled();
        AuditLogFilter filter = filter(module, entityType, action, q, from, to, PageQuery.of(1, 1));
        Map<String, Object> body = new LinkedHashMap<>(auditService.facets(tenantProvider.tenantId(), filter));
        body.put("total", auditService.search(tenantProvider.tenantId(), filter).total());
        return body;
    }

    private void requireEnabled() {
        if (!auditService.enabled()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
    }

    private static AuditLogFilter filter(String module, String entityType, String action, String q,
            LocalDate from, LocalDate to, PageQuery page) {
        return new AuditLogFilter(blankToNull(module), blankToNull(entityType), blankToNull(action),
                blankToNull(q), from, to, page);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
