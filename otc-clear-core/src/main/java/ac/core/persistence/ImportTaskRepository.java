package ac.core.persistence;

import ac.core.common.PageQuery;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** SQL access for {@code import_task}. */
@Repository
public class ImportTaskRepository {

    /** Default name of a fresh task; later ones get a numeric suffix to stay unique. */
    public static final String DEFAULT_NAME = "Untitled";

    /** The task has at least one batch produced by a product of the given base product. */
    private static final String BASE_PRODUCT_EXISTS = """
            EXISTS (SELECT 1 FROM import_batch b
                    JOIN product p ON p.tenant_id = b.tenant_id AND p.code = b.product_code
                    WHERE b.task_id = t.id AND p.base_product_code = :product)""";

    private static final String COLUMNS = """
            id, tenant_id, kind, status, name, source_name, source_sha256, source_md5, sheet_count, batch_count,
            total_rows, invalid_rows, params_json, issues_json, message, note, created_by, created_at, started_at,
            finished_at
            """;

    private final JdbcClient jdbc;

    public ImportTaskRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(ImportTaskRow row) {
        jdbc.sql("""
                        INSERT INTO import_task (id, tenant_id, kind, status, name, source_name, source_sha256,
                            source_md5, sheet_count, batch_count, total_rows, invalid_rows, params_json, issues_json,
                            message, note, created_by, created_at, started_at, finished_at)
                        VALUES (:id, :tenantId, :kind, :status, :name, :sourceName, :sourceSha256, :sourceMd5,
                            :sheetCount, :batchCount, :totalRows, :invalidRows, :paramsJson, :issuesJson, :message,
                            :note, :createdBy, :createdAt, :startedAt, :finishedAt)
                        """)
                .param("id", row.id())
                .param("tenantId", row.tenantId())
                .param("kind", row.kind())
                .param("status", row.status())
                .param("name", row.name())
                .param("sourceName", row.sourceName())
                .param("sourceSha256", row.sourceSha256())
                .param("sourceMd5", row.sourceMd5())
                .param("sheetCount", row.sheetCount())
                .param("batchCount", row.batchCount())
                .param("totalRows", row.totalRows())
                .param("invalidRows", row.invalidRows())
                .param("paramsJson", row.paramsJson())
                .param("issuesJson", row.issuesJson())
                .param("message", row.message()).param("note", row.note())
                .param("createdBy", row.createdBy())
                .param("createdAt", row.createdAt())
                .param("startedAt", row.startedAt())
                .param("finishedAt", row.finishedAt())
                .update();
    }

    /** True when another task of the tenant already uses this name. */
    public boolean nameExists(String tenantId, String name) {
        return jdbc.sql("SELECT COUNT(*) FROM import_task WHERE tenant_id = :tenantId AND LOWER(name) = LOWER(:name)")
                .param("tenantId", tenantId).param("name", name)
                .query(Long.class).single() > 0;
    }

    /** First free name of the {@code Untitled}, {@code Untitled 2}, … series. */
    public String nextDefaultName(String tenantId) {
        String candidate = DEFAULT_NAME;
        int suffix = 2;
        while (nameExists(tenantId, candidate)) {
            candidate = DEFAULT_NAME + " " + suffix;
            suffix++;
        }
        return candidate;
    }

    public void rename(String tenantId, String id, String name) {
        jdbc.sql("UPDATE import_task SET name = :name WHERE tenant_id = :tenantId AND id = :id")
                .param("tenantId", tenantId).param("id", id).param("name", name).update();
    }

    /** Keeps the batch counter of a task aligned when batches are cloned or removed. */
    public void adjustBatchCount(String tenantId, String taskId, int delta) {
        if (taskId == null || delta == 0) {
            return;
        }
        jdbc.sql("""
                        UPDATE import_task SET batch_count = GREATEST(0, batch_count + :delta)
                        WHERE tenant_id = :tenantId AND id = :id
                        """)
                .param("delta", delta).param("tenantId", tenantId).param("id", taskId).update();
    }

    /**
     * Base products behind the tasks, derived from the product catalogue of their batches.
     *
     * @return task id to the distinct base product codes, sorted
     */
    public Map<String, List<String>> baseProductsByTask(String tenantId, List<String> taskIds) {
        Map<String, List<String>> result = new LinkedHashMap<>();
        if (taskIds.isEmpty()) {
            return result;
        }
        jdbc.sql("""
                        SELECT DISTINCT b.task_id, p.base_product_code
                        FROM import_batch b
                        JOIN product p ON p.tenant_id = b.tenant_id AND p.code = b.product_code
                        WHERE b.tenant_id = :tenantId AND b.task_id IN (:taskIds) AND b.status <> 'DELETED'
                        ORDER BY b.task_id, p.base_product_code
                        """)
                .param("tenantId", tenantId).param("taskIds", taskIds)
                .query((rs, rowNum) -> Map.entry(rs.getString("task_id"), rs.getString("base_product_code")))
                .list()
                .forEach(entry -> result.computeIfAbsent(entry.getKey(), key -> new ArrayList<>())
                        .add(entry.getValue()));
        return result;
    }

    /** Replaces the free text remark of one task; {@code null} clears it. */
    public void updateNote(String tenantId, String id, String note) {
        jdbc.sql("UPDATE import_task SET note = :note WHERE tenant_id = :tenantId AND id = :id")
                .param("tenantId", tenantId).param("id", id).param("note", note).update();
    }

    public void markRunning(String tenantId, String id, LocalDateTime startedAt) {
        jdbc.sql("UPDATE import_task SET status = 'RUNNING', started_at = :startedAt WHERE tenant_id = :tenantId AND id = :id")
                .param("tenantId", tenantId).param("id", id).param("startedAt", startedAt).update();
    }

    /**
     * Writes the reconciled status of a task an import appended to.
     *
     * <p>{@link #finish} cannot be used for that case because the counters are grown separately; only the
     * status, the finish time and (when given) the message are written here. A {@code null} message keeps
     * the previously recorded one.
     *
     * @param tenantId   tenant scope
     * @param id         task identifier
     * @param status     reconciled status
     * @param message    failure summary, {@code null} to keep the stored one
     * @param finishedAt finish time of the append
     */
    public void reconcileStatus(String tenantId, String id, String status, String message,
            LocalDateTime finishedAt) {
        jdbc.sql("""
                        UPDATE import_task SET status = :status, message = COALESCE(:message, message),
                            finished_at = :finishedAt
                        WHERE tenant_id = :tenantId AND id = :id
                        """)
                .param("tenantId", tenantId).param("id", id).param("status", status)
                .param("message", message).param("finishedAt", finishedAt).update();
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

    /**
     * Physically removes one task row.
     *
     * <p>Its batches and cases are deleted before by their own repositories; this is the last step of a
     * purge, after which nothing in the database points at the task any more.
     *
     * @param tenantId tenant scope
     * @param id       task identifier
     * @return number of removed rows
     */
    public int purge(String tenantId, String id) {
        return jdbc.sql("DELETE FROM import_task WHERE tenant_id = :tenantId AND id = :id")
                .param("tenantId", tenantId).param("id", id).update();
    }

    public Optional<ImportTaskRow> find(String tenantId, String id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM import_task WHERE tenant_id = :tenantId AND id = :id")
                .param("tenantId", tenantId).param("id", id)
                .query(this::map).optional();
    }

    /** Tasks by id, one query for a whole page of search results. */
    public List<ImportTaskRow> listByIds(String tenantId, List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("SELECT " + COLUMNS + " FROM import_task"
                        + " WHERE tenant_id = :tenantId AND id IN (:ids)")
                .param("tenantId", tenantId).param("ids", ids)
                .query(this::map).list();
    }

    /**
     * Task of the tenant with exactly this name, ignoring case.
     *
     * <p>Deleted tasks are returned as well, so callers can tell a free name from one that is only
     * occupied by a deleted task.
     */
    public Optional<ImportTaskRow> findByName(String tenantId, String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        return jdbc.sql("SELECT " + COLUMNS + " FROM import_task"
                        + " WHERE tenant_id = :tenantId AND LOWER(name) = LOWER(:name)")
                .param("tenantId", tenantId).param("name", name.trim())
                .query(this::map).stream().findFirst();
    }

    /** Adds the counters of one import run to an existing task, used when batches are joined. */
    public void addCounts(String tenantId, String id, int sheetDelta, int batchDelta, int rowDelta,
            int invalidDelta) {
        jdbc.sql("""
                        UPDATE import_task SET sheet_count = sheet_count + :sheets,
                            batch_count = batch_count + :batches,
                            total_rows = total_rows + :rows,
                            invalid_rows = invalid_rows + :invalid
                        WHERE tenant_id = :tenantId AND id = :id
                        """)
                .param("tenantId", tenantId).param("id", id)
                .param("sheets", sheetDelta).param("batches", batchDelta)
                .param("rows", rowDelta).param("invalid", invalidDelta)
                .update();
    }

    public List<ImportTaskRow> search(String tenantId, TaskFilter filter) {
        StringBuilder sql = new StringBuilder("SELECT " + COLUMNS + " FROM import_task t");
        Map<String, Object> params = new LinkedHashMap<>();
        appendFilter(sql, params, tenantId, filter, true);
        sql.append(orderBy(filter.sort()));
        sql.append(" LIMIT :limit OFFSET :offset");
        params.put("limit", filter.page().size());
        params.put("offset", filter.page().offset());
        return jdbc.sql(sql.toString()).params(params).query(this::map).list();
    }

    public long count(String tenantId, TaskFilter filter) {
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM import_task t");
        Map<String, Object> params = new LinkedHashMap<>();
        appendFilter(sql, params, tenantId, filter, true);
        return jdbc.sql(sql.toString()).params(params).query(Long.class).single();
    }

    /**
     * Status counts of the tasks matching the filter, without its status clause.
     *
     * <p>Counting the same predicate the list uses keeps every card reproducible: clicking it yields
     * exactly the number shown, also while a search, product or date filter is active.
     *
     * @param tenantId tenant scope
     * @param filter   filter whose status is ignored; {@code null} counts everything
     * @return one row per status with an {@code status} / {@code total} pair
     */
    public List<Map<String, Object>> statusCounts(String tenantId, TaskFilter filter) {
        StringBuilder sql = new StringBuilder("SELECT t.status, COUNT(*) AS total FROM import_task t");
        Map<String, Object> params = new LinkedHashMap<>();
        appendFilter(sql, params, tenantId, filter, false);
        sql.append(" GROUP BY t.status");
        List<Map<String, Object>> rows = new ArrayList<>();
        jdbc.sql(sql.toString()).params(params).query((rs, rowNum) -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("status", rs.getString("status"));
            item.put("total", rs.getInt("total"));
            rows.add(item);
            return item;
        }).list();
        return rows;
    }

    /**
     * Shared predicate of the list, its count and the status summary.
     *
     * @param sql        statement being built, must already select from {@code import_task t}
     * @param params     named parameters collected so far
     * @param tenantId   tenant scope
     * @param filter     filter values, {@code null} for none
     * @param withStatus whether the status clause of the filter applies; the summary drops it so it
     *                   can group by status
     */
    private static void appendFilter(StringBuilder sql, Map<String, Object> params, String tenantId,
            TaskFilter filter, boolean withStatus) {
        sql.append(" WHERE t.tenant_id = :tenantId");
        params.put("tenantId", tenantId);
        String kind = filter == null ? null : filter.kind();
        String status = filter == null ? null : filter.status();
        String product = filter == null ? null : filter.product();
        String query = filter == null ? null : filter.query();
        if (kind != null && !kind.isBlank()) {
            sql.append(" AND t.kind = :kind");
            params.put("kind", kind);
        }
        if (withStatus && status != null && !status.isBlank()) {
            sql.append(" AND t.status = :status");
            params.put("status", status);
        } else {
            sql.append(" AND t.status <> 'DELETED'");
        }
        if (product != null && !product.isBlank()) {
            sql.append(" AND " + BASE_PRODUCT_EXISTS);
            params.put("product", product.toUpperCase(java.util.Locale.ROOT));
        }
        if (query != null && !query.isBlank()) {
            sql.append(" AND (LOWER(t.source_name) LIKE :query OR LOWER(t.id) LIKE :query")
                    .append(" OR LOWER(t.name) LIKE :query")
                    .append(" OR EXISTS (SELECT 1 FROM import_batch b")
                    .append(" JOIN product p ON p.tenant_id = b.tenant_id AND p.code = b.product_code")
                    .append(" WHERE b.task_id = t.id AND LOWER(p.base_product_code) LIKE :query))");
            params.put("query", "%" + query.trim().toLowerCase(java.util.Locale.ROOT) + "%");
        }
        if (filter != null) {
            appendDateRange(sql, params, filter);
        }
    }

    /**
     * ORDER BY clause for the requested sort key.
     *
     * <p>Keys are {@code <field>} or {@code <field>_asc} / {@code <field>_desc}; without a direction
     * the newest task comes first, which is also the default order of the task panel.
     */
    private String orderBy(String sort) {
        String key = sort == null || sort.isBlank() ? "created" : sort.trim().toLowerCase(Locale.ROOT);
        boolean ascending = key.endsWith("_asc");
        String field = ascending ? key.substring(0, key.length() - 4)
                : key.endsWith("_desc") ? key.substring(0, key.length() - 5) : key;
        String direction = ascending ? " ASC" : " DESC";
        return switch (field) {
            case "name" -> " ORDER BY LOWER(t.name)" + direction + ", t.created_at DESC, t.id";
            case "batches" -> " ORDER BY t.batch_count" + direction + ", t.created_at DESC, t.id";
            case "rows" -> " ORDER BY t.total_rows" + direction + ", t.created_at DESC, t.id";
            case "invalid" -> " ORDER BY t.invalid_rows" + direction + ", t.created_at DESC, t.id";
            case "duration" -> " ORDER BY COALESCE(DATEDIFF('SECOND', t.started_at, t.finished_at), 0)"
                    + direction + ", t.created_at DESC, t.id";
            default -> " ORDER BY t.created_at" + direction + ", t.id";
        };
    }

    /**
     * Restricts the result to a creation date range; both bounds are inclusive and interpreted in
     * the display zone, so they line up with the timestamps shown in the table.
     */
    private static void appendDateRange(StringBuilder sql, Map<String, Object> params, TaskFilter filter) {
        if (filter.from() != null) {
            sql.append(" AND t.created_at >= :createdFrom");
            params.put("createdFrom", utcStartOfDay(filter.from()));
        }
        if (filter.to() != null) {
            sql.append(" AND t.created_at < :createdTo");
            params.put("createdTo", utcStartOfDay(filter.to().plusDays(1)));
        }
    }

    private static java.time.LocalDateTime utcStartOfDay(java.time.LocalDate date) {
        return date.atStartOfDay(ac.core.common.TimeProvider.DISPLAY_ZONE)
                .withZoneSameInstant(java.time.ZoneOffset.UTC).toLocalDateTime();
    }

    private ImportTaskRow map(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new ImportTaskRow(
                rs.getString("id"),
                rs.getString("tenant_id"),
                rs.getString("kind"),
                rs.getString("status"),
                rs.getString("name"),
                rs.getString("source_name"),
                rs.getString("source_sha256"),
                rs.getString("source_md5"),
                rs.getInt("sheet_count"),
                rs.getInt("batch_count"),
                rs.getInt("total_rows"),
                rs.getInt("invalid_rows"),
                rs.getString("params_json"),
                rs.getString("issues_json"),
                rs.getString("message"),
                rs.getString("note"),
                rs.getString("created_by"),
                rs.getObject("created_at", LocalDateTime.class),
                rs.getObject("started_at", LocalDateTime.class),
                rs.getObject("finished_at", LocalDateTime.class));
    }
}
