package ac.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * Business processing works on the original cell text recorded at import time, never on the
 * type-normalized value.
 */
class RawCellTextTest extends AbstractIntegrationTest {

    private static final String HEADER = "_TestCaseId,_Remarks,Trade ID,Trade Date,Maturity Date,Notional";

    private static final List<String> KEEP_FIELDS =
            List.of("testCaseId", "remarks", "tradeId", "tradeDate", "maturityDate", "notional");

    @Test
    void storedRowKeepsTheOriginalTextNextToTheTypedValue() {
        String product = productWithFields("MW", "IRS", KEEP_FIELDS);
        String suffix = UUID.randomUUID().toString().substring(0, 6);
        String batchId = importCsv(product, "raw_" + suffix, HEADER + "\n"
                + "TC-1,padded row,  T-" + suffix + "  ,2026/01/05,2031/01/05,\"1,500,000.00\"\n");

        JsonNode detail = firstCase(batchId);
        // the typed copy is still stored for display, filtering and sorting
        assertThat(detail.get("data").get("notional").decimalValue()).isEqualByComparingTo("1500000.00");
        assertThat(detail.get("data").get("tradeDate").asString()).isEqualTo("2026-01-05");

        // the original text is what an export writes back
        JsonNode batch = api.get("/api/batches/" + batchId).json();
        String csv = exportCsv(batchId);
        assertThat(csv).contains("\"1,500,000.00\"");
        assertThat(csv).contains("2026/01/05");
        assertThat(csv).contains("  T-" + suffix + "  ");
        assertThat(batch.get("productCode").asString()).isEqualTo(product);
    }

    @Test
    void validationAndComparisonUseTheOriginalText() {
        String setCode = createRuleSet();
        createRule(setCode, "PLAIN_TRADE_ID", "WILDCARD", "T-*");
        String product = productWithFields("MW", "IRS", KEEP_FIELDS);
        bindRule(product, "tradeId", "PLAIN_TRADE_ID");

        String suffix = UUID.randomUUID().toString().substring(0, 6);
        String batchId = importCsv(product, "rawcmp_" + suffix, HEADER + "\n"
                + "TC-1,,  T-" + suffix + "  ,2026-01-02,2031-01-02,1000\n"
                + "TC-2,,T-" + suffix + ",2026-01-02,2031-01-02,1000\n");

        // the padded value fails, the plain one passes: the rule sees the cell text, spaces included
        JsonNode validation = api.get("/api/batches/" + batchId + "/validation").json();
        assertThat(validation.get("rowsFailed").asInt()).isEqualTo(1);
        assertThat(validation.get("failures").get(0).get("testCaseId").asString()).isEqualTo("TC-1");
        assertThat(validation.get("failures").get(0).get("failures").get(0).get("value").asString())
                .isEqualTo("  T-" + suffix + "  ");

        JsonNode cases = api.get("/api/batches/" + batchId + "/cases?size=10").json().get("items");
        String paddedId = cases.get(0).get("id").asString();
        String plainId = cases.get(1).get("id").asString();
        // the two rows differ only by formatting, so their record ids differ as well
        assertThat(cases.get(0).get("businessRecordId").asString())
                .isNotEqualTo(cases.get(1).get("businessRecordId").asString());

        JsonNode compare = api.post("/api/cases/" + paddedId + "/compare",
                api.toJson(Map.of("rightCaseId", plainId))).json();
        JsonNode tradeIdDiff = fieldOf(compare, "tradeId");
        assertThat(tradeIdDiff.get("status").asString()).isEqualTo("DIFFERENT");
        assertThat(tradeIdDiff.get("leftValue").asString()).isEqualTo("  T-" + suffix + "  ");

        // saving the plain text replaces the original text, so the row starts passing
        JsonNode before = api.get("/api/cases/" + paddedId).json();
        var patched = api.patch("/api/cases/" + paddedId, api.toJson(Map.of(
                "version", before.get("version").asInt(),
                "values", Map.of("tradeId", "T-" + suffix))));
        assertThat(patched.ok()).as(patched.body()).isTrue();
        JsonNode after = api.get("/api/cases/" + paddedId + "/validation").json();
        assertThat(after.get("rowsFailed").asInt()).isZero();
    }

    @Test
    void requiredMetaValuesAreReadOnlyAndRestoredWhenMissing() {
        var edit = api.patch("/api/meta/sets/VALUE_TYPE/items/TEXT", api.toJson(Map.of(
                "label", "任意文本", "sortOrder", 1, "enabled", true)));
        assertThat(edit.status()).as(edit.body()).isEqualTo(409);
        var remove = api.delete("/api/meta/sets/VALUE_TYPE/items/TEXT");
        assertThat(remove.status()).as(remove.body()).isEqualTo(409);
        var disableRole = api.patch("/api/meta/sets/FIELD_ROLE/items/BUSINESS", api.toJson(Map.of(
                "label", "业务字段", "sortOrder", 1, "enabled", false)));
        assertThat(disableRole.status()).as(disableRole.body()).isEqualTo(409);

        // values that are not required stay editable
        String setCode = createRuleSet();
        String ruleCode = createRule(setCode, "EDITABLE_RULE", "WILDCARD", "T-*");
        var renamed = api.patch("/api/meta/sets/" + setCode + "/items/" + ruleCode, api.toJson(Map.of(
                "label", "renamed", "content", "T-*", "ruleType", "WILDCARD", "enabled", true)));
        assertThat(renamed.ok()).as(renamed.body()).isTrue();

        // the item view flags the required values so the screen can hide the actions
        JsonNode items = api.get("/api/meta/sets/VALUE_TYPE/items").json();
        boolean textReadonly = false;
        for (JsonNode item : items) {
            if ("TEXT".equals(item.get("code").asString())) {
                textReadonly = item.get("readonly").asBoolean();
            }
        }
        assertThat(textReadonly).isTrue();
    }

    @Test
    void textValuesLiveInTheOriginalTextOnlyAndDisplayFollowsIt() {
        String product = productWithFields("MW", "IRS", KEEP_FIELDS);
        String suffix = UUID.randomUUID().toString().substring(0, 6);
        String batchId = importCsv(product, "textonly_" + suffix, HEADER + "\n"
                + "TC-1,padded row,  T-" + suffix + "  ,2026/01/05,2031/01/05,\"1,500,000.00\"\n");

        JsonNode detail = api.get("/api/cases/" + firstCaseId(batchId)).json();
        // text values get no normalized copy at all, only the original text
        assertThat(detail.get("data").has("tradeId")).isFalse();
        assertThat(detail.get("data").has("remarks")).isFalse();
        // date / numeric values keep the normalized copy for sorting and filtering
        assertThat(detail.get("data").get("tradeDate").asString()).isEqualTo("2026-01-05");
        assertThat(detail.get("data").get("notional").decimalValue()).isEqualByComparingTo("1500000.00");
        // every screen reads the original text
        assertThat(detail.get("values").get("tradeId").asString()).isEqualTo("  T-" + suffix + "  ");
        assertThat(detail.get("managed").get("_Remarks").asString()).isEqualTo("padded row");
        assertThat(detail.get("values").get("notional").asString()).isEqualTo("1,500,000.00");

        // the case list preview and the vertical view show the same text
        JsonNode preview = api.get("/api/batches/" + batchId + "/cases?size=5").json()
                .get("items").get(0).get("preview");
        boolean tradeIdSeen = false;
        for (JsonNode field : preview) {
            if ("Trade ID".equals(field.get("header").asString())) {
                tradeIdSeen = ("  T-" + suffix + "  ").equals(field.get("value").asString());
            }
        }
        assertThat(tradeIdSeen).isTrue();
        JsonNode vertical = api.get("/api/cases/" + firstCaseId(batchId) + "/vertical").json();
        String verticalTradeId = null;
        for (JsonNode row : vertical.get("sections").get(1).get("rows")) {
            if ("tradeId".equals(row.get("fieldKey").asString())) {
                verticalTradeId = row.get("value").asString();
            }
        }
        assertThat(verticalTradeId).isEqualTo("  T-" + suffix + "  ");
    }

    @Test
    void timeAndDateTimeTypesAreNormalizedForSortingOnly() {
        String baseId = baseProductIdOf("MW", "IRS");
        String suffix = UUID.randomUUID().toString().substring(0, 6);
        String timeKey = addBaseField(baseId, "Booking Time", "TIME");
        String stampKey = addBaseField(baseId, "Booking Stamp", "DATETIME");
        try {
            String product = productWithFields("MW", "IRS", List.of(timeKey, stampKey));
            String csv = "Booking Time,Booking Stamp\n9:05,2026/01/05 9:30\n";
            String batchId = importCsv(product, "timelike_" + suffix, csv);
            JsonNode detail = api.get("/api/cases/" + firstCaseId(batchId)).json();
            // normalized copies exist for sorting and filtering only
            assertThat(detail.get("data").get(timeKey).asString()).isEqualTo("09:05:00");
            assertThat(detail.get("data").get(stampKey).asString()).startsWith("2026-01-05T09:30");
            // the display keeps what the file said
            assertThat(detail.get("values").get(timeKey).asString()).isEqualTo("9:05");
            assertThat(detail.get("values").get(stampKey).asString()).isEqualTo("2026/01/05 9:30");
        } finally {
            api.delete("/api/catalog/base-products/" + baseId + "/fields/" + fieldIdOf(baseId, timeKey));
            api.delete("/api/catalog/base-products/" + baseId + "/fields/" + fieldIdOf(baseId, stampKey));
        }
    }

    @Test
    void testOnlyValueTypesAreNoLongerRequired() {
        // CONV_METHOD was a test type: it may be renamed now
        var renamed = api.patch("/api/meta/sets/VALUE_TYPE/items/CONV_METHOD", api.toJson(Map.of(
                "label", "Conversion Method", "enabled", true, "refSetCode", "CONV_METHOD")));
        assertThat(renamed.ok()).as(renamed.body()).isTrue();

        JsonNode items = api.get("/api/meta/sets/VALUE_TYPE/items").json();
        boolean convReadonly = true;
        boolean textReadonly = false;
        for (JsonNode item : items) {
            if ("CONV_METHOD".equals(item.get("code").asString())) {
                convReadonly = item.get("readonly").asBoolean();
            }
            if ("TEXT".equals(item.get("code").asString())) {
                textReadonly = item.get("readonly").asBoolean();
            }
        }
        assertThat(convReadonly).isFalse();
        assertThat(textReadonly).isTrue();
        // the required set now carries the time types used by the normalization rule
        assertThat(items.toString()).contains("TIME").contains("DATETIME");
    }

    @Test
    void singleEditUpdatesBothTheOriginalTextAndTheNormalizedCopy() {
        String product = productWithFields("MW", "IRS", KEEP_FIELDS);
        String suffix = UUID.randomUUID().toString().substring(0, 6);
        String batchId = importCsv(product, "editboth_" + suffix, HEADER + "\n"
                + "TC-1,,T-" + suffix + ",2026/01/02,2031/01/02,1000\n");
        JsonNode before = api.get("/api/cases/" + firstCaseId(batchId)).json();
        String caseId = before.get("id").asString();

        var patched = api.patch("/api/cases/" + caseId, api.toJson(Map.of(
                "version", before.get("version").asInt(),
                "values", Map.of(
                        "tradeId", "  T-" + suffix + "-NEW  ",
                        "notional", "2,500,000.50",
                        "tradeDate", "2031/12/31"))));
        assertThat(patched.ok()).as(patched.body()).isTrue();
        JsonNode after = patched.json();

        // the submitted text is the new original text, spacing and separators included
        assertThat(after.get("values").get("tradeId").asString()).isEqualTo("  T-" + suffix + "-NEW  ");
        assertThat(after.get("values").get("notional").asString()).isEqualTo("2,500,000.50");
        assertThat(after.get("values").get("tradeDate").asString()).isEqualTo("2031/12/31");
        // date and number fields also refresh their normalized copy
        assertThat(after.get("data").get("notional").decimalValue()).isEqualByComparingTo("2500000.50");
        assertThat(after.get("data").get("tradeDate").asString()).isEqualTo("2031-12-31");
        // text fields carry no normalized copy at all
        assertThat(after.get("data").has("tradeId")).isFalse();

        // a managed text column keeps the submitted text verbatim, and clearing it drops the text
        var noted = api.patch("/api/cases/" + caseId, api.toJson(Map.of(
                "version", after.get("version").asInt(),
                "managed", Map.of("_Remarks", "  reviewed by QA  "))));
        assertThat(noted.ok()).as(noted.body()).isTrue();
        assertThat(noted.json().get("managed").get("_Remarks").asString()).isEqualTo("  reviewed by QA  ");

        var cleared = api.patch("/api/cases/" + caseId, api.toJson(Map.of(
                "version", noted.json().get("version").asInt(),
                "managed", Map.of("_Remarks", ""))));
        assertThat(cleared.ok()).as(cleared.body()).isTrue();
        assertThat(cleared.json().get("managed").has("_Remarks")).isFalse();
    }

    @Test
    void bulkEditUpdatesBothTheOriginalTextAndTheNormalizedCopy() {
        String product = productWithFields("MW", "IRS", KEEP_FIELDS);
        String suffix = UUID.randomUUID().toString().substring(0, 6);
        String batchId = importCsv(product, "bulkboth_" + suffix, HEADER + "\n"
                + "TC-1,,T1-" + suffix + ",2026-01-02,2031-01-02,1000\n"
                + "TC-2,,T2-" + suffix + ",2026-01-03,2031-01-03,2000\n");

        var updated = api.post("/api/batches/" + batchId + "/cases/bulk-update", api.toJson(Map.of(
                "fieldKey", "notional", "mode", "VALUE", "value", "1,234,567.89", "scope", "BATCH")));
        assertThat(updated.ok()).as(updated.body()).isTrue();

        JsonNode cases = api.get("/api/batches/" + batchId + "/cases?size=10").json().get("items");
        for (JsonNode item : cases) {
            JsonNode detail = api.get("/api/cases/" + item.get("id").asString()).json();
            // both stores follow the submitted text
            assertThat(detail.get("values").get("notional").asString()).isEqualTo("1,234,567.89");
            assertThat(detail.get("data").get("notional").decimalValue())
                    .isEqualByComparingTo("1234567.89");
        }

        // a managed text column keeps only the original text, and it stays verbatim
        var remarks = api.post("/api/batches/" + batchId + "/cases/bulk-update", api.toJson(Map.of(
                "fieldKey", "remarks", "mode", "VALUE", "value", "  QA PASSED  ", "scope", "BATCH")));
        assertThat(remarks.ok()).as(remarks.body()).isTrue();
        for (JsonNode item : api.get("/api/batches/" + batchId + "/cases?size=10").json().get("items")) {
            JsonNode detail = api.get("/api/cases/" + item.get("id").asString()).json();
            assertThat(detail.get("managed").get("_Remarks").asString()).isEqualTo("  QA PASSED  ");
        }

        // clearing removes the original text together with the normalized copy
        var cleared = api.post("/api/batches/" + batchId + "/cases/bulk-update", api.toJson(Map.of(
                "fieldKey", "notional", "mode", "CLEAR", "scope", "BATCH")));
        assertThat(cleared.ok()).as(cleared.body()).isTrue();
        for (JsonNode item : api.get("/api/batches/" + batchId + "/cases?size=10").json().get("items")) {
            JsonNode detail = api.get("/api/cases/" + item.get("id").asString()).json();
            assertThat(detail.get("values").has("notional")).isFalse();
            assertThat(detail.get("data").has("notional")).isFalse();
        }

        var clearedRemarks = api.post("/api/batches/" + batchId + "/cases/bulk-update", api.toJson(Map.of(
                "fieldKey", "remarks", "mode", "CLEAR", "scope", "BATCH")));
        assertThat(clearedRemarks.ok()).as(clearedRemarks.body()).isTrue();
        for (JsonNode item : api.get("/api/batches/" + batchId + "/cases?size=10").json().get("items")) {
            JsonNode detail = api.get("/api/cases/" + item.get("id").asString()).json();
            assertThat(detail.get("values").has("remarks")).isFalse();
            assertThat(detail.get("managed").has("_Remarks")).isFalse();
        }
    }

    // ------------------------------------------------------------------ helpers

    /** Adds a base field (skipping it when the same header already exists) and returns its field key. */
    private String addBaseField(String baseId, String header, String valueTypeCode) {
        for (JsonNode field : api.get("/api/catalog/base-products/" + baseId).json().get("fields")) {
            if (header.equals(field.get("header").asString())) {
                return field.get("fieldKey").asString();
            }
        }
        var created = api.post("/api/catalog/base-products/" + baseId + "/fields", api.toJson(Map.of(
                "header", header, "valueTypeCode", valueTypeCode, "roleCode", "BUSINESS",
                "required", false)));
        assertThat(created.ok()).as(created.body()).isTrue();
        return created.json().get("fieldKey").asString();
    }

    private String fieldIdOf(String baseId, String fieldKey) {
        for (JsonNode field : api.get("/api/catalog/base-products/" + baseId).json().get("fields")) {
            if (fieldKey.equals(field.get("fieldKey").asString())) {
                return field.get("id").asString();
            }
        }
        throw new IllegalStateException("Base field " + fieldKey + " not found");
    }

    private String baseProductIdOf(String platformCode, String baseCode) {
        for (JsonNode platform : api.get("/api/catalog").json()) {
            if (!platformCode.equals(platform.get("code").asString())) {
                continue;
            }
            for (JsonNode base : platform.get("baseProducts")) {
                if (baseCode.equals(base.get("code").asString())) {
                    return base.get("id").asString();
                }
            }
        }
        throw new IllegalStateException("Base product " + platformCode + "/" + baseCode + " not found");
    }

    private String firstCaseId(String batchId) {
        JsonNode items = api.get("/api/batches/" + batchId + "/cases?size=10").json().get("items");
        assertThat(items).isNotEmpty();
        return items.get(0).get("id").asString();
    }

    private JsonNode fieldOf(JsonNode compare, String fieldKey) {
        for (JsonNode field : compare.get("fields")) {
            if (fieldKey.equals(field.get("fieldKey").asString())) {
                return field;
            }
        }
        throw new IllegalStateException("Field " + fieldKey + " is missing from the comparison");
    }

    private JsonNode firstCase(String batchId) {
        var response = api.get("/api/batches/" + batchId + "/cases?size=10");
        assertThat(response.ok()).as(response.body()).isTrue();
        JsonNode items = response.json().get("items");
        assertThat(items).isNotEmpty();
        return api.get("/api/cases/" + items.get(0).get("id").asString()).json();
    }

    private String exportCsv(String batchId) {
        var created = api.post("/api/exports", api.toJson(Map.of("batchId", batchId)));
        assertThat(created.ok()).as(created.body()).isTrue();
        String exportId = created.json().get("exportId").asString();
        return new String(api.getBytes("/api/exports/" + exportId + "/download"), StandardCharsets.UTF_8);
    }

    private String createRuleSet() {
        String code = "VR" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        var created = api.post("/api/meta/sets", api.toJson(Map.of(
                "code", code, "name", "Validation " + code, "type", "VALIDATION")));
        assertThat(created.ok()).as(created.body()).isTrue();
        return code;
    }

    private String createRule(String setCode, String ruleCode, String ruleType, String content) {
        var created = api.post("/api/meta/sets/" + setCode + "/items", api.toJson(Map.of(
                "code", ruleCode, "label", ruleCode, "content", content, "ruleType", ruleType)));
        assertThat(created.ok()).as(created.body()).isTrue();
        return created.json().get("code").asString();
    }

    private void bindRule(String productCode, String fieldKey, String expression) {
        String productId = productIdOf(productCode);
        for (JsonNode field : api.get("/api/catalog/products/" + productId).json().get("fields")) {
            if (!fieldKey.equals(field.get("fieldKey").asString())) {
                continue;
            }
            var patched = api.patch("/api/catalog/products/" + productId + "/fields/"
                    + field.get("id").asString(), api.toJson(Map.of(
                    "required", field.get("required").asBoolean(),
                    "sortOrder", field.get("sortOrder").asInt(),
                    "validationExpr", expression)));
            assertThat(patched.ok()).as(patched.body()).isTrue();
            return;
        }
        throw new IllegalStateException("Field " + fieldKey + " not found in product " + productCode);
    }

    private String importCsv(String productCode, String fileNamePrefix, String csv) {
        var upload = api.upload("/api/uploads", fileNamePrefix + ".csv", csv.getBytes(StandardCharsets.UTF_8));
        assertThat(upload.ok()).as(upload.body()).isTrue();
        var imported = api.post("/api/imports", api.toJson(Map.of(
                "uploadId", upload.json().get("uploadId").asString(), "force", false,
                "sheets", List.of(Map.of("sheetIndex", 0, "productCode", productCode)))));
        assertThat(imported.ok()).as(imported.body()).isTrue();
        return imported.json().get("batches").get(0).get("batchId").asString();
    }
}
