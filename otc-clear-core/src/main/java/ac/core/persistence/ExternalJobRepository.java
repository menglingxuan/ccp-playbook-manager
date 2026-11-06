package ac.core.persistence;

import ac.core.common.PageQuery;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** SQL access for {@code external_job} and {@code external_job_case}. */
@Repository
public class ExternalJobRepository {

    private static final String COLUMNS = """
            id, tenant_id, product_code, case_count, folder_path, input_csv_path, request_id,
            external_job_id, status, dispatch_payload, last_response, message, created_by, created_at,
            dispatched_at, finished_at, last_polled_at
            """;

    private final JdbcClient jdbc;

    public ExternalJobRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(ExternalJobRow row) {
        jdbc.sql("""
                        INSERT INTO external_job (id, tenant_id, product_code, case_count, folder_path,
                            input_csv_path, request_id, external_job_id, status, dispatch_payload, last_response,
                            message, created_by, created_at, dispatched_at, finished_at, last_polled_at)
                        VALUES (:id, :tenantId, :productCode, :caseCount, :folderPath, :inputCsvPath, :requestId,
                            :externalJobId, :status, :dispatchPayload, :lastResponse, :message, :createdBy,
                            :createdAt, :dispatchedAt, :finishedAt, :lastPolledAt)
                        """)
                .param("id", row.id())
                .param("tenantId", row.tenantId())
                .param("productCode", row.productCode())
                .param("caseCount", row.caseCount())
                .param("folderPath", row.folderPath())
                .param("inputCsvPath", row.inputCsvPath())
                .param("requestId", row.requestId())
                .param("externalJobId", row.externalJobId())
                .param("status", row.status())
                .param("dispatchPayload", row.dispatchPayload())
                .param("lastResponse", row.lastResponse())
                .param("message", row.message())
                .param("createdBy", row.createdBy())
                .param("createdAt", row.createdAt())
                .param("dispatchedAt", row.dispatchedAt())
                .param("finishedAt", row.finishedAt())
                .param("lastPolledAt", row.lastPolledAt())
                .update();
    }

    public void insertCases(String tenantId, String jobId, List<String> caseIds) {
        int rowNo = 0;
        for (String caseId : caseIds) {
            rowNo++;
            jdbc.sql("""
                            INSERT INTO external_job_case (tenant_id, job_id, case_id, row_no)
                            VALUES (:tenantId, :jobId, :caseId, :rowNo)
                            """)
                    .param("tenantId", tenantId).param("jobId", jobId).param("caseId", caseId)
                    .param("rowNo", rowNo).update();
        }
    }

    public List<String> caseIds(String jobId) {
        return jdbc.sql("SELECT case_id FROM external_job_case WHERE job_id = :jobId ORDER BY row_no")
                .param("jobId", jobId).query(String.class).list();
    }

    public Optional<ExternalJobRow> find(String tenantId, String id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM external_job WHERE tenant_id = :tenantId AND id = :id")
                .param("tenantId", tenantId).param("id", id).query(this::map).optional();
    }

    public List<ExternalJobRow> page(String tenantId, String status, String product, String query,
            PageQuery page) {
        StringBuilder sql = new StringBuilder("SELECT " + COLUMNS + " FROM external_job j WHERE j.tenant_id = :tenantId");
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("tenantId", tenantId);
        appendFilters(sql, params, status, product, query);
        sql.append(" ORDER BY j.created_at DESC LIMIT :limit OFFSET :offset");
        params.put("limit", page.size());
        params.put("offset", page.offset());
        return jdbc.sql(sql.toString()).params(params).query(this::map).list();
    }

    public long count(String tenantId, String status, String product, String query) {
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM external_job j WHERE j.tenant_id = :tenantId");
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("tenantId", tenantId);
        appendFilters(sql, params, status, product, query);
        return jdbc.sql(sql.toString()).params(params).query(Long.class).single();
    }

    public List<ExternalJobRow> activeJobs(String tenantId) {
        return jdbc.sql("SELECT " + COLUMNS + """
                         FROM external_job WHERE tenant_id = :tenantId
                           AND status IN ('PENDING', 'DISPATCHED', 'RUNNING', 'UNKNOWN')
                         ORDER BY created_at
                        """)
                .param("tenantId", tenantId).query(this::map).list();
    }

    public List<ExternalJobRow> jobsWithStatus(String tenantId, String status) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM external_job WHERE tenant_id = :tenantId AND status = :status")
                .param("tenantId", tenantId).param("status", status).query(this::map).list();
    }

    public void updateDispatch(String tenantId, String id, String externalJobId, String status,
            LocalDateTime dispatchedAt, String payload, String response, String message) {
        jdbc.sql("""
                        UPDATE external_job SET external_job_id = :externalJobId, status = :status,
                            dispatched_at = :dispatchedAt, dispatch_payload = :payload, last_response = :response,
                            message = :message
                        WHERE tenant_id = :tenantId AND id = :id
                        """)
                .param("externalJobId", externalJobId).param("status", status)
                .param("dispatchedAt", dispatchedAt).param("payload", payload).param("response", response)
                .param("message", message).param("tenantId", tenantId).param("id", id).update();
    }

    public void updateStatus(String tenantId, String id, String status, String response, String message,
            LocalDateTime finishedAt, LocalDateTime polledAt) {
        jdbc.sql("""
                        UPDATE external_job SET status = :status, last_response = :response, message = :message,
                            finished_at = COALESCE(:finishedAt, finished_at), last_polled_at = :polledAt
                        WHERE tenant_id = :tenantId AND id = :id
                        """)
                .param("status", status).param("response", response).param("message", message)
                .param("finishedAt", finishedAt).param("polledAt", polledAt)
                .param("tenantId", tenantId).param("id", id).update();
    }

    public void updateMessage(String tenantId, String id, String message, LocalDateTime polledAt) {
        jdbc.sql("""
                        UPDATE external_job SET message = :message, last_polled_at = :polledAt
                        WHERE tenant_id = :tenantId AND id = :id
                        """)
                .param("message", message).param("polledAt", polledAt)
                .param("tenantId", tenantId).param("id", id).update();
    }

    private void appendFilters(StringBuilder sql, Map<String, Object> params, String status, String product,
            String query) {
        if (status != null && !status.isBlank()) {
            sql.append(" AND j.status = :status");
            params.put("status", status);
        }
        if (product != null && !product.isBlank()) {
            sql.append(" AND j.product_code = :product");
            params.put("product", product.toUpperCase(java.util.Locale.ROOT));
        }
        if (query != null && !query.isBlank()) {
            sql.append(" AND (LOWER(j.id) LIKE :query OR LOWER(j.request_id) LIKE :query OR LOWER(j.folder_path) LIKE :query)");
            params.put("query", "%" + query.trim().toLowerCase(java.util.Locale.ROOT) + "%");
        }
    }

    private ExternalJobRow map(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new ExternalJobRow(
                rs.getString("id"),
                rs.getString("tenant_id"),
                rs.getString("product_code"),
                rs.getInt("case_count"),
                rs.getString("folder_path"),
                rs.getString("input_csv_path"),
                rs.getString("request_id"),
                rs.getString("external_job_id"),
                rs.getString("status"),
                rs.getString("dispatch_payload"),
                rs.getString("last_response"),
                rs.getString("message"),
                rs.getString("created_by"),
                rs.getObject("created_at", LocalDateTime.class),
                rs.getObject("dispatched_at", LocalDateTime.class),
                rs.getObject("finished_at", LocalDateTime.class),
                rs.getObject("last_polled_at", LocalDateTime.class));
    }
}
