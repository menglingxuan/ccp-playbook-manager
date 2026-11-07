package ac.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** Upload, preview, import, browse, clone and export through the real HTTP API. */
class ImportFlowTest extends AbstractIntegrationTest {

    private static final String CSV_TEMPLATE = """
            _TestCaseId,_Remarks,Trade ID,Trade Date,Maturity Date,Notional,Notional Currency,Rate,Rate,Direction,Extra Ref
            TC-1,first,%s,2026-01-02,2031-01-02,"1,500,000.00",USD,0.0325,0.0425,BUY,ref-1
            TC-2,second,%s-B,2026-02-03,2031-02-03,2000000,EUR,0.025,0.035,SELL,ref-2
            """;

    @Test
    void importsClonesAndExports() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String tradeId = "IRS-" + suffix;
        String csv = CSV_TEMPLATE.formatted(tradeId, tradeId);
        String productCode = productWithFields("MW", "IRS", List.of("tradeId", "tradeDate", "maturityDate",
                "notional", "notionalCurrency", "fixedRate", "floatingRate", "direction"));

        var upload = api.upload("/api/uploads", "irs_" + suffix + ".csv",
                csv.getBytes(StandardCharsets.UTF_8));
        assertThat(upload.ok()).as(upload.body()).isTrue();
        JsonNode uploadJson = upload.json();
        String uploadId = uploadJson.get("uploadId").asString();
        assertThat(uploadJson.get("sheets")).hasSize(1);

        var preview = api.post("/api/uploads/" + uploadId + "/preview",
                api.toJson(Map.of("sheetIndex", 0, "productCode", productCode)));
        assertThat(preview.ok()).as(preview.body()).isTrue();
        JsonNode previewJson = preview.json().get("preview");
        assertThat(previewJson.get("valid").asBoolean()).isTrue();
        assertThat(previewJson.get("layout").get("headerRow").asInt()).isEqualTo(1);
        assertThat(previewJson.get("layout").get("tradeStartColumn").asInt()).isEqualTo(3);
        assertThat(previewJson.get("stats").get("mappedTradeColumns").asInt()).isEqualTo(8);
        assertThat(previewJson.get("stats").get("managedColumns").asInt()).isEqualTo(3);
        JsonNode firstSample = previewJson.get("sampleRows").get(0);
        assertThat(firstSample.get("values").get("fixedRate").decimalValue().toPlainString()).isEqualTo("0.0325");
        assertThat(firstSample.get("values").get("floatingRate").decimalValue().toPlainString()).isEqualTo("0.0425");

        var importResponse = api.post("/api/imports", api.toJson(Map.of(
                "uploadId", uploadId,
                "force", false,
                "sheets", List.of(Map.of("sheetIndex", 0, "productCode", productCode, "headerRow", 1,
                        "tradeStartColumn", 3, "dataStartRow", 2)))));
        assertThat(importResponse.ok()).as(importResponse.body()).isTrue();
        JsonNode importJson = importResponse.json();
        assertThat(importJson.get("status").asString()).isEqualTo("SUCCEEDED");
        assertThat(importJson.get("totalRows").asInt()).isEqualTo(2);
        String taskId = importJson.get("taskId").asString();
        String batchId = importJson.get("batches").get(0).get("batchId").asString();

        var task = api.get("/api/tasks/" + taskId);
        assertThat(task.ok()).isTrue();
        assertThat(task.json().get("task").get("sourceName").asString()).startsWith("irs_");

        var cases = api.get("/api/batches/" + batchId + "/cases?page=1&size=50");
        assertThat(cases.ok()).isTrue();
        JsonNode caseItems = cases.json().get("items");
        assertThat(caseItems).hasSize(2);
        JsonNode firstCase = caseItems.get(0);
        assertThat(firstCase.get("testCaseId").asString()).isEqualTo("TC-1");
        assertThat(firstCase.get("keyValue").asString()).isEqualTo(tradeId);
        String caseId = firstCase.get("id").asString();

        var vertical = api.get("/api/cases/" + caseId + "/vertical");
        assertThat(vertical.ok()).isTrue();
        JsonNode sections = vertical.json().get("sections");
        JsonNode tradeSection = null;
        for (JsonNode section : sections) {
            if ("trade".equals(section.get("code").asString())) {
                tradeSection = section;
            }
        }
        assertThat(tradeSection).isNotNull();
        assertThat(tradeSection.get("rows")).hasSize(8);

        var clonePreview = api.post("/api/clones/preview", api.toJson(Map.of(
                "caseIds", List.of(caseId), "copies", 2, "template", "{base}-C{seq:02}", "note", "it")));
        assertThat(clonePreview.ok()).as(clonePreview.body()).isTrue();
        assertThat(clonePreview.json().get("totalRows").asInt()).isEqualTo(2);

        var clone = api.post("/api/clones", api.toJson(Map.of(
                "caseIds", List.of(caseId), "copies", 2, "template", "{base}-C{seq:02}", "note", "it")));
        assertThat(clone.ok()).as(clone.body()).isTrue();
        assertThat(clone.json().get("created").asInt()).isEqualTo(2);

        Map<String, Object> exportRequest = Map.of(
                "batchId", batchId,
                "caseIds", List.of(),
                "options", Map.of("delimiter", ";", "quoteAll", true, "includeHeader", true,
                        "lineEnding", "\r\n", "bom", false, "managedColumns", "ALL",
                        "formulaAsValue", true));
        var precheck = api.post("/api/exports/precheck", api.toJson(exportRequest));
        assertThat(precheck.ok()).as(precheck.body()).isTrue();
        assertThat(precheck.json().get("valid").asBoolean()).isTrue();
        JsonNode headers = precheck.json().get("headers");
        assertThat(headers).hasSize(11);
        assertThat(headers.get(7).asString()).isEqualTo("Rate");
        assertThat(headers.get(8).asString()).isEqualTo("Rate");

        var export = api.post("/api/exports", api.toJson(exportRequest));
        assertThat(export.ok()).as(export.body()).isTrue();
        JsonNode exportJson = export.json();
        assertThat(exportJson.get("rowCount").asInt()).isEqualTo(2);

        String csvText = new String(api.getBytes("/api/exports/" + exportJson.get("exportId").asString()
                + "/download"), StandardCharsets.UTF_8);
        assertThat(csvText).startsWith("\"_TestCaseId\";");
        assertThat(csvText).contains("1500000.00");
        assertThat(csvText.split("\r\n")).hasSize(3);
    }

    @Test
    void invalidParamsReturnProblemDetail() {
        var response = api.post("/api/uploads/00000000-0000-0000-0000-000000000000/preview",
                api.toJson(Map.of("sheetIndex", 0)));
        assertThat(response.status()).isEqualTo(404);
        assertThat(response.json().get("code").asString()).isEqualTo("NOT_FOUND");
    }

    @Test
    void deletingTaskCascades() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String csv = CSV_TEMPLATE.formatted("DEL-" + suffix, "DEL-" + suffix);
        String productCode = productWithFields("MW", "IRS", List.of("tradeId", "tradeDate", "maturityDate",
                "notional", "notionalCurrency", "fixedRate", "floatingRate", "direction"));
        var upload = api.upload("/api/uploads", "del_" + suffix + ".csv", csv.getBytes(StandardCharsets.UTF_8));
        String uploadId = upload.json().get("uploadId").asString();
        var importResponse = api.post("/api/imports", api.toJson(Map.of(
                "uploadId", uploadId,
                "force", false,
                "sheets", List.of(Map.of("sheetIndex", 0, "productCode", productCode)))));
        assertThat(importResponse.ok()).as(importResponse.body()).isTrue();
        String batchId = importResponse.json().get("batches").get(0).get("batchId").asString();
        String taskId = importResponse.json().get("taskId").asString();

        var delete = api.delete("/api/tasks/" + taskId);
        assertThat(delete.ok()).as(delete.body()).isTrue();
        assertThat(api.get("/api/tasks/" + taskId).status()).isEqualTo(404);
        assertThat(api.get("/api/batches/" + batchId).status()).isEqualTo(404);
    }
}
