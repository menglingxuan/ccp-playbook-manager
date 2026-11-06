package ac.core.persistence;

import ac.core.common.PageQuery;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** SQL access for {@code import_task}. */
@Repository
public class ImportTaskRepository {

    private static final String COLUMNS = """
            id, tenant_id, kind, status, source_name, source_sha256, sheet_count, batch_count,
            total_rows, invalid_rows, params_json, issues_json, message, created_by, created_at, started_at, finished_at
            """;

    private final JdbcClient jdbc;

    public ImportTaskRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(ImportTaskRow row) {
        jdbc.sql("""
                        INSERT INTO import_task (id, tenant_id, kind, status, source_name, source_sha256,
                            sheet_count, batch_count, total_rows, invalid_rows, params_json, issues_json, message,
                            created_by, created_at, started_at, finished_at)
                        VALUES (:id, :tenantId, :kind, :status, :sourceName, :sourceSha256,
                            :sheetCount, :batchCount, :totalRows, :invalidRows, :paramsJson, :issuesJson, :message,
                            :createdBy, :createdAt, :startedAt, :finishedAt)
                        """)
                .param("id", row.id())
                .param("tenantId", row.tenantId())
                .param("kind", row.kind())
                .param("status", row.status())
                .param("sourceName", row.sourceName())
                .param("sourceSha256", row.sourceSha256())
                .param("sheetCount", row.sheetCount())
                .param("batchCount", row.batchCount())
                .param("totalRows", row.totalRows())
                .param("invalidRows", row.invalidRows())
                .param("paramsJson", row.paramsJson())
                .param("issuesJson", row.issuesJson())
                .param("message", row.message())
                .param("createdBy", row.createdBy())
                .param("createdAt", row.createdAt())
                .param("startedAt", row.startedAt())
                .param("finishedAt", row.finishedAt())
                .update();
    }

    public void markRunning(String tenantId, String id, LocalDateTime startedAt) {
        jdbc.sql("UPDATE import_task SET status = 'RUNNING', started_at = :startedAt WHERE tenant_id = :tenantId AND id = :id")
                .param("tenantId", tenantId).param("id", id).param("startedAt", startedAt).update();
    }

    public void finish(String tenantId, String id, String status, int sheetCount, int batchCount,
            int totalRows, int invalidRows, String issuesJson, String message, LocalDateTime finishedAt) {
        jdbc.sql("""
                        UPDATE import_task SET status = :status, sheet_count = :sheetCount,
                            batch_count = :batchCount, total_rows = :totalRows, invalid_rows = :invalidRows,
                            issues_json = :issuesJson, message = :message, finished_at = :finishedAt
                        WHERE tenant_id = :tenantId AND id = :id
                        """)
                .param("tenantId", tenantId).param("id", id).param("status", status)
                .param("sheetCount", sheetCount).param("batchCount", batchCount)
                .param("totalRows", totalRows).param("invalidRows", invalidRows)
                .param("issuesJson", issuesJson)
                .param("message", message).param("finishedAt", finishedAt)
                .update();
    }

    public void softDelete(String tenantId, String id) {
        jdbc.sql("UPDATE import_task SET status = 'DELETED' WHERE tenant_id = :tenantId AND id = :id")
                .param("tenantId", tenantId).param("id", id).update();
    }

    public Optional<ImportTaskRow> find(String tenantId, String id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM import_task WHERE tenant_id = :tenantId AND id = :id")
                .param("tenantId", tenantId).param("id", id)
                .query(this::map).optional();
    }

    public List<ImportTaskRow> search(String tenantId, TaskFilter filter) {
        StringBuilder sql = new StringBuilder("SELECT " + COLUMNS + " FROM import_task t WHERE t.tenant_id = :tenantId");
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("tenantId", tenantId);
        if (filter.kind() != null && !filter.kind().isBlank()) {
            sql.append(" AND t.kind = :kind");
            params.put("kind", filter.kind());
        }
        if (filter.status() != null && !filter.status().isBlank()) {
            sql.append(" AND t.status = :status");
            params.put("status", filter.status());
        } else {
            sql.append(" AND t.status <> 'DELETED'");
        }
        if (filter.product() != null && !filter.product().isBlank()) {
            sql.append(" AND EXISTS (SELECT 1 FROM import_batch b WHERE b.task_id = t.id AND b.product_code = :product)");
            params.put("product", filter.product().toUpperCase(java.util.Locale.ROOT));
        }
        if (filter.query() != null && !filter.query().isBlank()) {
            sql.append(" AND (LOWER(t.source_name) LIKE :query OR LOWER(t.id) LIKE :query")
                    .append(" OR EXISTS (SELECT 1 FROM import_batch b WHERE b.task_id = t.id AND LOWER(b.product_code) LIKE :query))");
            params.put("query", "%" + filter.query().trim().toLowerCase(java.util.Locale.ROOT) + "%");
        }
        sql.append(orderBy(filter.sort()));
        sql.append(" LIMIT :limit OFFSET :offset");
        params.put("limit", filter.page().size());
        params.put("offset", filter.page().offset());
        return jdbc.sql(sql.toString()).params(params).query(this::map).list();
    }

    public long count(String tenantId, TaskFilter filter) {
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM import_task t WHERE t.tenant_id = :tenantId");
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("tenantId", tenantId);
        if (filter.kind() != null && !filter.kind().isBlank()) {
            sql.append(" AND t.kind = :kind");
            params.put("kind", filter.kind());
        }
        if (filter.status() != null && !filter.status().isBlank()) {
            sql.append(" AND t.status = :status");
            params.put("status", filter.status());
        } else {
            sql.append(" AND t.status <> 'DELETED'");
        }
        if (filter.product() != null && !filter.product().isBlank()) {
            sql.append(" AND EXISTS (SELECT 1 FROM import_batch b WHERE b.task_id = t.id AND b.product_code = :product)");
            params.put("product", filter.product().toUpperCase(java.util.Locale.ROOT));
        }
        if (filter.query() != null && !filter.query().isBlank()) {
            sql.append(" AND (LOWER(t.source_name) LIKE :query OR LOWER(t.id) LIKE :query")
                    .append(" OR EXISTS (SELECT 1 FROM import_batch b WHERE b.task_id = t.id AND LOWER(b.product_code) LIKE :query))");
            params.put("query", "%" + filter.query().trim().toLowerCase(java.util.Locale.ROOT) + "%");
        }
        return jdbc.sql(sql.toString()).params(params).query(Long.class).single();
    }

    public List<Map<String, Object>> statusCounts(String tenantId) {
        List<Map<String, Object>> rows = new ArrayList<>();
        jdbc.sql("""
                        SELECT status, COUNT(*) AS total FROM import_task
                        WHERE tenant_id = :tenantId AND status <> 'DELETED' GROUP BY status
                        """)
                .param("tenantId", tenantId)
                .query((rs, rowNum) -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("status", rs.getString("status"));
                    item.put("total", rs.getInt("total"));
                    rows.add(item);
                    return item;
                }).list();
        return rows;
    }

    private String orderBy(String sort) {
        return switch (sort == null ? "created" : sort) {
            case "rows" -> " ORDER BY t.total_rows DESC, t.created_at DESC";
            case "invalid" -> " ORDER BY t.invalid_rows DESC, t.created_at DESC";
            case "duration" -> " ORDER BY COALESCE(DATEDIFF('SECOND', t.started_at, t.finished_at), 0) DESC";
            default -> " ORDER BY t.created_at DESC";
        };
    }

    private ImportTaskRow map(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new ImportTaskRow(
                rs.getString("id"),
                rs.getString("tenant_id"),
                rs.getString("kind"),
                rs.getString("status"),
                rs.getString("source_name"),
                rs.getString("source_sha256"),
                rs.getInt("sheet_count"),
                rs.getInt("batch_count"),
                rs.getInt("total_rows"),
                rs.getInt("invalid_rows"),
                rs.getString("params_json"),
                rs.getString("issues_json"),
                rs.getString("message"),
                rs.getString("created_by"),
                rs.getObject("created_at", LocalDateTime.class),
                rs.getObject("started_at", LocalDateTime.class),
                rs.getObject("finished_at", LocalDateTime.class));
    }
}
