package ac.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** Free text remarks on the task, batch and export lists: set, edit, clear and reject over long text. */
class ListNoteTest extends AbstractIntegrationTest {

    private static final String CSV = """
            _TestCaseId,_Remarks,Trade ID,Trade Date,Maturity Date,Notional,Notional Currency,Rate,Direction
            TC-1,first,%s,2026-01-02,2031-01-02,1000000,USD,0.0325,BUY
            """;

    @Test
    void tasksKeepMultiLineRemarks() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        Fixture fixture = importWithOneBatch(suffix);
        String note = "第一行备注\n第二行备注\n第三行";

        var updated = api.patch("/api/tasks/" + fixture.taskId() + "/note", api.toJson(Map.of("note", note)));
        assertThat(updated.ok()).as(updated.body()).isTrue();
        assertThat(updated.json().get("note").asString()).isEqualTo(note);

        JsonNode item = api.get("/api/tasks?q=" + fixture.taskId()).json().get("items").get(0);
        assertThat(item.get("note").asString()).isEqualTo(note);

        // an empty text clears the remark again
        api.patch("/api/tasks/" + fixture.taskId() + "/note", api.toJson(Map.of("note", "   ")));
        assertThat(api.get("/api/tasks?q=" + fixture.taskId()).json().get("items").get(0).get("note").isNull())
                .isTrue();

        var tooLong = api.patch("/api/tasks/" + fixture.taskId() + "/note",
                api.toJson(Map.of("note", "x".repeat(2001))));
        assertThat(tooLong.status()).isEqualTo(400);

        assertThat(api.patch("/api/tasks/00000000-0000-0000-0000-000000000000/note",
                api.toJson(Map.of("note", "x"))).status()).isEqualTo(404);
    }

    @Test
    void batchesAndExportsKeepTheirRemark() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        Fixture fixture = importWithOneBatch(suffix);

        String batchNote = "批次备注\n换行";
        assertThat(api.patch("/api/batches/" + fixture.batchId(), api.toJson(Map.of("note", batchNote))).ok())
                .isTrue();
        JsonNode batch = api.get("/api/tasks/" + fixture.taskId()).json().get("batches").get(0);
        assertThat(batch.get("note").asString()).isEqualTo(batchNote);

        String exportId = export(fixture.batchId());
        String exportNote = "导出备注\n第二行";
        var updated = api.patch("/api/exports/" + exportId + "/note", api.toJson(Map.of("note", exportNote)));
        assertThat(updated.ok()).as(updated.body()).isTrue();
        JsonNode export = api.get("/api/exports?q=" + exportId).json().get("items").get(0);
        assertThat(export.get("note").asString()).isEqualTo(exportNote);

        api.patch("/api/exports/" + exportId + "/note", api.toJson(Map.of("note", "")));
        assertThat(api.get("/api/exports?q=" + exportId).json().get("items").get(0).get("note").isNull()).isTrue();
    }

    private record Fixture(String taskId, String batchId) {
    }

    private Fixture importWithOneBatch(String suffix) {
        String productCode = productWithFields("MW", "IRS", List.of("testCaseId", "remarks", "tradeId",
                "tradeDate", "maturityDate", "notional", "notionalCurrency", "fixedRate", "direction"));
        var upload = api.upload("/api/uploads", "note_" + suffix + ".csv",
                CSV.formatted("NOTE-" + suffix).getBytes(StandardCharsets.UTF_8));
        String uploadId = upload.json().get("uploadId").asString();
        var imported = api.post("/api/imports", api.toJson(Map.of(
                "uploadId", uploadId, "force", false,
                "sheets", List.of(Map.of("sheetIndex", 0, "productCode", productCode)))));
        assertThat(imported.ok()).as(imported.body()).isTrue();
        return new Fixture(imported.json().get("taskId").asString(),
                imported.json().get("batches").get(0).get("batchId").asString());
    }

    private String export(String batchId) {
        var response = api.post("/api/exports", api.toJson(Map.of(
                "batchId", batchId, "caseIds", List.of(),
                "options", Map.of("delimiter", ",", "quoteAll", false, "includeHeader", true,
                        "lineEnding", "\r\n", "bom", false, "managedColumns", "NONE", "formulaAsValue", true))));
        assertThat(response.ok()).as(response.body()).isTrue();
        return response.json().get("exportId").asString();
    }
}
