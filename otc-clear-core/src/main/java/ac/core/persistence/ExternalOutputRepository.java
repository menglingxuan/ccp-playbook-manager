package ac.core.persistence;

import ac.core.common.PageQuery;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** SQL access for {@code external_output}. */
@Repository
public class ExternalOutputRepository {

    private static final String COLUMNS = """
            id, tenant_id, job_id, file_name, relative_path, size_bytes, sha256, imported_batch_id, status,
            message, discovered_at, imported_at
            """;

    private final JdbcClient jdbc;

    public ExternalOutputRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(ExternalOutputRow row) {
        jdbc.sql("""
                        INSERT INTO external_output (id, tenant_id, job_id, file_name, relative_path, size_bytes,
                            sha256, imported_batch_id, status, message, discovered_at, imported_at)
                        VALUES (:id, :tenantId, :jobId, :fileName, :relativePath, :sizeBytes, :sha256,
                            :importedBatchId, :status, :message, :discoveredAt, :importedAt)
                        """)
                .param("id", row.id())
                .param("tenantId", row.tenantId())
                .param("jobId", row.jobId())
                .param("fileName", row.fileName())
                .param("relativePath", row.relativePath())
                .param("sizeBytes", row.sizeBytes())
                .param("sha256", row.sha256())
                .param("importedBatchId", row.importedBatchId())
                .param("status", row.status())
                .param("message", row.message())
                .param("discoveredAt", row.discoveredAt())
                .param("importedAt", row.importedAt())
                .update();
    }

    public void updateStatus(String tenantId, String id, String status, String message,
            String importedBatchId, LocalDateTime importedAt) {
        jdbc.sql("""
                        UPDATE external_output SET status = :status, message = :message,
                            imported_batch_id = :importedBatchId, imported_at = :importedAt
                        WHERE tenant_id = :tenantId AND id = :id
                        """)
                .param("status", status).param("message", message).param("importedBatchId", importedBatchId)
                .param("importedAt", importedAt).param("tenantId", tenantId).param("id", id).update();
    }

    public Optional<ExternalOutputRow> find(String tenantId, String id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM external_output WHERE tenant_id = :tenantId AND id = :id")
                .param("tenantId", tenantId).param("id", id).query(this::map).optional();
    }

    public Optional<ExternalOutputRow> findBySha(String tenantId, String sha256) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM external_output WHERE tenant_id = :tenantId AND sha256 = :sha")
                .param("tenantId", tenantId).param("sha", sha256).query(this::map).optional();
    }

    public List<ExternalOutputRow> listByJob(String tenantId, String jobId) {
        return jdbc.sql("SELECT " + COLUMNS + """
                         FROM external_output WHERE tenant_id = :tenantId AND job_id = :jobId
                         ORDER BY relative_path
                        """)
                .param("tenantId", tenantId).param("jobId", jobId).query(this::map).list();
    }

    public List<ExternalOutputRow> page(String tenantId, String jobId, PageQuery page) {
        StringBuilder sql = new StringBuilder("SELECT " + COLUMNS + " FROM external_output WHERE tenant_id = :tenantId");
        if (jobId != null && !jobId.isBlank()) {
            sql.append(" AND job_id = :jobId");
        }
        sql.append(" ORDER BY discovered_at DESC LIMIT :limit OFFSET :offset");
        var spec = jdbc.sql(sql.toString()).param("tenantId", tenantId)
                .param("limit", page.size()).param("offset", page.offset());
        if (jobId != null && !jobId.isBlank()) {
            spec = spec.param("jobId", jobId);
        }
        return spec.query(this::map).list();
    }

    public long count(String tenantId, String jobId) {
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM external_output WHERE tenant_id = :tenantId");
        if (jobId != null && !jobId.isBlank()) {
            sql.append(" AND job_id = :jobId");
        }
        var spec = jdbc.sql(sql.toString()).param("tenantId", tenantId);
        if (jobId != null && !jobId.isBlank()) {
            spec = spec.param("jobId", jobId);
        }
        return spec.query(Long.class).single();
    }

    public void deleteByJob(String tenantId, String jobId) {
        jdbc.sql("DELETE FROM external_output WHERE tenant_id = :tenantId AND job_id = :jobId")
                .param("tenantId", tenantId).param("jobId", jobId).update();
    }

    public void delete(String tenantId, String id) {
        jdbc.sql("DELETE FROM external_output WHERE tenant_id = :tenantId AND id = :id")
                .param("tenantId", tenantId).param("id", id).update();
    }

    private ExternalOutputRow map(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new ExternalOutputRow(
                rs.getString("id"),
                rs.getString("tenant_id"),
                rs.getString("job_id"),
                rs.getString("file_name"),
                rs.getString("relative_path"),
                rs.getLong("size_bytes"),
                rs.getString("sha256"),
                rs.getString("imported_batch_id"),
                rs.getString("status"),
                rs.getString("message"),
                rs.getObject("discovered_at", LocalDateTime.class),
                rs.getObject("imported_at", LocalDateTime.class));
    }
}
