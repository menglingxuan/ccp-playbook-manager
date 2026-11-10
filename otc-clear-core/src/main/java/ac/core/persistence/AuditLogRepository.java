package ac.core.persistence;

import ac.core.common.PageQuery;
import ac.core.common.TimeProvider;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** SQL access for {@code audit_log}. */
@Repository
public class AuditLogRepository {

    private static final String COLUMNS =
            "id, tenant_id, module, entity_type, entity_id, action, detail, actor, created_at";

    private final JdbcClient jdbc;

    public AuditLogRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(AuditLogRow row) {
        jdbc.sql("""
                        INSERT INTO audit_log (id, tenant_id, module, entity_type, entity_id, action, detail,
                                               actor, created_at)
                        VALUES (:id, :tenantId, :module, :entityType, :entityId, :action, :detail, :actor, :createdAt)
                        """)
                .param("id", row.id())
                .param("tenantId", row.tenantId())
                .param("module", row.module())
                .param("entityType", row.entityType())
                .param("entityId", row.entityId())
                .param("action", row.action())
                .param("detail", row.detail())
                .param("actor", row.actor())
                .param("createdAt", row.createdAt())
                .update();
    }

    public List<AuditLogRow> page(String tenantId, AuditLogFilter filter) {
        Map<String, Object> params = new LinkedHashMap<>();
        String where = whereClause(tenantId, filter, params);
        params.put("limit", filter.page().size());
        params.put("offset", filter.page().offset());
        return jdbc.sql("SELECT " + COLUMNS + " FROM audit_log " + where + " ORDER BY created_at DESC, id"
                + " LIMIT :limit OFFSET :offset").params(params).query(this::map).list();
    }

    /** Convenience overload for callers that only need the first page of everything. */
    public List<AuditLogRow> page(String tenantId, PageQuery page) {
        return page(tenantId, new AuditLogFilter(null, null, null, null, null, null, page));
    }

    public long count(String tenantId, AuditLogFilter filter) {
        Map<String, Object> params = new LinkedHashMap<>();
        String where = whereClause(tenantId, filter, params);
        return jdbc.sql("SELECT COUNT(*) FROM audit_log " + where).params(params)
                .query(Long.class).single();
    }

    /**
     * Counts the entries matching the filter, grouped by one column.
     *
     * @param column {@code module}, {@code entity_type} or {@code action}
     * @return counts keyed by the column value, never {@code null}
     */
    public Map<String, Long> countBy(String tenantId, AuditLogFilter filter, String column) {
        String safeColumn = switch (column) {
            case "module" -> "module";
            case "entity_type" -> "entity_type";
            case "action" -> "action";
            default -> throw new IllegalArgumentException("Unsupported audit facet: " + column);
        };
        Map<String, Object> params = new LinkedHashMap<>();
        String where = whereClause(tenantId, filter, params);
        Map<String, Long> counts = new LinkedHashMap<>();
        jdbc.sql("SELECT " + safeColumn + " AS facet, COUNT(*) AS total FROM audit_log " + where
                + " GROUP BY " + safeColumn + " ORDER BY total DESC, facet")
                .params(params)
                .query(rs -> {
                    counts.put(rs.getString("facet"), rs.getLong("total"));
                });
        return counts;
    }

    private String whereClause(String tenantId, AuditLogFilter filter, Map<String, Object> params) {
        StringBuilder sql = new StringBuilder("WHERE tenant_id = :tenantId");
        params.put("tenantId", tenantId);
        if (notBlank(filter.module())) {
            sql.append(" AND module = :module");
            params.put("module", filter.module().trim().toUpperCase(Locale.ROOT));
        }
        if (notBlank(filter.entityType())) {
            sql.append(" AND entity_type = :entityType");
            params.put("entityType", filter.entityType().trim().toUpperCase(Locale.ROOT));
        }
        if (notBlank(filter.action())) {
            sql.append(" AND action = :action");
            params.put("action", filter.action().trim().toUpperCase(Locale.ROOT));
        }
        if (filter.from() != null) {
            sql.append(" AND created_at >= :createdFrom");
            params.put("createdFrom", utcStartOfDay(filter.from()));
        }
        if (filter.to() != null) {
            sql.append(" AND created_at < :createdTo");
            params.put("createdTo", utcStartOfDay(filter.to().plusDays(1)));
        }
        if (notBlank(filter.query())) {
            sql.append(" AND (LOWER(entity_id) LIKE :query OR LOWER(detail) LIKE :query")
                    .append(" OR LOWER(action) LIKE :query OR LOWER(actor) LIKE :query)");
            params.put("query", "%" + filter.query().trim().toLowerCase(Locale.ROOT) + "%");
        }
        return sql.toString();
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    private static LocalDateTime utcStartOfDay(LocalDate date) {
        return date.atStartOfDay(TimeProvider.DISPLAY_ZONE).withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime();
    }

    private AuditLogRow map(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new AuditLogRow(
                rs.getString("id"),
                rs.getString("tenant_id"),
                rs.getString("module"),
                rs.getString("entity_type"),
                rs.getString("entity_id"),
                rs.getString("action"),
                rs.getString("detail"),
                rs.getString("actor"),
                rs.getObject("created_at", LocalDateTime.class));
    }
}
