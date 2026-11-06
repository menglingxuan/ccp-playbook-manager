package ac.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Case editing: validation, optimistic locking and bulk delete. */
class CaseEditTest extends AbstractIntegrationTest {

    private static final String CSV = """
            Trade ID,Trade Date,Maturity Date,Notional,Notional Currency,Rate,Rate,Direction
            %s,2026-01-02,2031-01-02,1000000,USD,0.03,0.04,BUY
            %s-B,2026-01-03,2031-01-03,2000000,USD,0.05,0.06,SELL
            """;

    @Test
    void editsWithOptimisticLockAndValidatesValues() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String tradeId = "EDIT-" + suffix;
        String batchId = importCsv(tradeId);
        var cases = api.get("/api/batches/" + batchId + "/cases?page=1&size=10");
        String caseId = cases.json().get("items").get(0).get("id").asString();
        int version = cases.json().get("items").get(0).get("version").asInt();

        var invalid = api.patch("/api/cases/" + caseId,
                api.toJson(Map.of("version", version, "values", Map.of("notional", "not-a-number"))));
        assertThat(invalid.status()).isEqualTo(400);

        var updated = api.patch("/api/cases/" + caseId, api.toJson(Map.of(
                "version", version,
                "values", Map.of("notional", "1234567.89", "tradeState", "CLEARED"),
                "managed", Map.of("_Remarks", "edited"))));
        assertThat(updated.ok()).as(updated.body()).isTrue();
        assertThat(updated.json().get("version").asInt()).isEqualTo(version + 1);
        assertThat(updated.json().get("data").get("notional").decimalValue().toPlainString())
                .isEqualTo("1234567.89");

        var stale = api.patch("/api/cases/" + caseId, api.toJson(Map.of(
                "version", version, "values", Map.of("notional", "1"))));
        assertThat(stale.status()).isEqualTo(409);
        assertThat(stale.json().get("code").asString()).isEqualTo("CONFLICT");

        var bulk = api.post("/api/cases/bulk-delete", api.toJson(Map.of("ids", List.of(caseId))));
        assertThat(bulk.ok()).as(bulk.body()).isTrue();
        assertThat(bulk.json().get("deleted").asInt()).isEqualTo(1);
        assertThat(api.get("/api/cases/" + caseId).status()).isEqualTo(404);
    }

    private String importCsv(String tradeId) {
        var upload = api.upload("/api/uploads", "edit_" + tradeId + ".csv",
                CSV.formatted(tradeId, tradeId).getBytes(StandardCharsets.UTF_8));
        String uploadId = upload.json().get("uploadId").asString();
        var importResponse = api.post("/api/imports", api.toJson(Map.of(
                "uploadId", uploadId, "force", false,
                "sheets", List.of(Map.of("sheetIndex", 0, "productCode", "IRS")))));
        assertThat(importResponse.ok()).as(importResponse.body()).isTrue();
        return importResponse.json().get("batches").get(0).get("batchId").asString();
    }
}
