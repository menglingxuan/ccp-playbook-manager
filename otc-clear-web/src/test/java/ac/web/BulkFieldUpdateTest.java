package ac.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** Bulk field update of every case in a batch: value, formula and clearing. */
class BulkFieldUpdateTest extends AbstractIntegrationTest {

    private static final String CSV_TEMPLATE = """
            _TestCaseId,_Remarks,Trade ID,Trade Date,Maturity Date,Notional,Notional Currency,Rate,Direction
            TC-1,first,%s-A,2026-01-02,2031-01-02,1500000.00,USD,0.0325,BUY
            TC-2,second,%s-B,2026-02-03,2031-02-03,1600000.00,EUR,0.0250,SELL
            TC-3,third,%s-C,2026-03-04,2031-03-04,1700000.00,USD,0.0275,BUY
            """;

    private static final List<String> KEEP_FIELDS = List.of("testCaseId", "remarks", "tradeId", "tradeDate",
            "maturityDate", "notional", "notionalCurrency", "fixedRate", "direction");

    @Test
    void valueUpdateRewritesEveryCaseOfTheBatch() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String batchId = importBatch("bulk_value_" + suffix, suffix);
        List<String> caseIds = caseIds(batchId);
        assertThat(caseIds).hasSize(3);

        Map<String, Object> dryRun = body("notional", "VALUE", true);
        dryRun.put("value", "2000000");
        JsonNode preview = bulk(batchId, dryRun);
        assertThat(preview.get("matched").asInt()).isEqualTo(3);
        assertThat(preview.get("updated").asInt()).isZero();
        assertThat(preview.get("dryRun").asBoolean()).isTrue();
        assertThat(preview.get("samples")).isNotEmpty();
        // the dry run wrote nothing
        assertThat(caseDetail(caseIds.get(0)).get("data").get("notional").decimalValue())
                .isNotEqualByComparingTo("2000000");

        Map<String, Object> apply = body("notional", "VALUE", false);
        apply.put("value", "2000000");
        JsonNode applied = bulk(batchId, apply);
        assertThat(applied.get("matched").asInt()).isEqualTo(3);
        assertThat(applied.get("updated").asInt()).isEqualTo(3);
        assertThat(applied.get("issues")).isEmpty();

        for (String caseId : caseIds) {
            JsonNode detail = caseDetail(caseId);
            assertThat(detail.get("data").get("notional").decimalValue()).isEqualByComparingTo("2000000");
            assertThat(detail.get("version").asInt()).isPositive();
            // the derived search text follows the new value
            assertThat(api.get("/api/batches/" + batchId + "/cases?q=2000000&size=50")
                    .json().get("total").asInt()).isEqualTo(3);
        }
    }

    @Test
    void formulaUpdateKeepsTheStoredValueAndCountsTheFormula() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String batchId = importBatch("bulk_formula_" + suffix, suffix);
        List<String> caseIds = caseIds(batchId);

        Map<String, Object> payload = body("fixedRate", "FORMULA", false);
        payload.put("formula", "=Notional/1000000");
        JsonNode applied = bulk(batchId, payload);
        assertThat(applied.get("updated").asInt()).isEqualTo(3);

        JsonNode detail = caseDetail(caseIds.get(0));
        assertThat(detail.get("formulas").get("fixedRate").asString()).isEqualTo("=Notional/1000000");
        // the static value is kept until the next export recalculates it
        assertThat(detail.get("data").get("fixedRate").decimalValue()).isEqualByComparingTo("0.0325");
        assertThat(api.get("/api/batches/" + batchId + "/cases?hasFormula=true&size=50")
                .json().get("total").asInt()).isEqualTo(3);
    }

    @Test
    void clearingRemovesValueAndFormula() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String batchId = importBatch("bulk_clear_" + suffix, suffix);
        List<String> caseIds = caseIds(batchId);

        Map<String, Object> formulaPayload = body("fixedRate", "FORMULA", false);
        formulaPayload.put("formula", "=Notional/2");
        assertThat(bulk(batchId, formulaPayload).get("updated").asInt()).isEqualTo(3);

        JsonNode cleared = bulk(batchId, body("fixedRate", "CLEAR", false));
        assertThat(cleared.get("updated").asInt()).isEqualTo(3);
        JsonNode detail = caseDetail(caseIds.get(0));
        assertThat(detail.get("formulas").get("fixedRate")).isNull();
        assertThat(detail.get("managedFormulas").get("fixedRate")).isNull();
        assertThat(detail.get("data").get("fixedRate")).isNull();
    }

    @Test
    void selectionScopeOnlyTouchesTheChosenCases() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String batchId = importBatch("bulk_scope_" + suffix, suffix);
        List<String> caseIds = caseIds(batchId);

        Map<String, Object> payload = body("notionalCurrency", "VALUE", false);
        payload.put("value", "JPY");
        payload.put("caseIds", List.of(caseIds.get(1)));
        JsonNode applied = bulk(batchId, payload);
        assertThat(applied.get("matched").asInt()).isEqualTo(1);
        assertThat(applied.get("updated").asInt()).isEqualTo(1);

        assertThat(caseDetail(caseIds.get(1)).get("values").get("notionalCurrency").asString()).isEqualTo("JPY");
        assertThat(caseDetail(caseIds.get(0)).get("values").get("notionalCurrency").asString()).isEqualTo("USD");
    }

    @Test
    void filterScopeOnlyTouchesMatchingCases() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String batchId = importBatch("bulk_filter_" + suffix, suffix);

        Map<String, Object> payload = body("direction", "VALUE", false);
        payload.put("value", "SELL");
        payload.put("query", suffix + "-A");
        JsonNode applied = bulk(batchId, payload);
        assertThat(applied.get("matched").asInt()).isEqualTo(1);
        assertThat(applied.get("updated").asInt()).isEqualTo(1);
    }

    @Test
    void keyFieldCannotBeSetOnSeveralCases() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String batchId = importBatch("bulk_key_" + suffix, suffix);

        Map<String, Object> payload = body("tradeId", "VALUE", false);
        payload.put("value", "SAME-KEY");
        var response = api.post("/api/batches/" + batchId + "/cases/bulk-update", api.toJson(payload));
        assertThat(response.status()).isEqualTo(409);
        assertThat(response.body()).contains("duplicate keys");

        // a single case may still be renamed
        Map<String, Object> single = body("tradeId", "VALUE", false);
        single.put("value", "SINGLE-KEY");
        single.put("caseIds", List.of(caseIds(batchId).get(0)));
        assertThat(bulk(batchId, single).get("updated").asInt()).isEqualTo(1);
    }

    @Test
    void invalidRequestsAreRejected() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String batchId = importBatch("bulk_invalid_" + suffix, suffix);

        // read only identity column
        assertThat(api.post("/api/batches/" + batchId + "/cases/bulk-update",
                api.toJson(body("testCaseId", "VALUE", false))).status()).isEqualTo(400);
        // unknown field
        assertThat(api.post("/api/batches/" + batchId + "/cases/bulk-update",
                api.toJson(body("nope", "VALUE", false))).status()).isEqualTo(400);
        // formula without '='
        Map<String, Object> badFormula = body("fixedRate", "FORMULA", false);
        badFormula.put("formula", "Notional/2");
        assertThat(api.post("/api/batches/" + batchId + "/cases/bulk-update",
                api.toJson(badFormula)).status()).isEqualTo(400);
        // managed column cannot take a formula
        Map<String, Object> managedFormula = body("remarks", "FORMULA", false);
        managedFormula.put("formula", "=TODAY()");
        assertThat(api.post("/api/batches/" + batchId + "/cases/bulk-update",
                api.toJson(managedFormula)).status()).isEqualTo(400);
        // value that does not fit the field type
        Map<String, Object> badValue = body("notional", "VALUE", false);
        badValue.put("value", "not-a-number");
        assertThat(api.post("/api/batches/" + batchId + "/cases/bulk-update",
                api.toJson(badValue)).status()).isEqualTo(400);
    }

    @Test
    void metaSetTypeAndContentDriveTheBulkValue() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String batchId = importBatch("bulk_set_" + suffix, suffix);
        List<String> caseIds = caseIds(batchId);

        // a VALUE set whose item content supplies the new value
        String valueSet = "CUR_" + suffix.toUpperCase();
        Map<String, Object> set = new LinkedHashMap<>();
        set.put("code", valueSet);
        set.put("name", valueSet);
        set.put("type", "VALUE");
        var createdSet = api.post("/api/meta/sets", api.toJson(set));
        assertThat(createdSet.ok()).as(createdSet.body()).isTrue();
        assertThat(createdSet.json().get("type").asString()).isEqualTo("VALUE");

        Map<String, Object> yen = new LinkedHashMap<>();
        yen.put("code", "JPY");
        yen.put("label", "Japanese Yen");
        yen.put("content", "JPY");
        assertThat(api.post("/api/meta/sets/" + valueSet + "/items", api.toJson(yen)).ok()).isTrue();

        Map<String, Object> payload = body("notionalCurrency", "VALUE", false);
        payload.put("valueSet", valueSet);
        payload.put("valueItem", "JPY");
        assertThat(bulk(batchId, payload).get("updated").asInt()).isEqualTo(3);
        assertThat(caseDetail(caseIds.get(0)).get("values").get("notionalCurrency").asString()).isEqualTo("JPY");

        // a FORMULA set drives the formula of the same batch
        String formulaSet = "FML_" + suffix.toUpperCase();
        Map<String, Object> formulaSetBody = new LinkedHashMap<>();
        formulaSetBody.put("code", formulaSet);
        formulaSetBody.put("name", formulaSet);
        formulaSetBody.put("type", "FORMULA");
        assertThat(api.post("/api/meta/sets", api.toJson(formulaSetBody)).json().get("type").asString())
                .isEqualTo("FORMULA");

        Map<String, Object> half = new LinkedHashMap<>();
        half.put("code", "HALF");
        half.put("label", "一半");
        half.put("content", "=Notional/2");
        var createdItem = api.post("/api/meta/sets/" + formulaSet + "/items", api.toJson(half));
        assertThat(createdItem.ok()).as(createdItem.body()).isTrue();
        assertThat(createdItem.json().get("content").asString()).isEqualTo("=Notional/2");

        Map<String, Object> formulaPayload = body("fixedRate", "FORMULA", false);
        formulaPayload.put("valueSet", formulaSet);
        formulaPayload.put("valueItem", "HALF");
        assertThat(bulk(batchId, formulaPayload).get("updated").asInt()).isEqualTo(3);
        assertThat(caseDetail(caseIds.get(0)).get("formulas").get("fixedRate").asString())
                .isEqualTo("=Notional/2");

        // mismatched kinds and unknown items are rejected
        Map<String, Object> wrongKind = body("fixedRate", "FORMULA", false);
        wrongKind.put("valueSet", valueSet);
        wrongKind.put("valueItem", "JPY");
        assertThat(api.post("/api/batches/" + batchId + "/cases/bulk-update",
                api.toJson(wrongKind)).status()).isEqualTo(400);
        Map<String, Object> unknownItem = body("notionalCurrency", "VALUE", false);
        unknownItem.put("valueSet", valueSet);
        unknownItem.put("valueItem", "NOPE");
        assertThat(api.post("/api/batches/" + batchId + "/cases/bulk-update",
                api.toJson(unknownItem)).status()).isEqualTo(400);
    }

    @Test
    void formulaSetRejectsContentWithoutEquals() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String setCode = "FMLBAD_" + suffix.toUpperCase();
        Map<String, Object> set = new LinkedHashMap<>();
        set.put("code", setCode);
        set.put("name", setCode);
        set.put("type", "FORMULA");
        assertThat(api.post("/api/meta/sets", api.toJson(set)).ok()).isTrue();

        Map<String, Object> item = new LinkedHashMap<>();
        item.put("code", "BROKEN");
        item.put("label", "broken");
        item.put("content", "Notional/2");
        assertThat(api.post("/api/meta/sets/" + setCode + "/items", api.toJson(item)).status()).isEqualTo(400);

        item.put("content", "=Notional/2");
        assertThat(api.post("/api/meta/sets/" + setCode + "/items", api.toJson(item)).ok()).isTrue();

        // the set kind is editable: switching to VALUE accepts plain text
        Map<String, Object> patch = new LinkedHashMap<>();
        patch.put("name", setCode);
        patch.put("type", "VALUE");
        assertThat(api.patch("/api/meta/sets/" + setCode, api.toJson(patch)).ok()).isTrue();
        Map<String, Object> plain = new LinkedHashMap<>();
        plain.put("code", "PLAIN");
        plain.put("label", "plain");
        plain.put("content", "plain text");
        assertThat(api.post("/api/meta/sets/" + setCode + "/items", api.toJson(plain)).ok()).isTrue();
    }

    private Map<String, Object> body(String fieldKey, String mode, boolean dryRun) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("fieldKey", fieldKey);
        payload.put("mode", mode);
        payload.put("dryRun", dryRun);
        // the whole batch is a destructive scope, so the server requires it to be declared
        payload.put("scope", "BATCH");
        return payload;
    }

    private JsonNode bulk(String batchId, Map<String, Object> payload) {
        var response = api.post("/api/batches/" + batchId + "/cases/bulk-update", api.toJson(payload));
        assertThat(response.ok()).as(response.body()).isTrue();
        return response.json();
    }

    private JsonNode caseDetail(String caseId) {
        var response = api.get("/api/cases/" + caseId);
        assertThat(response.ok()).as(response.body()).isTrue();
        return response.json();
    }

    private List<String> caseIds(String batchId) {
        List<String> ids = new ArrayList<>();
        api.get("/api/batches/" + batchId + "/cases?size=50").json().get("items")
                .forEach(item -> ids.add(item.get("id").asString()));
        return ids;
    }

    private String importBatch(String fileNamePrefix, String suffix) {
        String productCode = productWithFields("MW", "IRS", KEEP_FIELDS);
        String csv = CSV_TEMPLATE.formatted(suffix, suffix, suffix);
        var upload = api.upload("/api/uploads", fileNamePrefix + ".csv", csv.getBytes(StandardCharsets.UTF_8));
        assertThat(upload.ok()).as(upload.body()).isTrue();
        var imported = api.post("/api/imports", api.toJson(Map.of(
                "uploadId", upload.json().get("uploadId").asString(), "force", false,
                "sheets", List.of(Map.of("sheetIndex", 0, "productCode", productCode)))));
        assertThat(imported.ok()).as(imported.body()).isTrue();
        return imported.json().get("batches").get(0).get("batchId").asString();
    }
}
