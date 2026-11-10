package ac.core.persistence;

import ac.core.common.PageQuery;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** SQL access for {@code export_job}. */
@Repository
public class ExportJobRepository {

    private static final String COLUMNS = """
            id, tenant_id, product_code, scope_type, batch_id, row_count, file_name, storage_path, file_size,
            options_json, status, message, created_by, created_at
            """;

    private final JdbcClient jdbc;

    public ExportJobRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(ExportJobRow row) {
        jdbc.sql("""
                        INSERT INTO export_job (id, tenant_id, product_code, scope_type, batch_id, row_count,
                            file_name, storage_path, file_size, options_json, status, message, created_by, created_at)
                        VALUES (:id, :tenantId, :productCode, :scopeType, :batchId, :rowCount, :fileName,
                            :storagePath, :fileSize, :optionsJson, :status, :message, :createdBy, :createdAt)
                        """)
                .param("id", row.id())
                .param("tenantId", row.tenantId())
                .param("productCode", row.productCode())
                .param("scopeType", row.scopeType())
                .param("batchId", row.batchId())
                .param("rowCount", row.rowCount())
                .param("fileName", row.fileName())
                .param("storagePath", row.storagePath())
                .param("fileSize", row.fileSize())
                .param("optionsJson", row.optionsJson())
                .param("status", row.status())
                .param("message", row.message())
                .param("createdBy", row.createdBy())
                .param("createdAt", row.createdAt())
                .update();
    }

    public Optional<ExportJobRow> find(String tenantId, String id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM export_job WHERE tenant_id = :tenantId AND id = :id")
                .param("tenantId", tenantId).param("id", id).query(this::map).optional();
    }

    public List<ExportJobRow> page(String tenantId, PageQuery page) {
        return page(tenantId, new ExportFilter(page));
    }

    /** Filtered history of the export management panel. */
    public List<ExportJobRow> page(String tenantId, ExportFilter filter) {
        Map<String, Object> params = new LinkedHashMap<>();
        String where = whereClause(tenantId, filter, params);
        params.put("limit", filter.page().size());
        params.put("offset", filter.page().offset());
        return jdbc.sql("SELECT " + COLUMNS + " FROM export_job e " + where + orderBy(filter.sort())
                + " LIMIT :limit OFFSET :offset").params(params).query(this::map).list();
    }

    public long count(String tenantId) {
        return count(tenantId, new ExportFilter(PageQuery.of(1, 1)));
    }

    public long count(String tenantId, ExportFilter filter) {
        Map<String, Object> params = new LinkedHashMap<>();
        String where = whereClause(tenantId, filter, params);
        return jdbc.sql("SELECT COUNT(*) FROM export_job e " + where).params(params)
                .query(Long.class).single();
    }

    /** Removes one history entry; the stored file is deleted by the service. */
    public void delete(String tenantId, String id) {
        jdbc.sql("DELETE FROM export_job WHERE tenant_id = :tenantId AND id = :id")
                .param("tenantId", tenantId).param("id", id).update();
    }

    private String whereClause(String tenantId, ExportFilter filter, Map<String, Object> params) {
        StringBuilder sql = new StringBuilder("WHERE e.tenant_id = :tenantId");
        params.put("tenantId", tenantId);
        if (filter.product() != null && !filter.product().isBlank()) {
            sql.append(" AND e.product_code = :product");
            params.put("product", filter.product().trim().toUpperCase(java.util.Locale.ROOT));
        }
        if (filter.batchId() != null && !filter.batchId().isBlank()) {
            sql.append(" AND e.batch_id = :batchId");
            params.put("batchId", filter.batchId().trim());
        }
        if (filter.from() != null) {
            sql.append(" AND e.created_at >= :createdFrom");
            params.put("createdFrom", utcStartOfDay(filter.from()));
        }
        if (filter.to() != null) {
            sql.append(" AND e.created_at < :createdTo");
            params.put("createdTo", utcStartOfDay(filter.to().plusDays(1)));
        }
        if (filter.query() != null && !filter.query().isBlank()) {
            sql.append(" AND (LOWER(e.file_name) LIKE :query OR LOWER(e.product_code) LIKE :query")
                    .append(" OR LOWER(e.batch_id) LIKE :query OR LOWER(e.id) LIKE :query)");
            params.put("query", "%" + filter.query().trim().toLowerCase(java.util.Locale.ROOT) + "%");
        }
        return sql.toString();
    }

    private static java.time.LocalDateTime utcStartOfDay(java.time.LocalDate date) {
        return date.atStartOfDay(ac.core.common.TimeProvider.DISPLAY_ZONE)
                .withZoneSameInstant(java.time.ZoneOffset.UTC).toLocalDateTime();
    }

    private static String orderBy(String sort) {
        String key = sort == null || sort.isBlank() ? "created" : sort.trim().toLowerCase(java.util.Locale.ROOT);
        boolean ascending = key.endsWith("_asc");
        String field = ascending ? key.substring(0, key.length() - 4)
                : key.endsWith("_desc") ? key.substring(0, key.length() - 5) : key;
        String direction = ascending ? " ASC" : " DESC";
        return switch (field) {
            case "rows" -> " ORDER BY e.row_count" + direction + ", e.created_at DESC";
            case "size" -> " ORDER BY e.file_size" + direction + ", e.created_at DESC";
            default -> " ORDER BY e.created_at" + direction + ", e.id";
        };
    }

    private ExportJobRow map(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new ExportJobRow(
                rs.getString("id"),
                rs.getString("tenant_id"),
                rs.getString("product_code"),
                rs.getString("scope_type"),
                rs.getString("batch_id"),
                rs.getInt("row_count"),
                rs.getString("file_name"),
                rs.getString("storage_path"),
                rs.getLong("file_size"),
                rs.getString("options_json"),
                rs.getString("status"),
                rs.getString("message"),
                rs.getString("created_by"),
                rs.getObject("created_at", LocalDateTime.class));
    }
}
