package ac.core.persistence;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** SQL access for {@code import_batch}. */
@Repository
public class ImportBatchRepository {

    /** Default name of a batch until the user renames it. */
    public static final String DEFAULT_NAME = "Untitled";

    private static final String COLUMNS = """
            id, tenant_id, task_id, name, upload_id, kind, parent_batch_id, product_code, source_filename,
            source_sha256, source_type, sheet_name, header_row_index, trade_start_col, trade_end_col,
            layout_json, column_mapping, schema_hash, status, total_rows, invalid_rows, note, favorite,
            pinned, tags, created_by, created_at, updated_at, deleted_at
            """;

    private final JdbcClient jdbc;

    public ImportBatchRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(ImportBatchRow row) {
        jdbc.sql("""
                        INSERT INTO import_batch (id, tenant_id, task_id, name, upload_id, kind, parent_batch_id,
                            product_code, source_filename, source_sha256, source_type, sheet_name,
                            header_row_index, trade_start_col, trade_end_col, layout_json, column_mapping,
                            schema_hash, status, total_rows, invalid_rows, note, favorite, pinned, tags,
                            created_by, created_at, updated_at, deleted_at)
                        VALUES (:id, :tenantId, :taskId, :name, :uploadId, :kind, :parentBatchId,
                            :productCode, :sourceFilename, :sourceSha256, :sourceType, :sheetName,
                            :headerRowIndex, :tradeStartCol, :tradeEndCol, :layoutJson, :columnMapping,
                            :schemaHash, :status, :totalRows, :invalidRows, :note, :favorite, :pinned, :tags,
                            :createdBy, :createdAt, :updatedAt, :deletedAt)
                        """)
                .param("id", row.id())
                .param("tenantId", row.tenantId())
                .param("taskId", row.taskId())
                .param("name", row.name())
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
                .param("favorite", row.favorite())
                .param("pinned", row.pinned())
                .param("tags", row.tags())
                .param("createdBy", row.createdBy())
                .param("createdAt", row.createdAt())
                .param("updatedAt", row.updatedAt())
                .param("deletedAt", row.deletedAt())
                .update();
    }

    public boolean nameExists(String tenantId, String taskId, String name) {
        return jdbc.sql("""
                        SELECT COUNT(*) FROM import_batch
                        WHERE tenant_id = :tenantId AND LOWER(name) = LOWER(:name)
                          AND (task_id = :taskId OR (task_id IS NULL AND :taskId IS NULL))
                        """)
                .param("tenantId", tenantId).param("taskId", taskId).param("name", name)
                .query(Long.class).single() > 0;
    }

    /** First free name of the {@code Untitled}, {@code Untitled 2}, … series inside one task. */
    public String nextDefaultName(String tenantId, String taskId) {
        String candidate = DEFAULT_NAME;
        int suffix = 2;
        while (nameExists(tenantId, taskId, candidate)) {
            candidate = DEFAULT_NAME + " " + suffix;
            suffix++;
        }
        return candidate;
    }

    public void rename(String tenantId, String id, String name) {
        jdbc.sql("UPDATE import_batch SET name = :name WHERE tenant_id = :tenantId AND id = :id")
                .param("tenantId", tenantId).param("id", id).param("name", name).update();
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

    /** Batches by id, used to label search results with their batch name. */
    public List<ImportBatchRow> listByIds(String tenantId, Collection<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("SELECT " + COLUMNS + """
                         FROM import_batch WHERE tenant_id = :tenantId AND id IN (:ids)
                        """)
                .param("tenantId", tenantId).param("ids", ids)
                .query(this::map).list();
    }

    /** Batches of several tasks at once, oldest first, used to resolve source file locations. */    public List<ImportBatchRow> listByTaskIds(String tenantId, List<String> taskIds) {
        if (taskIds.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("SELECT " + COLUMNS + """
                         FROM import_batch WHERE tenant_id = :tenantId AND task_id IN (:taskIds)
                           AND status <> 'DELETED' ORDER BY created_at, sheet_name
                        """)
                .param("tenantId", tenantId).param("taskIds", taskIds)
                .query(this::map).list();
    }

    public List<ImportBatchRow> search(String tenantId, BatchFilter filter) {
        Map<String, Object> params = new LinkedHashMap<>();
        String where = whereClause(tenantId, filter, params, null);
        params.put("limit", filter.page().size());
        params.put("offset", filter.page().offset());
        return jdbc.sql("SELECT " + COLUMNS + " FROM import_batch b " + where + orderBy(filter.sort())
                + " LIMIT :limit OFFSET :offset")
                .params(params).query(this::map).list();
    }

    public long count(String tenantId, BatchFilter filter) {
        Map<String, Object> params = new LinkedHashMap<>();
        String where = whereClause(tenantId, filter, params, null);
        return jdbc.sql("SELECT COUNT(*) FROM import_batch b " + where).params(params)
                .query(Long.class).single();
    }

    /** Batches of the dock: pinned ones first, then the most recent ones. */
    public List<ImportBatchRow> dock(String tenantId, int limit, boolean includeDeleted) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("tenantId", tenantId);
        params.put("limit", limit);
        StringBuilder sql = new StringBuilder("SELECT " + COLUMNS + " FROM import_batch b WHERE b.tenant_id = :tenantId");
        if (!includeDeleted) {
            sql.append(" AND b.status <> 'DELETED'");
        }
        sql.append(" AND b.kind <> 'EXTERNAL_OUTPUT' ORDER BY b.pinned DESC, b.created_at DESC LIMIT :limit");
        return jdbc.sql(sql.toString()).params(params).query(this::map).list();
    }

    /** Total number of batches per facet value under the current filter, used by the console chips. */
    public Map<String, Long> countByColumn(String tenantId, BatchFilter filter, String column) {
        Map<String, Object> params = new LinkedHashMap<>();
        // the facet is counted with every filter applied except its own dimension
        String where = whereClause(tenantId, filter, params, column);
        Map<String, Long> counts = new LinkedHashMap<>();
        jdbc.sql("SELECT " + column + " AS k, COUNT(*) AS c FROM import_batch b " + where
                        + " GROUP BY " + column + " ORDER BY c DESC")
                .params(params)
                .query(rs -> {
                    String key = rs.getString("k");
                    counts.put(key == null ? "" : key, rs.getLong("c"));
                });
        return counts;
    }

    /** Raw tag column of the filtered rows; tag counting happens in the service. */
    public List<String> tagColumn(String tenantId, BatchFilter filter) {
        Map<String, Object> params = new LinkedHashMap<>();
        String where = whereClause(tenantId, filter, params, "tags");
        return jdbc.sql("SELECT b.tags FROM import_batch b " + where).params(params)
                .query(String.class).list();
    }

    /**
     * Builds the WHERE clause of the console query.
     *
     * @param skipColumn facet dimension to ignore, {@code null} to apply every filter
     */
    private String whereClause(String tenantId, BatchFilter filter, Map<String, Object> params,
            String skipColumn) {
        StringBuilder sql = new StringBuilder("WHERE b.tenant_id = :tenantId");
        params.put("tenantId", tenantId);
        boolean skipStatus = "status".equals(skipColumn);
        if (!skipStatus) {
            if (filter.status() != null && !filter.status().isBlank()) {
                sql.append(" AND b.status = :status");
                params.put("status", filter.status().trim().toUpperCase(java.util.Locale.ROOT));
            } else if (!filter.includeDeleted()) {
                sql.append(" AND b.status <> 'DELETED'");
            }
        }
        if (!"kind".equals(skipColumn) && !filter.includeExternal()) {
            sql.append(" AND b.kind <> 'EXTERNAL_OUTPUT'");
        }
        if (!"kind".equals(skipColumn) && filter.kind() != null && !filter.kind().isBlank()) {
            sql.append(" AND b.kind = :kind");
            params.put("kind", filter.kind().trim().toUpperCase(java.util.Locale.ROOT));
        }
        if (!"product_code".equals(skipColumn) && filter.product() != null && !filter.product().isBlank()) {
            sql.append(" AND b.product_code = :product");
            params.put("product", filter.product().trim().toUpperCase(java.util.Locale.ROOT));
        }
        if (!"tags".equals(skipColumn) && filter.tag() != null && !filter.tag().isBlank()) {
            sql.append(" AND LOWER(b.tags) LIKE :tag");
            params.put("tag", "%," + filter.tag().trim().toLowerCase(java.util.Locale.ROOT) + ",%");
        }
        if (!"favorite".equals(skipColumn) && filter.favorite() != null) {
            sql.append(" AND b.favorite = :favorite");
            params.put("favorite", filter.favorite());
        }
        if (!"pinned".equals(skipColumn) && filter.pinned() != null) {
            sql.append(" AND b.pinned = :pinned");
            params.put("pinned", filter.pinned());
        }
        if (filter.from() != null) {
            sql.append(" AND b.created_at >= :createdFrom");
            params.put("createdFrom", utcStartOfDay(filter.from()));
        }
        if (filter.to() != null) {
            sql.append(" AND b.created_at < :createdTo");
            params.put("createdTo", utcStartOfDay(filter.to().plusDays(1)));
        }
        if (filter.query() != null && !filter.query().isBlank()) {
            sql.append(" AND (LOWER(b.name) LIKE :query")
                    .append(" OR LOWER(b.source_filename) LIKE :query OR LOWER(b.sheet_name) LIKE :query")
                    .append(" OR LOWER(b.note) LIKE :query OR LOWER(b.tags) LIKE :query")
                    .append(" OR LOWER(b.product_code) LIKE :query OR LOWER(b.id) LIKE :query)");
            params.put("query", "%" + filter.query().trim().toLowerCase(java.util.Locale.ROOT) + "%");
        }
        return sql.toString();
    }

    private static java.time.LocalDateTime utcStartOfDay(java.time.LocalDate date) {
        return date.atStartOfDay(ac.core.common.TimeProvider.DISPLAY_ZONE)
                .withZoneSameInstant(java.time.ZoneOffset.UTC).toLocalDateTime();
    }

    private String orderBy(String sort) {
        String key = sort == null || sort.isBlank() ? "created" : sort.trim().toLowerCase(java.util.Locale.ROOT);
        boolean ascending = key.endsWith("_asc");
        String field = ascending ? key.substring(0, key.length() - 4)
                : key.endsWith("_desc") ? key.substring(0, key.length() - 5) : key;
        String direction = ascending ? " ASC" : " DESC";
        return switch (field) {
            case "name" -> " ORDER BY b.pinned DESC, LOWER(b.source_filename)" + direction + ", b.created_at DESC";
            case "rows" -> " ORDER BY b.pinned DESC, b.total_rows" + direction + ", b.created_at DESC";
            case "invalid" -> " ORDER BY b.pinned DESC, b.invalid_rows" + direction + ", b.created_at DESC";
            case "product" -> " ORDER BY b.pinned DESC, LOWER(b.product_code)" + direction + ", b.created_at DESC";
            default -> " ORDER BY b.pinned DESC, b.created_at" + direction + ", b.id";
        };
    }

    /** Stores the console metadata of one batch. */
    public void updateMeta(String tenantId, String id, String note, boolean favorite, boolean pinned,
            String tags, LocalDateTime updatedAt) {
        jdbc.sql("""
                        UPDATE import_batch SET note = :note, favorite = :favorite, pinned = :pinned, tags = :tags,
                            updated_at = :updatedAt
                        WHERE tenant_id = :tenantId AND id = :id
                        """)
                .param("note", note).param("favorite", favorite).param("pinned", pinned).param("tags", tags)
                .param("updatedAt", updatedAt).param("tenantId", tenantId).param("id", id).update();
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

    /**
     * Every batch of a task, including the ones that were already deleted on their own.
     *
     * <p>A purge has to reach those as well: their rows and files would otherwise stay behind without a
     * task to reach them from.
     *
     * @param tenantId tenant scope
     * @param taskId   task whose batches are collected
     * @return the batches in creation order
     */
    public List<ImportBatchRow> listByTaskForPurge(String tenantId, String taskId) {
        return jdbc.sql("SELECT " + COLUMNS + """
                         FROM import_batch WHERE tenant_id = :tenantId AND task_id = :taskId
                         ORDER BY created_at, sheet_name
                        """)
                .param("tenantId", tenantId).param("taskId", taskId)
                .query(this::map).list();
    }

    /** Physically removes the batches of a task; only used when the task itself is purged. */
    public int purgeByTask(String tenantId, String taskId) {
        return jdbc.sql("DELETE FROM import_batch WHERE tenant_id = :tenantId AND task_id = :taskId")
                .param("tenantId", tenantId).param("taskId", taskId).update();
    }

    /** Number of batches that still refer to an upload; zero means its files can be removed. */
    public long countByUploadId(String tenantId, String uploadId) {
        return jdbc.sql("SELECT COUNT(*) FROM import_batch WHERE tenant_id = :tenantId AND upload_id = :uploadId")
                .param("tenantId", tenantId).param("uploadId", uploadId)
                .query(Long.class).single();
    }

    public long countRecent(String tenantId, LocalDateTime since) {
        return jdbc.sql("""
                        SELECT COUNT(*) FROM import_batch
                        WHERE tenant_id = :tenantId AND status = 'ACTIVE' AND created_at >= :since
                        """)
                .param("tenantId", tenantId).param("since", since)
                .query(Long.class).single();
    }

    /** Active batches of one product; deleted batches no longer block a product delete. */
    public long countByProduct(String tenantId, String productCode) {
        return jdbc.sql("""
                        SELECT COUNT(*) FROM import_batch
                        WHERE tenant_id = :tenantId AND product_code = :productCode AND status <> 'DELETED'
                        """)
                .param("tenantId", tenantId).param("productCode", productCode).query(Long.class).single();
    }


    private ImportBatchRow map(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new ImportBatchRow(
                rs.getString("id"),
                rs.getString("tenant_id"),
                rs.getString("task_id"),
                rs.getString("name"),
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
                rs.getBoolean("favorite"),
                rs.getBoolean("pinned"),
                rs.getString("tags"),
                rs.getString("created_by"),
                rs.getObject("created_at", LocalDateTime.class),
                rs.getObject("updated_at", LocalDateTime.class),
                rs.getObject("deleted_at", LocalDateTime.class));
    }
}
