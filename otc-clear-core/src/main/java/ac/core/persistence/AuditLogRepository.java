package ac.core.persistence;

import ac.core.common.PageQuery;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** SQL access for {@code audit_log}. */
@Repository
public class AuditLogRepository {

    private final JdbcClient jdbc;

    public AuditLogRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(AuditLogRow row) {
        jdbc.sql("""
                        INSERT INTO audit_log (id, tenant_id, entity_type, entity_id, action, detail, actor, created_at)
                        VALUES (:id, :tenantId, :entityType, :entityId, :action, :detail, :actor, :createdAt)
                        """)
                .param("id", row.id())
                .param("tenantId", row.tenantId())
                .param("entityType", row.entityType())
                .param("entityId", row.entityId())
                .param("action", row.action())
                .param("detail", row.detail())
                .param("actor", row.actor())
                .param("createdAt", row.createdAt())
                .update();
    }

    public List<AuditLogRow> page(String tenantId, String entityType, String action, PageQuery page) {
        StringBuilder sql = new StringBuilder("""
                SELECT id, tenant_id, entity_type, entity_id, action, detail, actor, created_at
                FROM audit_log WHERE tenant_id = :tenantId
                """);
        if (entityType != null && !entityType.isBlank()) {
            sql.append(" AND entity_type = :entityType");
        }
        if (action != null && !action.isBlank()) {
            sql.append(" AND action = :action");
        }
        sql.append(" ORDER BY created_at DESC LIMIT :limit OFFSET :offset");
        var spec = jdbc.sql(sql.toString()).param("tenantId", tenantId)
                .param("limit", page.size()).param("offset", page.offset());
        if (entityType != null && !entityType.isBlank()) {
            spec = spec.param("entityType", entityType);
        }
        if (action != null && !action.isBlank()) {
            spec = spec.param("action", action);
        }
        return spec.query(this::map).list();
    }

    public long count(String tenantId, String entityType, String action) {
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM audit_log WHERE tenant_id = :tenantId");
        if (entityType != null && !entityType.isBlank()) {
            sql.append(" AND entity_type = :entityType");
        }
        if (action != null && !action.isBlank()) {
            sql.append(" AND action = :action");
        }
        var spec = jdbc.sql(sql.toString()).param("tenantId", tenantId);
        if (entityType != null && !entityType.isBlank()) {
            spec = spec.param("entityType", entityType);
        }
        if (action != null && !action.isBlank()) {
            spec = spec.param("action", action);
        }
        return spec.query(Long.class).single();
    }

    private AuditLogRow map(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new AuditLogRow(
                rs.getString("id"),
                rs.getString("tenant_id"),
                rs.getString("entity_type"),
                rs.getString("entity_id"),
                rs.getString("action"),
                rs.getString("detail"),
                rs.getString("actor"),
                rs.getObject("created_at", LocalDateTime.class));
    }
}
