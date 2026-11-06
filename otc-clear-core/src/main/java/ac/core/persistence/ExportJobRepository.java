package ac.core.persistence;

import ac.core.common.PageQuery;
import java.time.LocalDateTime;
import java.util.List;
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
        return jdbc.sql("SELECT " + COLUMNS + """
                         FROM export_job WHERE tenant_id = :tenantId
                         ORDER BY created_at DESC LIMIT :limit OFFSET :offset
                        """)
                .param("tenantId", tenantId).param("limit", page.size()).param("offset", page.offset())
                .query(this::map).list();
    }

    public long count(String tenantId) {
        return jdbc.sql("SELECT COUNT(*) FROM export_job WHERE tenant_id = :tenantId")
                .param("tenantId", tenantId).query(Long.class).single();
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
