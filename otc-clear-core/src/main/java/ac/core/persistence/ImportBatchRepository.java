package ac.core.persistence;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** SQL access for {@code import_batch}. */
@Repository
public class ImportBatchRepository {

    private static final String COLUMNS = """
            id, tenant_id, task_id, upload_id, kind, parent_batch_id, product_code, source_filename,
            source_sha256, source_type, sheet_name, header_row_index, trade_start_col, trade_end_col,
            layout_json, column_mapping, schema_hash, status, total_rows, invalid_rows, note, created_by,
            created_at, updated_at, deleted_at
            """;

    private final JdbcClient jdbc;

    public ImportBatchRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(ImportBatchRow row) {
        jdbc.sql("""
                        INSERT INTO import_batch (id, tenant_id, task_id, upload_id, kind, parent_batch_id,
                            product_code, source_filename, source_sha256, source_type, sheet_name,
                            header_row_index, trade_start_col, trade_end_col, layout_json, column_mapping,
                            schema_hash, status, total_rows, invalid_rows, note, created_by, created_at,
                            updated_at, deleted_at)
                        VALUES (:id, :tenantId, :taskId, :uploadId, :kind, :parentBatchId,
                            :productCode, :sourceFilename, :sourceSha256, :sourceType, :sheetName,
                            :headerRowIndex, :tradeStartCol, :tradeEndCol, :layoutJson, :columnMapping,
                            :schemaHash, :status, :totalRows, :invalidRows, :note, :createdBy, :createdAt,
                            :updatedAt, :deletedAt)
                        """)
                .param("id", row.id())
                .param("tenantId", row.tenantId())
                .param("taskId", row.taskId())
                .param("uploadId", row.uploadId())
                .param("kind", row.kind())
                .param("parentBatchId", row.parentBatchId())
                .param("productCode", row.productCode())
                .param("sourceFilename", row.sourceFilename())
                .param("sourceSha256", row.sourceSha256())
                .param("sourceType", row.sourceType())
                .param("sheetName", row.sheetName())
                .param("headerRowIndex", row.headerRowIndex())
                .param("tradeStartCol", row.tradeStartCol())
                .param("tradeEndCol", row.tradeEndCol())
                .param("layoutJson", row.layoutJson())
                .param("columnMapping", row.columnMappingJson())
                .param("schemaHash", row.schemaHash())
                .param("status", row.status())
                .param("totalRows", row.totalRows())
                .param("invalidRows", row.invalidRows())
                .param("note", row.note())
                .param("createdBy", row.createdBy())
                .param("createdAt", row.createdAt())
                .param("updatedAt", row.updatedAt())
                .param("deletedAt", row.deletedAt())
                .update();
    }

    public Optional<ImportBatchRow> find(String tenantId, String id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM import_batch WHERE tenant_id = :tenantId AND id = :id")
                .param("tenantId", tenantId).param("id", id)
                .query(this::map).optional();
    }

    public List<ImportBatchRow> listByTask(String tenantId, String taskId) {
        return jdbc.sql("SELECT " + COLUMNS + """
                         FROM import_batch WHERE tenant_id = :tenantId AND task_id = :taskId
                           AND status <> 'DELETED' ORDER BY created_at, sheet_name
                        """)
                .param("tenantId", tenantId).param("taskId", taskId)
                .query(this::map).list();
    }

    public List<ImportBatchRow> search(String tenantId, BatchFilter filter) {
        StringBuilder sql = new StringBuilder("SELECT " + COLUMNS + " FROM import_batch b WHERE b.tenant_id = :tenantId");
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("tenantId", tenantId);
        sql.append(" AND b.status <> 'DELETED'");
        if (!filter.includeExternal()) {
            sql.append(" AND b.kind <> 'EXTERNAL_OUTPUT'");
        }
        if (filter.product() != null && !filter.product().isBlank()) {
            sql.append(" AND b.product_code = :product");
            params.put("product", filter.product().toUpperCase(java.util.Locale.ROOT));
        }
        if (filter.query() != null && !filter.query().isBlank()) {
            sql.append(" AND (LOWER(b.source_filename) LIKE :query OR LOWER(b.sheet_name) LIKE :query)");
            params.put("query", "%" + filter.query().trim().toLowerCase(java.util.Locale.ROOT) + "%");
        }
        sql.append(" ORDER BY b.created_at DESC LIMIT :limit OFFSET :offset");
        params.put("limit", filter.page().size());
        params.put("offset", filter.page().offset());
        return jdbc.sql(sql.toString()).params(params).query(this::map).list();
    }

    public long count(String tenantId, BatchFilter filter) {
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM import_batch b WHERE b.tenant_id = :tenantId");
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("tenantId", tenantId);
        sql.append(" AND b.status <> 'DELETED'");
        if (!filter.includeExternal()) {
            sql.append(" AND b.kind <> 'EXTERNAL_OUTPUT'");
        }
        if (filter.product() != null && !filter.product().isBlank()) {
            sql.append(" AND b.product_code = :product");
            params.put("product", filter.product().toUpperCase(java.util.Locale.ROOT));
        }
        if (filter.query() != null && !filter.query().isBlank()) {
            sql.append(" AND (LOWER(b.source_filename) LIKE :query OR LOWER(b.sheet_name) LIKE :query)");
            params.put("query", "%" + filter.query().trim().toLowerCase(java.util.Locale.ROOT) + "%");
        }
        return jdbc.sql(sql.toString()).params(params).query(Long.class).single();
    }

    /**
     * True when the given upload already produced an active import batch for that sheet, which means
     * the very same submission was committed twice. Importing an identical file again creates a new
     * upload and is therefore not a duplicate.
     */
    public boolean existsActiveImportForUpload(String tenantId, String uploadId, String sheetName) {
        if (uploadId == null || uploadId.isBlank()) {
            return false;
        }
        return jdbc.sql("""
                        SELECT COUNT(*) FROM import_batch
                        WHERE tenant_id = :tenantId AND upload_id = :uploadId AND sheet_name = :sheetName
                          AND kind = 'IMPORT' AND status = 'ACTIVE'
                        """)
                .param("tenantId", tenantId).param("uploadId", uploadId).param("sheetName", sheetName)
                .query(Long.class).single() > 0;
    }

    public void updateNote(String tenantId, String id, String note, LocalDateTime updatedAt) {
        jdbc.sql("UPDATE import_batch SET note = :note, updated_at = :updatedAt WHERE tenant_id = :tenantId AND id = :id")
                .param("note", note).param("updatedAt", updatedAt).param("tenantId", tenantId).param("id", id)
                .update();
    }

    public void softDelete(String tenantId, String id, LocalDateTime deletedAt) {
        jdbc.sql("""
                        UPDATE import_batch SET status = 'DELETED', deleted_at = :deletedAt, updated_at = :deletedAt
                        WHERE tenant_id = :tenantId AND id = :id
                        """)
                .param("deletedAt", deletedAt).param("tenantId", tenantId).param("id", id).update();
    }

    public void softDeleteByTask(String tenantId, String taskId, LocalDateTime deletedAt) {
        jdbc.sql("""
                        UPDATE import_batch SET status = 'DELETED', deleted_at = :deletedAt, updated_at = :deletedAt
                        WHERE tenant_id = :tenantId AND task_id = :taskId
                        """)
                .param("deletedAt", deletedAt).param("tenantId", tenantId).param("taskId", taskId).update();
    }

    public long countRecent(String tenantId, LocalDateTime since) {
        return jdbc.sql("""
                        SELECT COUNT(*) FROM import_batch
                        WHERE tenant_id = :tenantId AND status = 'ACTIVE' AND created_at >= :since
                        """)
                .param("tenantId", tenantId).param("since", since)
                .query(Long.class).single();
    }

    public long countByProduct(String tenantId, String productCode) {
        return jdbc.sql("SELECT COUNT(*) FROM import_batch WHERE tenant_id = :tenantId AND product_code = :productCode")
                .param("tenantId", tenantId).param("productCode", productCode).query(Long.class).single();
    }


    private ImportBatchRow map(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new ImportBatchRow(
                rs.getString("id"),
                rs.getString("tenant_id"),
                rs.getString("task_id"),
                rs.getString("upload_id"),
                rs.getString("kind"),
                rs.getString("parent_batch_id"),
                rs.getString("product_code"),
                rs.getString("source_filename"),
                rs.getString("source_sha256"),
                rs.getString("source_type"),
                rs.getString("sheet_name"),
                rs.getInt("header_row_index"),
                rs.getInt("trade_start_col"),
                rs.getInt("trade_end_col"),
                rs.getString("layout_json"),
                rs.getString("column_mapping"),
                rs.getString("schema_hash"),
                rs.getString("status"),
                rs.getInt("total_rows"),
                rs.getInt("invalid_rows"),
                rs.getString("note"),
                rs.getString("created_by"),
                rs.getObject("created_at", LocalDateTime.class),
                rs.getObject("updated_at", LocalDateTime.class),
                rs.getObject("deleted_at", LocalDateTime.class));
    }
}
