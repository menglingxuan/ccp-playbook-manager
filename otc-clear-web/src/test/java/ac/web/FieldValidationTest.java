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

/** Validation rules: meta set CRUD, field binding, preview marking and the validation panels. */
class FieldValidationTest extends AbstractIntegrationTest {

    private static final String HEADER = "_TestCaseId,_Remarks,Trade ID,Trade Date,Maturity Date,Notional";

    private static final List<String> KEEP_FIELDS =
            List.of("testCaseId", "remarks", "tradeId", "tradeDate", "maturityDate", "notional");

    @Test
    void ruleSetStoresPatternsAndRejectsBrokenOnes() {
        String setCode = createRuleSet();
        String regexCode = createRule(setCode, "CCY_ISO", "REGEX", "[A-Z]{3}");
        assertThat(regexCode).isEqualTo("CCY_ISO");

        // broken regular expression and broken wildcard are both rejected by the server
        var rejectInvalid = api.post("/api/meta/sets/" + setCode + "/items", api.toJson(Map.of(
                "code", "BROKEN", "label", "Broken", "content", "[A-Z", "ruleType", "REGEX")));
        assertThat(rejectInvalid.status()).as(rejectInvalid.body()).isEqualTo(400);

        // rule codes are referenced by field expressions, so they must stay unique across sets
        String otherSet = createRuleSet();
        var duplicate = api.post("/api/meta/sets/" + otherSet + "/items", api.toJson(Map.of(
                "code", "CCY_ISO", "label", "Duplicate", "content", "???", "ruleType", "WILDCARD")));
        assertThat(duplicate.status()).as(duplicate.body()).isEqualTo(409);

        JsonNode rules = api.get("/api/meta/rules").json();
        assertThat(codes(rules)).contains("CCY_ISO");

        JsonNode matched = api.post("/api/meta/rules/test",
                api.toJson(Map.of("expression", "CCY_ISO", "text", "USD"))).json();
        assertThat(matched.get("valid").asBoolean()).isTrue();
        assertThat(matched.get("matched").asBoolean()).isTrue();

        JsonNode rejected = api.post("/api/meta/rules/test",
                api.toJson(Map.of("expression", "CCY_ISO", "text", "usd"))).json();
        assertThat(rejected.get("matched").asBoolean()).isFalse();
        assertThat(rejected.get("problems").get(0).asString()).isEqualTo("CCY_ISO");

        // pattern test endpoint used by the rule editor, full match for both rule types
        assertThat(api.post("/api/meta/rules/match", api.toJson(Map.of(
                "ruleType", "WILDCARD", "expression", "US?", "text", "USD"))).json()
                .get("matched").asBoolean()).isTrue();
        assertThat(api.post("/api/meta/rules/match", api.toJson(Map.of(
                "ruleType", "WILDCARD", "expression", "US?", "text", "USDX"))).json()
                .get("matched").asBoolean()).isFalse();
        assertThat(api.post("/api/meta/rules/match", api.toJson(Map.of(
                "ruleType", "REGEX", "expression", "[A-Z", "text", "USD"))).json()
                .get("valid").asBoolean()).isFalse();
    }

    @Test
    void batchAndCaseValidationReportFailingRowsAndUnruledFields() {
        String setCode = createRuleSet();
        createRule(setCode, "TRADE_PREFIX", "WILDCARD", "TD-*");
        String product = productWithFields("MW", "IRS", KEEP_FIELDS);
        bindRule(product, "tradeId", "TRADE_PREFIX");

        String suffix = UUID.randomUUID().toString().substring(0, 6);
        String batchId = importCsv(product, "val_" + suffix, HEADER + "\n"
                + "TC-1,first,TD-" + suffix + ",2026-01-02,2031-01-02,1000\n"
                + "TC-2,second,BAD-" + suffix + ",2026-01-02,2031-01-02,1000\n");

        JsonNode batchResult = api.get("/api/batches/" + batchId + "/validation").json();
        assertThat(batchResult.get("scope").asString()).isEqualTo("BATCH");
        assertThat(batchResult.get("rowsChecked").asInt()).isEqualTo(2);
        assertThat(batchResult.get("rowsFailed").asInt()).isEqualTo(1);
        JsonNode failure = batchResult.get("failures").get(0);
        assertThat(failure.get("testCaseId").asString()).isEqualTo("TC-2");
        assertThat(failure.get("failures").get(0).get("fieldKey").asString()).isEqualTo("tradeId");
        assertThat(failure.get("failures").get(0).get("failedRules").get(0).asString())
                .isEqualTo("TRADE_PREFIX");
        // fields without a rule are reported so a green result is never mistaken for full coverage
        assertThat(texts(batchResult.get("unruledFields"))).contains("Notional").doesNotContain("Trade ID");

        String failingCaseId = failure.get("caseId").asString();
        JsonNode caseResult = api.get("/api/cases/" + failingCaseId + "/validation").json();
        assertThat(caseResult.get("scope").asString()).isEqualTo("CASE");
        assertThat(caseResult.get("rowsChecked").asInt()).isEqualTo(1);
        assertThat(caseResult.get("rowsFailed").asInt()).isEqualTo(1);
        assertThat(caseResult.get("failures").get(0).get("failures").get(0).get("expression").asString())
                .isEqualTo("TRADE_PREFIX");

        // the import itself records the failure as a warning instead of blocking it
        String taskId = api.get("/api/batches/" + batchId).json().get("taskId").asString();
        JsonNode taskIssues = api.get("/api/tasks/" + taskId).json().get("issues");
        boolean recorded = false;
        for (JsonNode issue : taskIssues) {
            if ("VALIDATION_FAILED".equals(issue.get("code").asString())
                    && "WARN".equals(issue.get("level").asString())) {
                recorded = true;
            }
        }
        assertThat(recorded).as(taskIssues.toString()).isTrue();
    }

    @Test
    void blankValuesAreSkippedByDefaultAndCheckedOnDemand() {
        String setCode = createRuleSet();
        createRule(setCode, "REF_PREFIX", "WILDCARD", "REF-*");
        String product = productWithFields("MW", "IRS", KEEP_FIELDS);
        // _Remarks is an optional managed column, so its value may stay empty
        bindRule(product, "remarks", "REF_PREFIX");

        String suffix = UUID.randomUUID().toString().substring(0, 6);
        String batchId = importCsv(product, "blank_" + suffix, HEADER + "\n"
                + "TC-1,,TD-" + suffix + ",2026-01-02,2031-01-02,1000\n");

        // a blank value is the business of the required flag, so the default run leaves it alone
        JsonNode skipped = api.get("/api/batches/" + batchId + "/validation").json();
        assertThat(skipped.get("skipBlank").asBoolean()).isTrue();
        assertThat(failureValue(skipped, "remarks")).isNull();
        JsonNode skippedStat = fieldStat(skipped, "remarks");
        assertThat(skippedStat.get("failed").asInt()).isZero();
        assertThat(skippedStat.get("checked").asInt()).isZero();

        // asking for blank values turns the same row into a failure
        JsonNode checked = api.get("/api/batches/" + batchId + "/validation?skipBlank=false").json();
        assertThat(checked.get("skipBlank").asBoolean()).isFalse();
        assertThat(failureValue(checked, "remarks")).isEmpty();
        assertThat(fieldStat(checked, "remarks").get("failed").asInt()).isEqualTo(1);

        // a managed column that does carry a value is checked like any other field
        String filled = importCsv(product, "blank_filled_" + suffix, HEADER + "\n"
                + "TC-2,REF-" + suffix + ",TD-2" + suffix + ",2026-01-02,2031-01-02,1000\n"
                + "TC-3,BAD-" + suffix + ",TD-3" + suffix + ",2026-01-02,2031-01-02,1000\n");
        JsonNode filledResult = api.get("/api/batches/" + filled + "/validation").json();
        assertThat(failureValue(filledResult, "remarks")).isEqualTo("BAD-" + suffix);
    }

    @Test
    void previewMarksFailingCellsWithoutBlockingTheImport() {
        String setCode = createRuleSet();
        createRule(setCode, "PREVIEW_PREFIX", "WILDCARD", "TD-*");
        String product = productWithFields("MW", "IRS", KEEP_FIELDS);
        bindRule(product, "tradeId", "PREVIEW_PREFIX");

        String suffix = UUID.randomUUID().toString().substring(0, 6);
        String csv = HEADER + "\n"
                + "TC-1,first,TD-" + suffix + ",2026-01-02,2031-01-02,1000\n"
                + "TC-2,second,XD-" + suffix + ",2026-01-02,2031-01-02,1000\n";
        var upload = api.upload("/api/uploads", "preview_" + suffix + ".csv",
                csv.getBytes(StandardCharsets.UTF_8));
        assertThat(upload.ok()).as(upload.body()).isTrue();

        JsonNode preview = api.post("/api/uploads/" + upload.json().get("uploadId").asString() + "/preview",
                api.toJson(Map.of("sheetIndex", 0, "productCode", product))).json();
        JsonNode rows = preview.get("preview").get("sampleRows");
        assertThat(rows).hasSize(2);
        List<String> warnFields = new ArrayList<>();
        for (JsonNode issue : rows.get(1).get("issues")) {
            if ("WARN".equals(issue.get("level").asString()) && issue.get("fieldKey") != null) {
                warnFields.add(issue.get("fieldKey").asString());
            }
        }
        assertThat(warnFields).contains("tradeId");
        // the preview stays valid: a rule failure is only a warning
        assertThat(preview.get("preview").get("valid").asBoolean()).isTrue();
    }

    @Test
    void derivedProductInheritsAndMayOverrideTheBaseExpression() {
        String setCode = createRuleSet();
        createRule(setCode, "ISO3", "REGEX", "[A-Z]{3}");
        createRule(setCode, "DIGITS", "REGEX", "[0-9]+");

        String product = productWithFields("MW", "IRS", KEEP_FIELDS);
        String baseId = baseProductIdOf("MW", "IRS");

        // bind on the base product only, the derived product follows
        patchBaseField(baseId, "tradeId", "ISO3");
        assertThat(productFieldExpression(product, "tradeId")).isEqualTo("ISO3");

        // an override survives a later base product change
        patchProductField(product, "tradeId", "DIGITS");
        assertThat(productFieldExpression(product, "tradeId")).isEqualTo("DIGITS");
        patchBaseField(baseId, "tradeId", "ISO3 AND DIGITS");
        assertThat(productFieldExpression(product, "tradeId")).isEqualTo("DIGITS");

        // clearing the override returns to the inherited expression
        patchProductField(product, "tradeId", "");
        assertThat(productFieldExpression(product, "tradeId")).isEqualTo("ISO3 AND DIGITS");

        // the base product is shared by the whole JVM, so leave it as it was found
        patchBaseField(baseId, "tradeId", "");
        assertThat(productFieldExpression(product, "tradeId")).isNullOrEmpty();
    }

    @Test
    void ruleInUseCannotBeDeletedAndAVanishedRuleNeverFailsEveryRow() {
        String setCode = createRuleSet();
        createRule(setCode, "IN_USE", "WILDCARD", "TD-*");
        String product = productWithFields("MW", "IRS", KEEP_FIELDS);
        bindRule(product, "tradeId", "IN_USE");

        // the rule is referenced by a field expression, so neither the item nor the set may go away
        var deleteItem = api.delete("/api/meta/sets/" + setCode + "/items/IN_USE");
        assertThat(deleteItem.status()).as(deleteItem.body()).isEqualTo(409);
        var deleteSet = api.delete("/api/meta/sets/" + setCode);
        assertThat(deleteSet.status()).as(deleteSet.body()).isEqualTo(409);

        // after clearing the binding both deletions are allowed again
        patchProductField(product, "tradeId", "");
        assertThat(api.delete("/api/meta/sets/" + setCode).status()).isEqualTo(200);
    }

    @Test
    void disabledRuleIsTreatedAsUnruledInsteadOfFailingEveryRow() {
        String setCode = createRuleSet();
        createRule(setCode, "TEMP_RULE", "WILDCARD", "TD-*");
        String product = productWithFields("MW", "IRS", KEEP_FIELDS);
        bindRule(product, "tradeId", "TEMP_RULE");

        // a disabled rule disappears from the plan, so the expression must not reject every value
        var disabled = api.patch("/api/meta/sets/" + setCode + "/items/TEMP_RULE", api.toJson(Map.of(
                "label", "TEMP_RULE", "content", "TD-*", "ruleType", "WILDCARD", "enabled", false)));
        assertThat(disabled.ok()).as(disabled.body()).isTrue();

        String suffix = UUID.randomUUID().toString().substring(0, 6);
        String batchId = importCsv(product, "gone_" + suffix, HEADER + "\n"
                + "TC-1,first,XX-" + suffix + ",2026-01-02,2031-01-02,1000\n");
        JsonNode result = api.get("/api/batches/" + batchId + "/validation").json();
        assertThat(result.get("rowsFailed").asInt()).isZero();
        assertThat(texts(result.get("unruledFields"))).contains("Trade ID");
        patchProductField(product, "tradeId", "");
    }

    @Test
    void errorSeverityBlocksTheRowWhileWarningOnlyReports() {
        String setCode = createRuleSet();
        createRuleWithSeverity(setCode, "BLOCK_PREFIX", "WILDCARD", "TD-*", "ERROR");
        String product = productWithFields("MW", "IRS", KEEP_FIELDS);
        bindRule(product, "tradeId", "BLOCK_PREFIX");

        String suffix = UUID.randomUUID().toString().substring(0, 6);
        String csv = HEADER + "\n"
                + "TC-1,first,TD-" + suffix + ",2026-01-02,2031-01-02,1000\n"
                + "TC-2,second,BAD-" + suffix + ",2026-01-02,2031-01-02,1000\n";
        String batchId = importCsv(product, "block_" + suffix, csv);

        // the blocking rule rejects the second row, so only the good one is stored
        JsonNode batch = api.get("/api/batches/" + batchId).json();
        assertThat(batch.get("activeCases").asInt()).isEqualTo(1);
        String taskId = batch.get("taskId").asString();
        JsonNode taskIssues = api.get("/api/tasks/" + taskId).json().get("issues");
        boolean blocking = false;
        for (JsonNode issue : taskIssues) {
            if ("VALIDATION_FAILED".equals(issue.get("code").asString())
                    && "ERROR".equals(issue.get("level").asString())) {
                blocking = true;
            }
        }
        assertThat(blocking).as(taskIssues.toString()).isTrue();

        // switching the rule back to warning lets the same row through and only flags it
        var downgraded = api.patch("/api/meta/sets/" + setCode + "/items/BLOCK_PREFIX", api.toJson(Map.of(
                "label", "BLOCK_PREFIX", "content", "TD-*", "ruleType", "WILDCARD", "severity", "WARN")));
        assertThat(downgraded.ok()).as(downgraded.body()).isTrue();
        String warned = importCsv(product, "warn_" + suffix, csv);
        assertThat(api.get("/api/batches/" + warned).json().get("activeCases").asInt()).isEqualTo(2);
        JsonNode warnedResult = api.get("/api/batches/" + warned + "/validation").json();
        assertThat(warnedResult.get("rowsFailed").asInt()).isEqualTo(1);
        assertThat(warnedResult.get("rowsBlocked").asInt()).isZero();
        JsonNode failure = warnedResult.get("failures").get(0);
        assertThat(failure.get("blocked").asBoolean()).isFalse();
        assertThat(failure.get("failures").get(0).get("severity").asString()).isEqualTo("WARN");
        patchProductField(product, "tradeId", "");
    }

    @Test
    void severityIsValidatedAndStoredOnTheRule() {
        String setCode = createRuleSet();
        JsonNode created = api.post("/api/meta/sets/" + setCode + "/items", api.toJson(Map.of(
                "code", "SEV_ERROR", "label", "Sev", "content", "TD-*", "ruleType", "WILDCARD",
                "severity", "ERROR"))).json();
        assertThat(created.get("severity").asString()).isEqualTo("ERROR");

        // a new rule warns by default
        JsonNode plain = api.post("/api/meta/sets/" + setCode + "/items", api.toJson(Map.of(
                "code", "SEV_PLAIN", "label", "Plain", "content", "TD-*", "ruleType", "WILDCARD"))).json();
        assertThat(plain.get("severity").asString()).isEqualTo("WARN");

        var invalid = api.post("/api/meta/sets/" + setCode + "/items", api.toJson(Map.of(
                "code", "SEV_BAD", "label", "Bad", "content", "TD-*", "ruleType", "WILDCARD",
                "severity", "FATAL")));
        assertThat(invalid.status()).as(invalid.body()).isEqualTo(400);

        // a plain value set has no severity, so the level is normalised away
        JsonNode value = api.post("/api/meta/sets/VALUE_TYPE/items", api.toJson(Map.of(
                "code", "SEV_TEXT", "label", "Text", "content", "x", "severity", "ERROR"))).json();
        assertThat(value.get("severity").asString()).isEqualTo("WARN");
        assertThat(api.delete("/api/meta/sets/VALUE_TYPE/items/SEV_TEXT").status()).isEqualTo(200);
    }

    @Test
    void valueTypeReferencingASetCountsAsCovered() {
        String suffix = UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        String baseCode = "SEVBASE_" + suffix;
        JsonNode base = api.post("/api/catalog/platforms/MW/base-products", api.toJson(Map.of(
                "code", baseCode, "name", baseCode))).json();
        String baseId = base.get("id").asString();
        addField(baseId, "testCaseId", "_TestCaseId", "TEXT", "MANAGED", true);
        addField(baseId, "remarks", "_Remarks", "TEXT", "MANAGED", false);
        addField(baseId, "tradeId", "Trade ID", "TEXT", "BUSINESS", true);
        // CONV_METHOD is a value type that references the CONV_METHOD set, so it is already validated
        addField(baseId, "convMethod", "Conv Method", "CONV_METHOD", "BUSINESS", false);

        String product = baseCode + "_P";
        var created = api.post("/api/catalog/base-products/" + baseId + "/products", api.toJson(Map.of(
                "code", product, "name", product)));
        assertThat(created.ok()).as(created.body()).isTrue();

        String batchId = importCsv(product, "enum_" + suffix, "_TestCaseId,_Remarks,Trade ID,Conv Method\n"
                + "TC-1,,TD-1,MODFOLLOWING\n");
        JsonNode result = api.get("/api/batches/" + batchId + "/validation").json();
        assertThat(texts(result.get("unruledFields"))).doesNotContain("Conv Method");
        JsonNode stat = fieldStat(result, "convMethod");
        assertThat(stat.get("ruled").asBoolean()).isTrue();
        assertThat(stat.get("implicit").asBoolean()).isTrue();
        assertThat(stat.get("expression").asString()).isEqualTo("SET:CONV_METHOD");
    }

    @Test
    void validationOptionsSwitchCaseSensitivityAndBlankHandling() {
        String setCode = createRuleSet();
        createRule(setCode, "OPT_PREFIX", "WILDCARD", "TD-*");
        String product = productWithFields("MW", "IRS", KEEP_FIELDS);
        bindRule(product, "tradeId", "OPT_PREFIX");

        String suffix = UUID.randomUUID().toString().substring(0, 6);
        String batchId = importCsv(product, "opt_" + suffix, HEADER + "\n"
                + "TC-1,first,td-" + suffix + ",2026-01-02,2031-01-02,1000\n");

        JsonNode sensitive = api.get("/api/batches/" + batchId + "/validation").json();
        assertThat(sensitive.get("ignoreCase").asBoolean()).isFalse();
        assertThat(sensitive.get("rowsFailed").asInt()).isEqualTo(1);

        JsonNode insensitive = api.get("/api/batches/" + batchId + "/validation?ignoreCase=true").json();
        assertThat(insensitive.get("ignoreCase").asBoolean()).isTrue();
        assertThat(insensitive.get("rowsFailed").asInt()).isZero();

        // the case level endpoint takes the same switches
        String caseId = api.get("/api/batches/" + batchId + "/cases?size=5").json()
                .get("items").get(0).get("id").asString();
        assertThat(api.get("/api/cases/" + caseId + "/validation?ignoreCase=true").json()
                .get("rowsFailed").asInt()).isZero();
        assertThat(api.get("/api/cases/" + caseId + "/validation").json()
                .get("rowsFailed").asInt()).isEqualTo(1);
    }

    // ------------------------------------------------------------------ helpers

    /**
     * Value of the first failure of one field, or {@code null} when the field did not fail.
     */
    private String failureValue(JsonNode result, String fieldKey) {
        for (JsonNode row : result.get("failures")) {
            for (JsonNode failure : row.get("failures")) {
                if (fieldKey.equals(failure.get("fieldKey").asString())) {
                    return failure.get("value") == null ? null : failure.get("value").asString();
                }
            }
        }
        return null;
    }

    /** Per field counters of one field. */
    private JsonNode fieldStat(JsonNode result, String fieldKey) {
        for (JsonNode stat : result.get("fields")) {
            if (fieldKey.equals(stat.get("fieldKey").asString())) {
                return stat;
            }
        }
        throw new IllegalStateException("Field " + fieldKey + " is missing from the report");
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

    private String createRuleWithSeverity(String setCode, String ruleCode, String ruleType, String content,
            String severity) {
        var created = api.post("/api/meta/sets/" + setCode + "/items", api.toJson(Map.of(
                "code", ruleCode, "label", ruleCode, "content", content, "ruleType", ruleType,
                "severity", severity)));
        assertThat(created.ok()).as(created.body()).isTrue();
        return created.json().get("code").asString();
    }

    private void addField(String baseId, String fieldKey, String header, String valueTypeCode,
            String roleCode, boolean required) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("fieldKey", fieldKey);
        payload.put("header", header);
        payload.put("displayName", header);
        payload.put("aliases", List.of());
        payload.put("valueTypeCode", valueTypeCode);
        payload.put("roleCode", roleCode);
        payload.put("tags", List.of());
        payload.put("required", required);
        var added = api.post("/api/catalog/base-products/" + baseId + "/fields", api.toJson(payload));
        assertThat(added.ok()).as(added.body()).isTrue();
    }

    private void bindRule(String productCode, String fieldKey, String expression) {
        patchProductField(productCode, fieldKey, expression);
    }

    private void patchProductField(String productCode, String fieldKey, String expression) {
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

    private String productFieldExpression(String productCode, String fieldKey) {
        String productId = productIdOf(productCode);
        for (JsonNode field : api.get("/api/catalog/products/" + productId).json().get("fields")) {
            if (fieldKey.equals(field.get("fieldKey").asString())) {
                return field.get("validationExpr") == null ? null : field.get("validationExpr").asString();
            }
        }
        throw new IllegalStateException("Field " + fieldKey + " not found in product " + productCode);
    }

    private void patchBaseField(String baseProductId, String fieldKey, String expression) {
        for (JsonNode field : api.get("/api/catalog/base-products/" + baseProductId).json().get("fields")) {
            if (!fieldKey.equals(field.get("fieldKey").asString())) {
                continue;
            }
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("fieldKey", fieldKey);
            payload.put("header", field.get("header").asString());
            payload.put("displayName", field.get("displayName").asString());
            payload.put("aliases", List.of());
            payload.put("valueTypeCode", field.get("valueTypeCode").asString());
            payload.put("roleCode", field.get("roleCode").asString());
            payload.put("tags", List.of());
            payload.put("required", field.get("required").asBoolean());
            payload.put("sortOrder", field.get("sortOrder").asInt());
            payload.put("validationExpr", expression);
            var patched = api.patch("/api/catalog/base-products/" + baseProductId + "/fields/"
                    + field.get("id").asString(), api.toJson(payload));
            assertThat(patched.ok()).as(patched.body()).isTrue();
            return;
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

    private List<String> codes(JsonNode rules) {
        List<String> values = new ArrayList<>();
        for (JsonNode rule : rules) {
            values.add(rule.get("code").asString());
        }
        return values;
    }

    private List<String> texts(JsonNode array) {
        List<String> values = new ArrayList<>();
        for (JsonNode item : array) {
            values.add(item.asString());
        }
        return values;
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
