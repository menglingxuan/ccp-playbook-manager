package ac.web;

import static org.assertj.core.api.Assertions.assertThat;

import ac.core.service.DataLayout;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Deleting a task or a case must not leave rows, references or files behind.
 *
 * <p>Both are hard deletes: the shared in-memory database is inspected table by table and the data
 * directory is checked for the workbook baseline, the original upload and the archived export.
 */
class PurgeCleanupTest extends AbstractIntegrationTest {

    /** Same database the application under test uses, see {@link AbstractIntegrationTest}. */
    private static final String JDBC_URL = "jdbc:h2:mem:ottest;DB_CLOSE_DELAY=-1";
    private static final String TENANT = "default";

    @Autowired
    private DataLayout dataLayout;

    private static String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    // ------------------------------------------------------------------ sql helpers

    private static Connection connect() throws SQLException {
        return DriverManager.getConnection(JDBC_URL, "sa", "");
    }

    private static String placeholders(int count) {
        return String.join(", ", Collections.nCopies(count, "?"));
    }

    private long count(String sql, Object... params) {
        List<Object> results = query(sql, params);
        return results.isEmpty() ? 0 : ((Number) results.get(0)).longValue();
    }

    private List<Object> query(String sql, Object... params) {
        try (Connection connection = connect(); PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, params);
            List<Object> values = new ArrayList<>();
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    values.add(rows.getObject(1));
                }
            }
            return values;
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private void update(String sql, Object... params) {
        try (Connection connection = connect(); PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, params);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void bind(PreparedStatement statement, Object... params) throws SQLException {
        for (int index = 0; index < params.length; index++) {
            Object value = params[index];
            if (value == null) {
                statement.setNull(index + 1, java.sql.Types.VARCHAR);
            } else if (value instanceof LocalDateTime moment) {
                statement.setTimestamp(index + 1, java.sql.Timestamp.valueOf(moment));
            } else {
                statement.setObject(index + 1, value);
            }
        }
    }

    /** Varargs helper: the tenant id followed by a list of ids. */
    private static Object[] concat(String tenantId, List<String> ids) {
        Object[] params = new Object[ids.size() + 1];
        params[0] = tenantId;
        for (int index = 0; index < ids.size(); index++) {
            params[index + 1] = ids.get(index);
        }
        return params;
    }

    // ------------------------------------------------------------------ fixtures

    private static byte[] workbook(String tradePrefix) {
        try (Workbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("IRS");
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("Trade ID");
            header.createCell(1).setCellValue("Trade Date");
            for (int index = 0; index < 2; index++) {
                Row row = sheet.createRow(index + 1);
                row.createCell(0).setCellValue(tradePrefix + "-" + (index + 1));
                row.createCell(1).setCellValue("2026-01-02");
            }
            workbook.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Imports a small workbook into its own task; returns the upload, task and batch ids. */
    private Map<String, String> importTask(String taskName, byte[] content, String productCode) {
        var upload = api.upload("/api/uploads", "purge_" + suffix() + ".xlsx", content);
        assertThat(upload.ok()).as(upload.body()).isTrue();
        String uploadId = upload.json().get("uploadId").asString();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("uploadId", uploadId);
        body.put("force", false);
        body.put("sheets", List.of(Map.of("sheetIndex", 0, "productCode", productCode)));
        if (taskName != null) {
            body.put("taskName", taskName);
        }
        var imported = api.post("/api/imports", api.toJson(body));
        assertThat(imported.ok()).as(imported.body()).isTrue();
        assertThat(imported.json().get("status").asString()).isEqualTo("SUCCEEDED");
        Map<String, String> ids = new LinkedHashMap<>();
        ids.put("uploadId", uploadId);
        ids.put("taskId", imported.json().get("taskId").asString());
        ids.put("batchId", imported.json().get("batches").get(0).get("batchId").asString());
        return ids;
    }

    private List<String> batchIdsOfTask(String taskId) {
        return query("SELECT id FROM import_batch WHERE tenant_id = ? AND task_id = ?", TENANT, taskId).stream()
                .map(String::valueOf).toList();
    }

    private List<String> caseIdsOfBatch(String batchId) {
        return query("SELECT id FROM test_case WHERE tenant_id = ? AND batch_id = ?", TENANT, batchId).stream()
                .map(String::valueOf).toList();
    }

    private boolean uploadFileExists(String uploadId) throws IOException {
        Object createdAt = query("SELECT created_at FROM import_batch WHERE tenant_id = ? AND upload_id = ?",
                TENANT, uploadId).stream().findFirst().orElse(null);
        LocalDate date = createdAt instanceof java.sql.Timestamp stamp
                ? stamp.toInstant().atZone(ZoneOffset.UTC)
                        .withZoneSameInstant(ac.core.common.TimeProvider.DISPLAY_ZONE).toLocalDate()
                : LocalDate.now(ac.core.common.TimeProvider.DISPLAY_ZONE);
        Path folder = dataLayout.uploadsDir(TENANT, date);
        if (!Files.isDirectory(folder)) {
            return false;
        }
        try (Stream<Path> files = Files.list(folder)) {
            return files.anyMatch(path -> path.getFileName().toString().startsWith(uploadId + "."));
        }
    }

    // ------------------------------------------------------------------ tests

    @Test
    void deletingATaskRemovesItsRowsReferencesAndFiles() throws IOException {
        String productCode = productWithFields("MW", "IRS", List.of("tradeId", "tradeDate"));
        Map<String, String> imported = importTask(null, workbook("PURGE-" + suffix()), productCode);
        String taskId = imported.get("taskId");
        String batchId = imported.get("batchId");
        String uploadId = imported.get("uploadId");
        List<String> caseIds = caseIdsOfBatch(batchId);
        assertThat(caseIds).hasSize(2);

        // a clone inside the same task and an export of its batch
        var clone = api.post("/api/clones", api.toJson(Map.of("caseIds", caseIds, "copies", 1)));
        assertThat(clone.ok()).as(clone.body()).isTrue();
        List<String> batchIds = batchIdsOfTask(taskId);
        assertThat(batchIds).hasSize(2);
        var exported = api.post("/api/exports", api.toJson(Map.of("batchId", batchId)));
        assertThat(exported.ok()).as(exported.body()).isTrue();
        String exportId = exported.json().get("exportId").asString();
        String exportPath = String.valueOf(query(
                "SELECT storage_path FROM export_job WHERE tenant_id = ? AND id = ?", TENANT, exportId).get(0));
        Path exportFile = dataLayout.resolveInside(dataLayout.tenantRoot(TENANT), exportPath);
        assertThat(Files.isRegularFile(exportFile)).isTrue();

        // leftovers of other modules that point at the task data
        // job ids are 36 characters wide, so the task id doubles as a legacy dispatch job id
        String legacyJob = taskId;
        update("INSERT INTO external_job_case (tenant_id, job_id, case_id, row_no) VALUES (?, ?, ?, 1)",
                TENANT, legacyJob, caseIds.get(0));
        String outputId = UUID.randomUUID().toString();
        update("""
                INSERT INTO external_output (id, tenant_id, job_id, file_name, relative_path, size_bytes,
                    sha256, imported_batch_id, status, discovered_at)
                VALUES (?, ?, ?, 'out.csv', 'jobs/out.csv', 10, ?, ?, 'IMPORTED', ?)
                """, outputId, TENANT, legacyJob, "0".repeat(64), batchId, LocalDateTime.now());

        Path baselineFolder = dataLayout.batchFolder(TENANT, batchId);
        assertThat(Files.isDirectory(baselineFolder)).isTrue();
        assertThat(uploadFileExists(uploadId)).isTrue();

        var deleted = api.delete("/api/tasks/" + taskId);
        assertThat(deleted.ok()).as(deleted.body()).isTrue();

        // no row of any involved table survives, whether or not it was already marked deleted
        assertThat(count("SELECT COUNT(*) FROM import_task WHERE tenant_id = ? AND id = ?", TENANT, taskId))
                .isZero();
        assertThat(count("SELECT COUNT(*) FROM import_batch WHERE tenant_id = ? AND task_id = ?", TENANT, taskId))
                .isZero();
        assertThat(count("SELECT COUNT(*) FROM test_case WHERE tenant_id = ? AND batch_id IN ("
                + placeholders(batchIds.size()) + ")", concat(TENANT, batchIds))).isZero();
        assertThat(count("SELECT COUNT(*) FROM external_job_case WHERE tenant_id = ? AND case_id IN ("
                + placeholders(caseIds.size()) + ")", concat(TENANT, caseIds))).isZero();
        // the export history of the batch is gone, with its archived file
        assertThat(count("SELECT COUNT(*) FROM export_job WHERE tenant_id = ? AND batch_id IN ("
                + placeholders(batchIds.size()) + ")", concat(TENANT, batchIds))).isZero();
        assertThat(Files.exists(exportFile)).isFalse();
        // an external output keeps its record but no longer points at a removed batch
        assertThat(count("SELECT COUNT(*) FROM external_output WHERE tenant_id = ? AND id = ?"
                + " AND imported_batch_id IS NULL", TENANT, outputId)).isEqualTo(1);

        // files: the baseline folder and the upload of this task are gone
        assertThat(Files.exists(baselineFolder)).isFalse();
        assertThat(uploadFileExists(uploadId)).isFalse();

        // the API no longer knows the task or its batches
        assertThat(api.get("/api/tasks/" + taskId).status()).isEqualTo(404);
        assertThat(api.get("/api/batches/" + batchId).status()).isEqualTo(404);
    }

    @Test
    void uploadSharedWithAnotherTaskIsKept() throws IOException {
        String productCode = productWithFields("MW", "IRS", List.of("tradeId", "tradeDate"));
        Map<String, String> first = importTask(null, workbook("SHARE-" + suffix()), productCode);
        String uploadId = first.get("uploadId");

        // the same upload is imported into a second task, so its file has two owners
        var second = api.post("/api/imports", api.toJson(Map.of(
                "uploadId", uploadId, "force", false, "taskName", "共享上传 " + suffix(),
                "sheets", List.of(Map.of("sheetIndex", 0, "productCode", productCode)))));
        assertThat(second.ok()).as(second.body()).isTrue();
        String secondTaskId = second.json().get("taskId").asString();
        assertThat(uploadFileExists(uploadId)).isTrue();

        assertThat(api.delete("/api/tasks/" + first.get("taskId")).ok()).isTrue();
        // the second task still owns the upload, so its file must survive
        assertThat(uploadFileExists(uploadId)).isTrue();

        assertThat(api.delete("/api/tasks/" + secondTaskId).ok()).isTrue();
        assertThat(uploadFileExists(uploadId)).isFalse();
    }

    @Test
    void deletingCasesRemovesTheirRowsAndReferences() {
        String productCode = productWithFields("MW", "IRS", List.of("tradeId", "tradeDate"));
        Map<String, String> imported = importTask(null, workbook("CASE-" + suffix()), productCode);
        String batchId = imported.get("batchId");
        List<String> caseIds = caseIdsOfBatch(batchId);
        assertThat(caseIds).hasSize(2);
        String kept = caseIds.get(0);
        String removed = caseIds.get(1);

        // a clone of the removed case and a legacy dispatch link keep pointing at it
        var clone = api.post("/api/clones", api.toJson(Map.of("caseIds", List.of(removed), "copies", 1)));
        assertThat(clone.ok()).as(clone.body()).isTrue();
        String clonedCaseId = String.valueOf(query(
                "SELECT id FROM test_case WHERE tenant_id = ? AND cloned_from = ?", TENANT, removed).get(0));
        update("INSERT INTO external_job_case (tenant_id, job_id, case_id, row_no) VALUES (?, ?, ?, 1)",
                TENANT, batchId, removed);

        assertThat(api.delete("/api/cases/" + removed).ok()).isTrue();

        assertThat(count("SELECT COUNT(*) FROM test_case WHERE tenant_id = ? AND id = ?", TENANT, removed))
                .isZero();
        assertThat(count("SELECT COUNT(*) FROM external_job_case WHERE tenant_id = ? AND case_id = ?",
                TENANT, removed)).isZero();
        // the clone survives, but no longer names a case that is gone
        assertThat(count("SELECT COUNT(*) FROM test_case WHERE tenant_id = ? AND id = ? AND cloned_from IS NULL",
                TENANT, clonedCaseId)).isEqualTo(1);
        // the untouched case is still there
        assertThat(count("SELECT COUNT(*) FROM test_case WHERE tenant_id = ? AND id = ?", TENANT, kept))
                .isEqualTo(1);

        // a bulk delete clears the rest of the batch as well
        List<String> rest = caseIdsOfBatch(batchId);
        var deleted = api.post("/api/cases/bulk-delete", api.toJson(Map.of("ids", rest)));
        assertThat(deleted.ok()).as(deleted.body()).isTrue();
        assertThat(caseIdsOfBatch(batchId)).isEmpty();
        assertThat(api.delete("/api/tasks/" + imported.get("taskId")).ok()).isTrue();
    }
}
