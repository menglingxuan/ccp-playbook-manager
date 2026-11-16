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
    void caseSelectionExportsKeepTheSourceBatchForLinking() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String batchId = importBatch("export_sel_" + suffix, "EXPSEL-" + suffix);
        List<String> caseIds = new ArrayList<>();
        api.get("/api/batches/" + batchId + "/cases?size=50").json().get("items")
                .forEach(item -> caseIds.add(item.get("id").asString()));
        assertThat(caseIds).hasSize(2);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("caseIds", List.of(caseIds.get(0)));
        payload.put("recalculateFormulas", false);
        var response = api.post("/api/exports", api.toJson(payload));
        assertThat(response.ok()).as(response.body()).isTrue();
        String exportId = response.json().get("exportId").asString();

        JsonNode item = api.get("/api/exports?q=" + exportId).json().get("items").get(0);
        // the scope stays a selection, but the batch it came from is kept so the list can link back
        assertThat(item.get("scopeType").asString()).isEqualTo("SELECTION");
        assertThat(item.get("batchId").asString()).isEqualTo(batchId);
        assertThat(item.get("rowCount").asInt()).isEqualTo(1);
    }

    @Test
    void previewExposesTheStoredRowsWithoutDownloading() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String batchId = importBatch("export_preview_" + suffix, "EXPV-" + suffix);
        String exportId = createExport(batchId);

        JsonNode preview = api.get("/api/exports/" + exportId + "/preview").json();
        assertThat(preview.get("fileName").asString()).endsWith(".csv");
        assertThat(preview.get("rowCount").asInt()).isEqualTo(2);
        assertThat(preview.get("shownRows").asInt()).isEqualTo(2);
        assertThat(preview.get("truncated").asBoolean()).isFalse();
        List<String> headers = new ArrayList<>();
        preview.get("headers").forEach(node -> headers.add(node.asString()));
        assertThat(headers).contains("_TestCaseId", "Trade ID", "Notional");
        assertThat(preview.get("rows")).hasSize(2);
        int tradeIdColumn = headers.indexOf("Trade ID");
        assertThat(preview.get("rows").get(0).get(tradeIdColumn).asString()).isEqualTo("EXPV-" + suffix);
        // quoted values with a delimiter survive the round trip as the original cell text
        int notionalColumn = headers.indexOf("Notional");
        assertThat(preview.get("rows").get(0).get(notionalColumn).asString()).isEqualTo("1,500,000.00");

        // the limit truncates instead of failing
        JsonNode limited = api.get("/api/exports/" + exportId + "/preview?limit=1").json();
        assertThat(limited.get("shownRows").asInt()).isEqualTo(1);
        assertThat(limited.get("truncated").asBoolean()).isTrue();
        assertThat(limited.get("rows")).hasSize(1);

        assertThat(api.get("/api/exports/no-such-export/preview").status()).isEqualTo(404);
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

    @Test
    void partialOptionsFallBackToTheStoredDefaults() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String batchId = importBatch("export_opts_" + suffix, "EXPOPT-" + suffix);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("batchId", batchId);
        // only one option is sent: everything else has to come from the stored defaults
        payload.put("options", Map.of("lineEnding", "LF"));
        var response = api.post("/api/exports", api.toJson(payload));
        assertThat(response.ok()).as(response.body()).isTrue();
        String exportId = response.json().get("exportId").asString();

        JsonNode stored = api.get("/api/exports?q=" + exportId).json().get("items").get(0).get("options");
        assertThat(stored.get("delimiter").asString()).isEqualTo(",");
        assertThat(stored.get("managedColumns").asString()).isEqualTo("KNOWN");
        assertThat(stored.get("lineEnding").asString()).isEqualTo("LF");
        assertThat(stored.get("bom").asBoolean()).isFalse();

        String csv = new String(api.getBytes("/api/exports/" + exportId + "/download"),
                StandardCharsets.UTF_8);
        assertThat(csv).startsWith("Trade ID,").contains("EXPOPT-" + suffix);
        assertThat(csv.split("\n")).hasSize(3);
    }

    @Test
    void unusableLineEndingIsRejected() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String batchId = importBatch("export_badopts_" + suffix, "EXPBAD-" + suffix);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("batchId", batchId);
        payload.put("options", Map.of("lineEnding", "\r\n===injected===\r\n"));
        var response = api.post("/api/exports", api.toJson(payload));

        assertThat(response.status()).isEqualTo(400);
        assertThat(response.json().get("code").asString()).isEqualTo("INVALID_REQUEST");
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
