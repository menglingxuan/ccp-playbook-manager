package ac.core.persistence;

import ac.core.service.search.SearchQuery;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Cross entity search behind the console global search.
 *
 * <p>Both queries stay inside one tenant, skip soft deleted rows and cap the result, so a broad
 * term never returns an unbounded page.
 */
@Repository
public class SearchRepository {

    private final JdbcClient jdbc;

    public SearchRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Batch hits matching the batch scoped part of the expression. */
    public List<BatchHit> searchBatches(String tenantId, SearchQuery query, int limit) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("tenantId", tenantId);
        params.put("limit", limit);
        StringBuilder sql = new StringBuilder("""
                SELECT b.id, b.task_id, b.product_code, b.source_filename, b.sheet_name, b.note, b.tags,
                       b.status, b.total_rows, b.created_at
                FROM import_batch b
                WHERE b.tenant_id = :tenantId AND b.status <> 'DELETED' AND b.kind <> 'EXTERNAL_OUTPUT'
                """);
        if (query.batchId() != null) {
            sql.append(" AND b.id = :batchId");
            params.put("batchId", query.batchId());
        }
        if (query.product() != null) {
            sql.append(" AND LOWER(b.product_code) = :product");
            params.put("product", query.product().toLowerCase(Locale.ROOT));
        }
        if (query.tag() != null) {
            sql.append(" AND LOWER(b.tags) LIKE :tag");
            params.put("tag", "%," + query.tag().toLowerCase(Locale.ROOT) + ",%");
        }
        String text = query.batchText();
        if (text != null) {
            sql.append(" AND (LOWER(b.source_filename) LIKE :query OR LOWER(b.sheet_name) LIKE :query")
                    .append(" OR LOWER(b.note) LIKE :query OR LOWER(b.tags) LIKE :query")
                    .append(" OR LOWER(b.product_code) LIKE :query OR LOWER(b.id) LIKE :query)")
                    ;
            params.put("query", "%" + text.toLowerCase(Locale.ROOT) + "%");
        }
        sql.append(" ORDER BY b.pinned DESC, b.created_at DESC LIMIT :limit");
        return jdbc.sql(sql.toString()).params(params).query((rs, rowNum) -> new BatchHit(
                rs.getString("id"),
                rs.getString("task_id"),
                rs.getString("product_code"),
                rs.getString("source_filename"),
                rs.getString("sheet_name"),
                rs.getString("note"),
                rs.getString("tags"),
                rs.getString("status"),
                rs.getInt("total_rows"),
                rs.getObject("created_at", java.time.LocalDateTime.class))).list();
    }

    /** Case hits matching the case scoped part of the expression. */
    public List<CaseHit> searchCases(String tenantId, SearchQuery query, int limit) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("tenantId", tenantId);
        params.put("limit", limit);
        StringBuilder sql = new StringBuilder("""
                SELECT c.id, c.batch_id, c.product_code, c.row_no, c.test_case_id, c.key_value,
                       c.formula_count, b.source_filename
                FROM test_case c
                LEFT JOIN import_batch b ON b.tenant_id = c.tenant_id AND b.id = c.batch_id
                WHERE c.tenant_id = :tenantId AND c.status = 'ACTIVE'
                """);
        if (query.batchId() != null) {
            sql.append(" AND c.batch_id = :batchId");
            params.put("batchId", query.batchId());
        }
        // batch level qualifiers narrow the case group as well, so "tag:x" lists the cases behind the
        // tagged batches instead of every case of the tenant
        if (query.product() != null) {
            sql.append(" AND LOWER(b.product_code) = :product");
            params.put("product", query.product().toLowerCase(Locale.ROOT));
        }
        if (query.tag() != null) {
            sql.append(" AND LOWER(b.tags) LIKE :tag");
            params.put("tag", "%," + query.tag().toLowerCase(Locale.ROOT) + ",%");
        }
        String text = query.caseSearchText();
        if (text != null) {
            sql.append(" AND (LOWER(c.search_text) LIKE :query OR LOWER(c.test_case_id) LIKE :query")
                    .append(" OR LOWER(c.key_value) LIKE :query)");
            params.put("query", "%" + text.toLowerCase(Locale.ROOT) + "%");
        }
        sql.append(" ORDER BY c.updated_at DESC, c.row_no LIMIT :limit");
        return jdbc.sql(sql.toString()).params(params).query((rs, rowNum) -> new CaseHit(
                rs.getString("id"),
                rs.getString("batch_id"),
                rs.getString("product_code"),
                rs.getInt("row_no"),
                rs.getString("test_case_id"),
                rs.getString("key_value"),
                rs.getInt("formula_count"),
                rs.getString("source_filename"))).list();
    }

    /**
     * One batch hit.
     *
     * @param batchId        batch id
     * @param taskId         owning task, {@code null} for clones
     * @param productCode    product code
     * @param sourceFilename source file name
     * @param sheetName      sheet name
     * @param note           note
     * @param tags           encoded tag column
     * @param status         batch status
     * @param totalRows      imported rows
     * @param createdAt      creation timestamp
     */
    public record BatchHit(String batchId, String taskId, String productCode, String sourceFilename,
            String sheetName, String note, String tags, String status, int totalRows,
            java.time.LocalDateTime createdAt) {
    }

    /**
     * One case hit.
     *
     * @param caseId         case id
     * @param batchId        owning batch
     * @param productCode    product code
     * @param rowNo          row order inside the batch
     * @param testCaseId     test case id
     * @param keyValue       unique field value
     * @param formulaCount   number of captured formulas
     * @param sourceFilename source file of the batch, for context
     */
    public record CaseHit(String caseId, String batchId, String productCode, int rowNo, String testCaseId,
            String keyValue, int formulaCount, String sourceFilename) {
    }
}
