package ac.core.persistence;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** SQL access for {@code test_case}. */
@Repository
public class TestCaseRepository {

    private static final String COLUMNS = """
            id, tenant_id, batch_id, product_code, origin, row_no, source_row, test_case_id,
            test_case_id_source, original_record_id, business_record_id, key_value, data_json, managed_json,
            formula_json, extras_json, formula_count, search_text, status, version, cloned_from,
            created_at, updated_at
            """;

    private final JdbcClient jdbc;

    public TestCaseRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(TestCaseRecord row) {
        jdbc.sql("""
                        INSERT INTO test_case (id, tenant_id, batch_id, product_code, origin, row_no, source_row,
                            test_case_id, test_case_id_source, original_record_id, business_record_id, key_value,
                            data_json, managed_json, formula_json, extras_json, formula_count, search_text,
                            status, version, cloned_from, created_at, updated_at)
                        VALUES (:id, :tenantId, :batchId, :productCode, :origin, :rowNo, :sourceRow,
                            :testCaseId, :testCaseIdSource, :originalRecordId, :businessRecordId, :keyValue,
                            :dataJson, :managedJson, :formulaJson, :extrasJson, :formulaCount, :searchText,
                            :status, :version, :clonedFrom, :createdAt, :updatedAt)
                        """)
                .param("id", row.id())
                .param("tenantId", row.tenantId())
                .param("batchId", row.batchId())
                .param("productCode", row.productCode())
                .param("origin", row.origin())
                .param("rowNo", row.rowNo())
                .param("sourceRow", row.sourceRow())
                .param("testCaseId", row.testCaseId())
                .param("testCaseIdSource", row.testCaseIdSource())
                .param("originalRecordId", row.originalRecordId())
                .param("businessRecordId", row.businessRecordId())
                .param("keyValue", truncate(row.keyValue(), 200))
                .param("dataJson", row.dataJson())
                .param("managedJson", row.managedJson())
                .param("formulaJson", row.formulaJson())
                .param("extrasJson", row.extrasJson())
                .param("formulaCount", row.formulaCount())
                .param("searchText", truncate(row.searchText(), 4000))
                .param("status", row.status())
                .param("version", row.version())
                .param("clonedFrom", row.clonedFrom())
                .param("createdAt", row.createdAt())
                .param("updatedAt", row.updatedAt())
                .update();
    }

    public void insertAll(Collection<TestCaseRecord> rows) {
        for (TestCaseRecord row : rows) {
            insert(row);
        }
    }

    public Optional<TestCaseRecord> find(String tenantId, String id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM test_case WHERE tenant_id = :tenantId AND id = :id")
                .param("tenantId", tenantId).param("id", id).query(this::map).optional();
    }

    public List<TestCaseRecord> findByIds(String tenantId, Collection<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("SELECT " + COLUMNS + """
                         FROM test_case WHERE tenant_id = :tenantId AND id IN (:ids) AND status = 'ACTIVE'
                         ORDER BY product_code, row_no
                        """)
                .param("tenantId", tenantId).param("ids", ids).query(this::map).list();
    }

    public List<TestCaseRecord> page(String tenantId, CaseFilter filter) {
        StringBuilder sql = new StringBuilder("SELECT " + COLUMNS + " FROM test_case c WHERE c.tenant_id = :tenantId");
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("tenantId", tenantId);
        appendFilters(sql, params, filter);
        sql.append(" ORDER BY c.row_no LIMIT :limit OFFSET :offset");
        params.put("limit", filter.page().size());
        params.put("offset", filter.page().offset());
        return jdbc.sql(sql.toString()).params(params).query(this::map).list();
    }

    public long count(String tenantId, CaseFilter filter) {
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM test_case c WHERE c.tenant_id = :tenantId");
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("tenantId", tenantId);
        appendFilters(sql, params, filter);
        return jdbc.sql(sql.toString()).params(params).query(Long.class).single();
    }

    public List<TestCaseRecord> listForExport(String tenantId, String batchId) {
        return jdbc.sql("SELECT " + COLUMNS + """
                         FROM test_case WHERE tenant_id = :tenantId AND batch_id = :batchId
                           AND status = 'ACTIVE' ORDER BY row_no
                        """)
                .param("tenantId", tenantId).param("batchId", batchId).query(this::map).list();
    }

    public int updateCase(String tenantId, String id, int expectedVersion, String dataJson,
            String managedJson, String formulaJson, int formulaCount, String searchText, String keyValue,
            String businessRecordId, LocalDateTime updatedAt) {
        return jdbc.sql("""
                        UPDATE test_case SET data_json = :dataJson, managed_json = :managedJson,
                            formula_json = :formulaJson, formula_count = :formulaCount, search_text = :searchText,
                            key_value = :keyValue, business_record_id = :businessRecordId,
                            version = version + 1, updated_at = :updatedAt
                        WHERE tenant_id = :tenantId AND id = :id AND version = :expectedVersion
                        """)
                .param("dataJson", dataJson)
                .param("managedJson", managedJson)
                .param("formulaJson", formulaJson)
                .param("formulaCount", formulaCount)
                .param("searchText", truncate(searchText, 4000))
                .param("keyValue", truncate(keyValue, 200))
                .param("businessRecordId", businessRecordId)
                .param("updatedAt", updatedAt)
                .param("tenantId", tenantId)
                .param("id", id)
                .param("expectedVersion", expectedVersion)
                .update();
    }

    public int softDelete(String tenantId, String id, LocalDateTime updatedAt) {
        return jdbc.sql("""
                        UPDATE test_case SET status = 'DELETED', updated_at = :updatedAt
                        WHERE tenant_id = :tenantId AND id = :id AND status = 'ACTIVE'
                        """)
                .param("updatedAt", updatedAt).param("tenantId", tenantId).param("id", id).update();
    }

    public int softDeleteMany(String tenantId, Collection<String> ids, LocalDateTime updatedAt) {
        if (ids == null || ids.isEmpty()) {
            return 0;
        }
        return jdbc.sql("""
                        UPDATE test_case SET status = 'DELETED', updated_at = :updatedAt
                        WHERE tenant_id = :tenantId AND id IN (:ids) AND status = 'ACTIVE'
                        """)
                .param("updatedAt", updatedAt).param("tenantId", tenantId).param("ids", ids).update();
    }

    public int softDeleteByBatch(String tenantId, String batchId, LocalDateTime updatedAt) {
        return jdbc.sql("""
                        UPDATE test_case SET status = 'DELETED', updated_at = :updatedAt
                        WHERE tenant_id = :tenantId AND batch_id = :batchId AND status = 'ACTIVE'
                        """)
                .param("updatedAt", updatedAt).param("tenantId", tenantId).param("batchId", batchId).update();
    }

    public int softDeleteByTask(String tenantId, String taskId, LocalDateTime updatedAt) {
        return jdbc.sql("""
                        UPDATE test_case SET status = 'DELETED', updated_at = :updatedAt
                        WHERE tenant_id = :tenantId AND status = 'ACTIVE'
                          AND batch_id IN (SELECT id FROM import_batch WHERE tenant_id = :tenantId AND task_id = :taskId)
                        """)
                .param("updatedAt", updatedAt).param("tenantId", tenantId).param("taskId", taskId).update();
    }

    /** Existing key values of a product, used to detect duplicate keys before an import or clone. */
    public List<String> existingKeys(String tenantId, String productCode, Collection<String> keys) {
        if (keys == null || keys.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("""
                        SELECT key_value FROM test_case
                        WHERE tenant_id = :tenantId AND product_code = :productCode AND status = 'ACTIVE'
                          AND key_value IN (:keys)
                        """)
                .param("tenantId", tenantId).param("productCode", productCode).param("keys", keys)
                .query(String.class).list();
    }

    public int countByBatch(String tenantId, String batchId) {
        return jdbc.sql("""
                        SELECT COUNT(*) FROM test_case
                        WHERE tenant_id = :tenantId AND batch_id = :batchId AND status = 'ACTIVE'
                        """)
                .param("tenantId", tenantId).param("batchId", batchId).query(Integer.class).single();
    }

    public long countByOrigin(String tenantId, String origin) {
        return jdbc.sql("""
                        SELECT COUNT(*) FROM test_case
                        WHERE tenant_id = :tenantId AND origin = :origin AND status = 'ACTIVE'
                        """)
                .param("tenantId", tenantId).param("origin", origin).query(Long.class).single();
    }

    public long countByProduct(String tenantId, String productCode) {
        return jdbc.sql("SELECT COUNT(*) FROM test_case WHERE tenant_id = :tenantId AND product_code = :productCode")
                .param("tenantId", tenantId).param("productCode", productCode).query(Long.class).single();
    }

    private void appendFilters(StringBuilder sql, Map<String, Object> params, CaseFilter filter) {
        sql.append(" AND c.status = 'ACTIVE'");
        if (filter.batchId() != null && !filter.batchId().isBlank()) {
            sql.append(" AND c.batch_id = :batchId");
            params.put("batchId", filter.batchId());
        }
        if (Boolean.TRUE.equals(filter.hasFormula())) {
            sql.append(" AND c.formula_count > 0");
        }
        if (filter.query() != null && !filter.query().isBlank()) {
            sql.append(" AND LOWER(c.search_text) LIKE :query");
            params.put("query", "%" + filter.query().trim().toLowerCase(java.util.Locale.ROOT) + "%");
        }
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    private TestCaseRecord map(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new TestCaseRecord(
                rs.getString("id"),
                rs.getString("tenant_id"),
                rs.getString("batch_id"),
                rs.getString("product_code"),
                rs.getString("origin"),
                rs.getInt("row_no"),
                rs.getObject("source_row", Integer.class),
                rs.getString("test_case_id"),
                rs.getString("test_case_id_source"),
                rs.getString("original_record_id"),
                rs.getString("business_record_id"),
                rs.getString("key_value"),
                rs.getString("data_json"),
                rs.getString("managed_json"),
                rs.getString("formula_json"),
                rs.getString("extras_json"),
                rs.getInt("formula_count"),
                rs.getString("search_text"),
                rs.getString("status"),
                rs.getInt("version"),
                rs.getString("cloned_from"),
                rs.getObject("created_at", LocalDateTime.class),
                rs.getObject("updated_at", LocalDateTime.class));
    }
}
