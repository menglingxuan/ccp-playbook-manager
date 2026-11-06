package ac.core.service.audit;

import ac.core.common.Ids;
import ac.core.common.JsonSupport;
import ac.core.common.PageQuery;
import ac.core.common.PageResult;
import ac.core.common.TimeProvider;
import ac.core.persistence.AuditLogRepository;
import ac.core.persistence.AuditLogRow;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/** Writes the audit trail and exposes it for the audit page. */
@Slf4j
@Service
public class AuditService {

    public static final String ENTITY_TASK = "TASK";
    public static final String ENTITY_BATCH = "BATCH";
    public static final String ENTITY_CASE = "CASE";
    public static final String ENTITY_EXPORT = "EXPORT";
    public static final String ENTITY_EXTERNAL_JOB = "EXTERNAL_JOB";
    public static final String ENTITY_OUTPUT = "OUTPUT";

    private final AuditLogRepository repository;
    private final JsonSupport json;
    private final TimeProvider time;

    public AuditService(AuditLogRepository repository, JsonSupport json, TimeProvider time) {
        this.repository = repository;
        this.json = json;
        this.time = time;
    }

    public void record(String tenantId, String entityType, String entityId, String action,
            Map<String, Object> detail) {
        AuditLogRow row = new AuditLogRow(Ids.newId(), tenantId, entityType, entityId, action,
                detail == null || detail.isEmpty() ? null : json.toJson(detail), "system", time.nowUtc());
        try {
            repository.insert(row);
        } catch (RuntimeException e) {
            log.warn("Cannot write audit entry {} {} {}: {}", entityType, entityId, action, e.getMessage());
        }
    }

    public void record(String tenantId, String entityType, String entityId, String action, String message) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("message", message);
        record(tenantId, entityType, entityId, action, detail);
    }

    public PageResult<AuditLogRow> search(String tenantId, String entityType, String action,
            PageQuery page) {
        var items = repository.page(tenantId, entityType, action, page);
        long total = repository.count(tenantId, entityType, action);
        return new PageResult<>(items, page.page(), page.size(), total);
    }
}
