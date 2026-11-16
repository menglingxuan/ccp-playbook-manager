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
            Trade ID,Trade Date,Maturity Date,Notional,Notional Currency,Rate,Direction,Trade State
            %s,2026-01-02,2031-01-02,1000000,USD,0.03,BUY,PENDING
            %s-B,2026-01-03,2031-01-03,2000000,USD,0.05,SELL,PENDING
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

    @Test
    void editsFormulasInAdvancedMode() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String tradeId = "ADV-" + suffix;
        String batchId = importCsv(tradeId);
        var cases = api.get("/api/batches/" + batchId + "/cases?page=1&size=10");
        String caseId = cases.json().get("items").get(0).get("id").asString();
        int version = cases.json().get("items").get(0).get("version").asInt();
        assertThat(cases.json().get("items").get(0).get("formulaCount").asInt()).isZero();

        // set a formula: the static value stays, the formula is stored
        var withFormula = api.patch("/api/cases/" + caseId, api.toJson(Map.of(
                "version", version, "formulas", Map.of("fixedRate", "=D2/500000"))));
        assertThat(withFormula.ok()).as(withFormula.body()).isTrue();
        assertThat(withFormula.json().get("formulas").get("fixedRate").asString()).isEqualTo("=D2/500000");
        assertThat(withFormula.json().get("data").get("fixedRate").decimalValue().toPlainString())
                .isEqualTo("0.03");
        version = withFormula.json().get("version").asInt();

        // a plain value replaces the formula so the next export does not recalculate it
        var withValue = api.patch("/api/cases/" + caseId, api.toJson(Map.of(
                "version", version,
                "values", Map.of("fixedRate", "0.5"),
                "formulas", Map.of("fixedRate", ""))));
        assertThat(withValue.ok()).as(withValue.body()).isTrue();
        assertThat(withValue.json().get("formulas").has("fixedRate")).isFalse();
        assertThat(withValue.json().get("data").get("fixedRate").decimalValue().toPlainString())
                .isEqualTo("0.5");
        version = withValue.json().get("version").asInt();

        var managedFormula = api.patch("/api/cases/" + caseId, api.toJson(Map.of(
                "version", version, "formulas", Map.of("_Remarks", "=A1"))));
        assertThat(managedFormula.status()).isEqualTo(400);

        var keyFormula = api.patch("/api/cases/" + caseId, api.toJson(Map.of(
                "version", version, "formulas", Map.of("tradeId", "=A1"))));
        assertThat(keyFormula.status()).isEqualTo(409);

        var invalidFormula = api.patch("/api/cases/" + caseId, api.toJson(Map.of(
                "version", version, "formulas", Map.of("fixedRate", "D2/2"))));
        assertThat(invalidFormula.status()).isEqualTo(400);

        var unknownField = api.patch("/api/cases/" + caseId, api.toJson(Map.of(
                "version", version, "formulas", Map.of("nope", "=A1"))));
        assertThat(unknownField.status()).isEqualTo(400);
    }

    private String importCsv(String tradeId) {
        String productCode = productWithFields("MW", "IRS", List.of("tradeId", "tradeDate", "maturityDate",
                "notional", "notionalCurrency", "fixedRate", "direction", "tradeState", "testCaseId", "remarks"));
        var upload = api.upload("/api/uploads", "edit_" + tradeId + ".csv",
                CSV.formatted(tradeId, tradeId).getBytes(StandardCharsets.UTF_8));
        String uploadId = upload.json().get("uploadId").asString();
        var importResponse = api.post("/api/imports", api.toJson(Map.of(
                "uploadId", uploadId, "force", false,
                "sheets", List.of(Map.of("sheetIndex", 0, "productCode", productCode)))));
        assertThat(importResponse.ok()).as(importResponse.body()).isTrue();
        return importResponse.json().get("batches").get(0).get("batchId").asString();
    }
}
