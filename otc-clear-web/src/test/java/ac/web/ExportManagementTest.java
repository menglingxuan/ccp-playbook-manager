package ac.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** Export management: exports are stored as records and downloaded on demand. */
class ExportManagementTest extends AbstractIntegrationTest {

    private static final String CSV_TEMPLATE = """
            _TestCaseId,_Remarks,Trade ID,Trade Date,Maturity Date,Notional,Notional Currency,Rate,Direction
            TC-1,first,%s,2026-01-02,2031-01-02,"1,500,000.00",USD,0.0325,BUY
            TC-2,second,%s-B,2026-02-03,2031-02-03,2000000,EUR,0.025,SELL
            """;

    private static final List<String> KEEP_FIELDS = List.of("testCaseId", "remarks", "tradeId", "tradeDate",
            "maturityDate", "notional", "notionalCurrency", "fixedRate", "direction");

    @Test
    void exportsAreStoredAsRecordsAndCanBeDownloadedLater() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String batchId = importBatch("export_" + suffix, "EXP-" + suffix);
        String exportId = createExport(batchId);

        JsonNode page = api.get("/api/exports?q=" + exportId).json();
        assertThat(page.get("total").asInt()).isEqualTo(1);
        JsonNode item = page.get("items").get(0);
        assertThat(item.get("id").asString()).isEqualTo(exportId);
        assertThat(item.get("batchId").asString()).isEqualTo(batchId);
        assertThat(item.get("rowCount").asInt()).isEqualTo(2);
        assertThat(item.get("fileExists").asBoolean()).isTrue();
        assertThat(item.get("downloadUrl").asString()).endsWith("/download");
        assertThat(item.get("options").get("delimiter").asString()).isEqualTo(",");
        assertThat(item.get("status").asString()).isEqualTo("COMPLETED");

        // the file is only transferred when the user asks for it
        String csv = new String(api.getBytes("/api/exports/" + exportId + "/download"), StandardCharsets.UTF_8);
        assertThat(csv).contains("_TestCaseId").contains("EXP-" + suffix);
        assertThat(csv.split("\r\n")).hasSize(3);

        // filters line up with the console
        String today = LocalDate.now(java.time.ZoneId.of("Asia/Shanghai")).toString();
        assertThat(api.get("/api/exports?q=" + exportId + "&from=" + today + "&to=" + today)
                .json().get("total").asInt()).isEqualTo(1);
        assertThat(api.get("/api/exports?batchId=" + batchId).json().get("total").asInt()).isEqualTo(1);
        assertThat(api.get("/api/exports?product=OIS&q=" + exportId).json().get("total").asInt()).isZero();
        assertThat(api.get("/api/exports?q=" + URLEncoder.encode("nothing-matches-" + suffix,
                StandardCharsets.UTF_8)).json().get("total").asInt()).isZero();
    }

    @Test
    void deletingAnExportRemovesTheRecordAndTheFile() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String batchId = importBatch("export_del_" + suffix, "EXPD-" + suffix);
        String exportId = createExport(batchId);

        assertThat(api.delete("/api/exports/" + exportId).ok()).isTrue();
        assertThat(api.get("/api/exports?q=" + exportId).json().get("total").asInt()).isZero();
        assertThat(api.get("/api/exports/" + exportId + "/download").status()).isEqualTo(404);
    }

    @Test
    void historySortsByRowsAndDate() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String batchId = importBatch("export_sort_" + suffix, "EXPS-" + suffix);
        String first = createExport(batchId);
        Thread.sleep(1100);
        String second = createExport(batchId);

        List<String> ascending = exportIds("/api/exports?q=" + batchId + "&sort=created_asc");
        assertThat(ascending).containsSubsequence(first, second);
        List<String> descending = exportIds("/api/exports?q=" + batchId + "&sort=created_desc");
        assertThat(descending).containsSubsequence(second, first);
        assertThat(exportIds("/api/exports?q=" + batchId + "&sort=rows_desc"))
                .containsSubsequence(second, first);
    }

    private List<String> exportIds(String path) {
        JsonNode page = api.get(path).json();
        List<String> ids = new ArrayList<>();
        page.get("items").forEach(item -> ids.add(item.get("id").asString()));
        return ids;
    }

    private String createExport(String batchId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("batchId", batchId);
        payload.put("caseIds", List.of());
        payload.put("recalculateFormulas", false);
        var response = api.post("/api/exports", api.toJson(payload));
        assertThat(response.ok()).as(response.body()).isTrue();
        return response.json().get("exportId").asString();
    }

    private String importBatch(String fileNamePrefix, String tradeId) {
        String productCode = productWithFields("MW", "IRS", KEEP_FIELDS);
        String csv = CSV_TEMPLATE.formatted(tradeId, tradeId);
        var upload = api.upload("/api/uploads", fileNamePrefix + ".csv", csv.getBytes(StandardCharsets.UTF_8));
        assertThat(upload.ok()).as(upload.body()).isTrue();
        var imported = api.post("/api/imports", api.toJson(Map.of(
                "uploadId", upload.json().get("uploadId").asString(), "force", false,
                "sheets", List.of(Map.of("sheetIndex", 0, "productCode", productCode)))));
        assertThat(imported.ok()).as(imported.body()).isTrue();
        return imported.json().get("batches").get(0).get("batchId").asString();
    }
}
