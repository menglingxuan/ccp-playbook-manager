package ac.core.persistence;

import ac.core.common.PageQuery;
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
            test_case_id_source, original_record_id, business_record_id, core_business_record_id, key_value,
            data_json, managed_json, raw_json,
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
                            test_case_id, test_case_id_source, original_record_id, business_record_id,
                            core_business_record_id, key_value,
                            data_json, managed_json, raw_json, formula_json, extras_json, formula_count,
                            search_text, status, version, cloned_from, created_at, updated_at)
                        VALUES (:id, :tenantId, :batchId, :productCode, :origin, :rowNo, :sourceRow,
                            :testCaseId, :testCaseIdSource, :originalRecordId, :businessRecordId,
                            :coreBusinessRecordId, :keyValue,
                            :dataJson, :managedJson, :rawJson, :formulaJson, :extrasJson, :formulaCount,
                            :searchText, :status, :version, :clonedFrom, :createdAt, :updatedAt)
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
                .param("coreBusinessRecordId", row.coreBusinessRecordId())
                .param("keyValue", truncate(row.keyValue(), 200))
                .param("dataJson", row.dataJson())
                .param("managedJson", row.managedJson())
                .param("rawJson", row.rawJson())
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
        sql.append(orderBy(filter.sort()));
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

    /**
     * Rewrites one case row.
     *
     * @param expectedVersion optimistic lock version, {@code null} to overwrite unconditionally
     * @return number of updated rows
     */
    public int updateCase(String tenantId, String id, Integer expectedVersion, String dataJson,
            String managedJson, String rawJson, String formulaJson, int formulaCount, String searchText,
            String keyValue, String businessRecordId, String coreBusinessRecordId, LocalDateTime updatedAt) {
        boolean guarded = expectedVersion != null;
        String sql = """
                        UPDATE test_case SET data_json = :dataJson, managed_json = :managedJson,
                            raw_json = :rawJson,
                            formula_json = :formulaJson, formula_count = :formulaCount, search_text = :searchText,
                            key_value = :keyValue, business_record_id = :businessRecordId,
                            core_business_record_id = :coreBusinessRecordId,
                            version = version + 1, updated_at = :updatedAt
                        WHERE tenant_id = :tenantId AND id = :id
                        """ + (guarded ? " AND version = :expectedVersion" : "");
        var spec = jdbc.sql(sql)
                .param("dataJson", dataJson)
                .param("managedJson", managedJson)
                .param("rawJson", rawJson)
                .param("formulaJson", formulaJson)
                .param("formulaCount", formulaCount)
                .param("searchText", truncate(searchText, 4000))
                .param("keyValue", truncate(keyValue, 200))
                .param("businessRecordId", businessRecordId)
                .param("coreBusinessRecordId", coreBusinessRecordId)
                .param("updatedAt", updatedAt)
                .param("tenantId", tenantId)
                .param("id", id);
        if (guarded) {
            spec = spec.param("expectedVersion", expectedVersion);
        }
        return spec.update();
    }

    /**
     * Active cases of one batch matching the optional filter, capped by {@code limit}.
     *
     * @param batchId    batch scope
     * @param query      free text matched against the search text, may be {@code null}
     * @param hasFormula only cases with formulas when true
     * @param limit      maximum number of rows returned
     */
    public List<TestCaseRecord> listForBulkUpdate(String tenantId, String batchId, String query,
            Boolean hasFormula, int limit) {
        StringBuilder sql = new StringBuilder("SELECT " + COLUMNS + " FROM test_case c WHERE c.tenant_id = :tenantId");
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("tenantId", tenantId);
        appendFilters(sql, params, new CaseFilter(batchId, query, hasFormula, "row", null));
        sql.append(" ORDER BY c.row_no LIMIT :limit");
        params.put("limit", limit);
        return jdbc.sql(sql.toString()).params(params).query(this::map).list();
    }

    /**
     * Active cases of several batches matching the optional filter, used by the global case search.
     *
     * @param batchIds batch scope, empty for every batch of the tenant
     * @param query    free text matched against the search text, may be {@code null}
     * @param page     page request, {@code null} returns every matching row
     */
    public List<TestCaseRecord> pageInBatches(String tenantId, Collection<String> batchIds, String query,
            PageQuery page) {
        StringBuilder sql = new StringBuilder("SELECT " + COLUMNS + " FROM test_case c WHERE c.tenant_id = :tenantId");
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("tenantId", tenantId);
        appendFilters(sql, params, new CaseFilter(null, query, null, "updated_desc", null));
        if (batchIds != null && !batchIds.isEmpty()) {
            sql.append(" AND c.batch_id IN (:batchIds)");
            params.put("batchIds", batchIds);
        }
        sql.append(" ORDER BY c.updated_at DESC, c.row_no");
        if (page != null) {
            sql.append(" LIMIT :limit OFFSET :offset");
            params.put("limit", page.size());
            params.put("offset", page.offset());
        }
        return jdbc.sql(sql.toString()).params(params).query(this::map).list();
    }

    /**
     * Rows imported before the core business record id existed, oldest first.
     *
     * @param limit maximum number of rows to return
     */
    public List<TestCaseRecord> listMissingCoreBusinessId(int limit) {
        return jdbc.sql("SELECT " + COLUMNS + """
                         FROM test_case WHERE core_business_record_id IS NULL
                         ORDER BY created_at LIMIT :limit
                        """)
                .param("limit", limit).query(this::map).list();
    }

    /** Distinct tenants that own at least one case row. */
    public List<String> listTenantIds() {
        return jdbc.sql("SELECT DISTINCT tenant_id FROM test_case ORDER BY tenant_id")
                .query(String.class).list();
    }

    /**
     * One page of a tenant's cases ordered by id, used by the identity backfill.
     *
     * @param afterId last id of the previous page, empty for the first page
     */
    public List<TestCaseRecord> pageForIdentity(String tenantId, String afterId, int limit) {
        return jdbc.sql("SELECT " + COLUMNS + """
                         FROM test_case WHERE tenant_id = :tenantId AND id > :afterId
                         ORDER BY id LIMIT :limit
                        """)
                .param("tenantId", tenantId)
                .param("afterId", afterId == null ? "" : afterId)
                .param("limit", limit)
                .query(this::map).list();
    }

    /** Business record ids that appear more than once among the active cases of one batch. */
    public List<String> duplicateBusinessRecordIds(String tenantId, String batchId) {
        return duplicateRecordIds("business_record_id", tenantId, batchId);
    }

    /** Core business record ids that appear more than once among the active cases of one batch. */
    public List<String> duplicateCoreBusinessRecordIds(String tenantId, String batchId) {
        return duplicateRecordIds("core_business_record_id", tenantId, batchId);
    }

    private List<String> duplicateRecordIds(String column, String tenantId, String batchId) {
        String sql = "SELECT " + column + " FROM test_case"
                + " WHERE tenant_id = :tenantId AND batch_id = :batchId AND status = 'ACTIVE'"
                + " AND " + column + " IS NOT NULL AND " + column + " <> ''"
                + " GROUP BY " + column + " HAVING COUNT(*) > 1 ORDER BY " + column;
        return jdbc.sql(sql)
                .param("tenantId", tenantId)
                .param("batchId", batchId)
                .query(String.class).list();
    }
    /** Fills both business ids of one row without touching its version or timestamp. */
    public int updateBusinessIds(String tenantId, String id, String businessRecordId, String coreBusinessRecordId) {
        return jdbc.sql("""
                        UPDATE test_case SET business_record_id = :businessRecordId,
                            core_business_record_id = :coreBusinessRecordId
                        WHERE tenant_id = :tenantId AND id = :id
                        """)
                .param("businessRecordId", businessRecordId)
                .param("coreBusinessRecordId", coreBusinessRecordId)
                .param("tenantId", tenantId)
                .param("id", id)
                .update();
    }

    /** Number of rows {@link #pageInBatches} would return without the page window. */    public long countInBatches(String tenantId, Collection<String> batchIds, String query) {
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM test_case c WHERE c.tenant_id = :tenantId");
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("tenantId", tenantId);
        appendFilters(sql, params, new CaseFilter(null, query, null, "row", null));
        if (batchIds != null && !batchIds.isEmpty()) {
            sql.append(" AND c.batch_id IN (:batchIds)");
            params.put("batchIds", batchIds);
        }
        return jdbc.sql(sql.toString()).params(params).query(Long.class).single();
    }

    public int softDeleteByBatch(String tenantId, String batchId, LocalDateTime updatedAt) {
        return jdbc.sql("""
                        UPDATE test_case SET status = 'DELETED', updated_at = :updatedAt
                        WHERE tenant_id = :tenantId AND batch_id = :batchId AND status = 'ACTIVE'
                        """)
                .param("updatedAt", updatedAt).param("tenantId", tenantId).param("batchId", batchId).update();
    }

    /**
     * Physically removes the cases of the given batches, together with the legacy dispatch links that
     * point at them.
     *
     * <p>Used when a whole task is purged: the rows are gone afterwards, so nothing can reference them
     * any more. Deleted cases are included, otherwise a previously soft deleted case would survive.
     *
     * @param tenantId tenant scope
     * @param batchIds batches whose cases disappear
     * @return number of removed cases
     */
    public int purgeByBatches(String tenantId, Collection<String> batchIds) {
        if (batchIds == null || batchIds.isEmpty()) {
            return 0;
        }
        deleteDispatchLinks(tenantId, "batch_id IN (:batchIds)", Map.of("batchIds", batchIds));
        return jdbc.sql("DELETE FROM test_case WHERE tenant_id = :tenantId AND batch_id IN (:batchIds)")
                .param("tenantId", tenantId).param("batchIds", batchIds).update();
    }

    /**
     * Physically removes single cases and drops the references other cases hold on them.
     *
     * @param tenantId tenant scope
     * @param caseIds  cases to remove
     * @return number of removed cases
     */
    public int purgeByIds(String tenantId, Collection<String> caseIds) {
        if (caseIds == null || caseIds.isEmpty()) {
            return 0;
        }
        deleteDispatchLinks(tenantId, "id IN (:caseIds)", Map.of("caseIds", caseIds));
        jdbc.sql("UPDATE test_case SET cloned_from = NULL WHERE tenant_id = :tenantId AND cloned_from IN (:caseIds)")
                .param("tenantId", tenantId).param("caseIds", caseIds).update();
        return jdbc.sql("DELETE FROM test_case WHERE tenant_id = :tenantId AND id IN (:caseIds)")
                .param("tenantId", tenantId).param("caseIds", caseIds).update();
    }

    /**
     * Removes the legacy {@code external_job_case} rows of the selected cases.
     *
     * <p>The table is a leftover of the removed dispatch module and has no other reader, but it holds
     * case ids, so leaving rows behind would keep dead references in the database.
     */
    private void deleteDispatchLinks(String tenantId, String caseSelector, Map<String, ?> params) {
        StringBuilder sql = new StringBuilder(
                "DELETE FROM external_job_case WHERE tenant_id = :tenantId AND case_id IN (SELECT id FROM test_case WHERE tenant_id = :tenantId AND ")
                .append(caseSelector).append(')');
        var statement = jdbc.sql(sql.toString()).param("tenantId", tenantId);
        for (Map.Entry<String, ?> entry : params.entrySet()) {
            statement = statement.param(entry.getKey(), entry.getValue());
        }
        statement.update();
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

    /** Active cases of one product; deleted rows no longer block a product delete. */
    public long countByProduct(String tenantId, String productCode) {
        return jdbc.sql("""
                        SELECT COUNT(*) FROM test_case
                        WHERE tenant_id = :tenantId AND product_code = :productCode AND status = 'ACTIVE'
                        """)
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

    private static String orderBy(String sort) {
        String key = sort == null || sort.isBlank() ? "row" : sort.trim().toLowerCase(java.util.Locale.ROOT);
        boolean ascending = key.endsWith("_asc");
        boolean descending = key.endsWith("_desc");
        String field = ascending ? key.substring(0, key.length() - 4)
                : descending ? key.substring(0, key.length() - 5) : key;
        // the natural row order stays ascending unless a direction is asked for
        String direction = ascending || (!descending && "row".equals(field)) ? " ASC" : " DESC";
        String order = switch (field) {
            case "testcase" -> "c.test_case_id";
            case "key" -> "c.key_value";
            case "formulas" -> "c.formula_count";
            case "updated" -> "c.updated_at";
            case "row" -> "c.row_no";
            default -> null;
        };
        if (order == null) {
            return " ORDER BY c.row_no LIMIT :limit OFFSET :offset";
        }
        return " ORDER BY " + order + direction + ", c.row_no LIMIT :limit OFFSET :offset";
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
                rs.getString("core_business_record_id"),
                rs.getString("key_value"),
                rs.getString("data_json"),
                rs.getString("managed_json"),
                rs.getString("raw_json"),
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
