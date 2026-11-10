package ac.core.service.audit;

import ac.core.common.Ids;
import ac.core.common.JsonSupport;
import ac.core.common.PageResult;
import ac.core.common.TimeProvider;
import ac.core.config.AppProperties;
import ac.core.persistence.AuditLogFilter;
import ac.core.persistence.AuditLogRepository;
import ac.core.persistence.AuditLogRow;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Writes the audit trail and exposes it for the audit panel.
 *
 * <p>Only the product definition and the field meta dictionary are audited; the whole trail can be
 * switched off with {@code app.audit.enabled} (the prod profile does that by default).
 */
@Slf4j
@Service
public class AuditService {

    public static final String ENTITY_BASE_PRODUCT = "BASE_PRODUCT";
    public static final String ENTITY_BASE_FIELD = "BASE_FIELD";
    public static final String ENTITY_PRODUCT = "PRODUCT";
    public static final String ENTITY_PRODUCT_FIELD = "PRODUCT_FIELD";
    public static final String ENTITY_META_SET = "META_SET";
    public static final String ENTITY_META_ITEM = "META_ITEM";

    private final AuditLogRepository repository;
    private final JsonSupport json;
    private final TimeProvider time;
    private final boolean enabled;

    public AuditService(AuditLogRepository repository, JsonSupport json, TimeProvider time,
            AppProperties properties) {
        this.repository = repository;
        this.json = json;
        this.time = time;
        this.enabled = properties.getAudit().isEnabled();
    }

    /** Whether the audit entry and the trail are enabled at all. */
    public boolean enabled() {
        return enabled;
    }

    public void record(String tenantId, AuditModule module, String entityType, String entityId, String action,
            Map<String, Object> detail) {
        if (!enabled) {
            return;
        }
        AuditLogRow row = new AuditLogRow(Ids.newId(), tenantId, module.name(), entityType, entityId, action,
                detail == null || detail.isEmpty() ? null : json.toJson(detail), "system", time.nowUtc());
        try {
            repository.insert(row);
        } catch (RuntimeException e) {
            log.warn("Cannot write audit entry {} {} {}: {}", entityType, entityId, action, e.getMessage());
        }
    }

    public void record(String tenantId, AuditModule module, String entityType, String entityId, String action,
            String message) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("message", message);
        record(tenantId, module, entityType, entityId, action, detail);
    }

    public PageResult<AuditLogRow> search(String tenantId, AuditLogFilter filter) {
        return new PageResult<>(repository.page(tenantId, filter), filter.page().page(),
                filter.page().size(), repository.count(tenantId, filter));
    }

    /**
     * Facet counts for the filter dropdowns.
     *
     * <p>Every dimension is computed with the other dimensions applied, so the numbers next to an
     * option match what selecting it would return.
     *
     * @return maps of module / entity type / action codes to their counts
     */
    public Map<String, Map<String, Long>> facets(String tenantId, AuditLogFilter filter) {
        Map<String, Map<String, Long>> facets = new LinkedHashMap<>();
        facets.put("modules", repository.countBy(tenantId, without(filter, "module"), "module"));
        facets.put("entities", repository.countBy(tenantId, without(filter, "entity_type"), "entity_type"));
        facets.put("actions", repository.countBy(tenantId, without(filter, "action"), "action"));
        return facets;
    }

    private static AuditLogFilter without(AuditLogFilter filter, String dimension) {
        return new AuditLogFilter(
                "module".equals(dimension) ? null : filter.module(),
                "entity_type".equals(dimension) ? null : filter.entityType(),
                "action".equals(dimension) ? null : filter.action(),
                filter.query(), filter.from(), filter.to(), filter.page());
    }
}
