package ac.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * Export and restore of the definition catalogue: the workbook round trip, the pre-check
 * classification and the three import modes.
 */
class DefinitionBackupTest extends AbstractIntegrationTest {

    @Test
    void exportsWorkbooksAndReplayableSql() throws Exception {
        byte[] workbook = api.getBytes("/api/definitions/export?scope=META");
        try (XSSFWorkbook book = new XSSFWorkbook(new ByteArrayInputStream(workbook))) {
            assertThat(book.getSheet("META_SET")).as("META_SET sheet").isNotNull();
            Sheet items = book.getSheet("META_ITEM");
            assertThat(items).as("META_ITEM sheet").isNotNull();
            assertThat(items.getRow(0).getCell(0).getStringCellValue()).isEqualTo("set_code");
            assertThat(items.getLastRowNum()).isGreaterThan(0);
        }

        byte[] productWorkbook = api.getBytes("/api/definitions/export?scope=PRODUCT");
        try (XSSFWorkbook book = new XSSFWorkbook(new ByteArrayInputStream(productWorkbook))) {
            assertThat(book.getSheet("PLATFORM")).isNotNull();
            assertThat(book.getSheet("BASE_PRODUCT").getLastRowNum()).isGreaterThan(0);
            assertThat(book.getSheet("BASE_FIELD").getLastRowNum()).isGreaterThan(0);
            assertThat(book.getSheet("PRODUCT").getLastRowNum()).isGreaterThan(0);
        }

        String sql = new String(api.getBytes("/api/definitions/export?scope=META&format=sql"),
                StandardCharsets.UTF_8);
        assertThat(sql).contains("DELETE FROM meta_item WHERE tenant_id = 'default'")
                .contains("DELETE FROM meta_set WHERE tenant_id = 'default'")
                .contains("INSERT INTO meta_item")
                .contains("COMMIT;");

        String productSql = new String(api.getBytes("/api/definitions/export?scope=PRODUCT&format=sql"),
                StandardCharsets.UTF_8);
        assertThat(productSql).contains("MERGE INTO platform")
                .contains("DELETE FROM product_field WHERE tenant_id = 'default'")
                .contains("INSERT INTO base_product_field");

        assertThat(api.get("/api/definitions/export?scope=NOPE").status()).isEqualTo(400);
        assertThat(api.get("/api/definitions/export?scope=META&format=json").status()).isEqualTo(400);
    }

    @Test
    void previewClassifiesRowsAndReportsInvalidOnes() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        String newSet = "T_" + suffix;
        // an existing set that the file will update, a new set, and a row pointing nowhere
        Map<String, List<List<String>>> sheets = new LinkedHashMap<>();
        sheets.put("META_SET", List.of(
                List.of(newSet, "New set", "", "false", "90", "VALUE"),
                List.of("FIELD_TAG", "Field tags", "renamed", "true", "3", "VALUE")));
        sheets.put("META_ITEM", List.of(
                List.of(newSet, "A", "Value A", "", "1", "true", "", "alpha", "", ""),
                List.of("MISSING_SET", "X", "Broken", "", "1", "true", "", "", "", "")));

        JsonNode plan = preview("META", workbook(sheets));

        assertThat(plan.get("scope").asString()).isEqualTo("META");
        JsonNode counts = plan.get("counts");
        assertThat(counts.get("ADD").asInt()).isEqualTo(2);
        assertThat(counts.get("CONFLICT").asInt()).isEqualTo(1);
        assertThat(counts.get("INVALID").asInt()).isEqualTo(1);

        JsonNode invalid = rowOf(plan, "INVALID");
        assertThat(invalid.get("key").asString()).isEqualTo("MISSING_SET/X");
        assertThat(invalid.get("messages").get(0).asString()).contains("集合 MISSING_SET 不存在");

        // applying the plan writes the additions and overwrites the conflict
        JsonNode result = api.post("/api/definitions/import/" + plan.get("planId").asString(),
                api.toJson(Map.of("mode", "MERGE_OVERWRITE"))).json();
        assertThat(result.get("created").asInt()).isEqualTo(2);
        assertThat(result.get("updated").asInt()).isEqualTo(1);
        assertThat(result.get("invalid").asInt()).isEqualTo(1);

        assertThat(api.get("/api/meta/sets/" + newSet + "/items").json().get(0).get("code").asString())
                .isEqualTo("A");
        // restoring the tag set definition back keeps the other tests independent
        JsonNode tagSet = api.get("/api/meta/sets").json().findValues("set").stream()
                .filter(node -> "FIELD_TAG".equals(node.get("code").asString())).findFirst().orElseThrow();
        assertThat(tagSet.get("description").asString()).isEqualTo("renamed");
        api.patch("/api/meta/sets/FIELD_TAG", api.toJson(Map.of("name", "字段标签",
                "description", "用于分类与检索的标签", "sortOrder", 3, "type", "VALUE")));
        api.delete("/api/meta/sets/" + newSet);
    }

    @Test
    void mergeSkipKeepsStoredDefinitionsAndFullRestoreReplacesThem() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        String setCode = "R_" + suffix;
        var created = api.post("/api/meta/sets", api.toJson(Map.of(
                "code", setCode, "name", "Restore me", "type", "VALUE")));
        assertThat(created.ok()).as(created.body()).isTrue();
        api.post("/api/meta/sets/" + setCode + "/items", api.toJson(Map.of(
                "code", "OLD", "label", "Old value", "content", "old")));

        Map<String, List<List<String>>> sheets = new LinkedHashMap<>();
        sheets.put("META_SET", List.of(List.of(setCode, "Restored name", "from file", "false", "95", "VALUE")));
        sheets.put("META_ITEM", List.of(List.of(setCode, "NEW", "New value", "", "1", "true", "", "new", "", "")));
        JsonNode plan = preview("META", workbook(sheets));
        assertThat(rowOf(plan, "CONFLICT").get("key").asString()).isEqualTo(setCode);

        JsonNode skipped = api.post("/api/definitions/import/" + plan.get("planId").asString(),
                api.toJson(Map.of("mode", "MERGE_SKIP"))).json();
        assertThat(skipped.get("created").asInt()).isEqualTo(1);
        assertThat(skipped.get("skipped").asInt()).isEqualTo(1);
        JsonNode stored = api.get("/api/meta/sets").json().findValues("set").stream()
                .filter(node -> setCode.equals(node.get("code").asString())).findFirst().orElseThrow();
        assertThat(stored.get("name").asString()).isEqualTo("Restore me");

        // a full restore needs the complete dictionary, so it starts from a fresh export
        byte[] complete = api.getBytes("/api/definitions/export?scope=META");
        JsonNode secondPlan = preview("META", complete);
        JsonNode restored = api.post("/api/definitions/import/" + secondPlan.get("planId").asString(),
                api.toJson(Map.of("mode", "FULL_RESTORE"))).json();
        assertThat(restored.get("deleted").asInt()).isGreaterThan(0);
        // every row of the file is written again; the values the application requires are kept
        int written = restored.get("created").asInt() + restored.get("updated").asInt();
        assertThat(written).as(secondPlan.get("counts").toString())
                .isEqualTo(secondPlan.get("counts").get("TOTAL").asInt());
        List<String> items = new ArrayList<>();
        api.get("/api/meta/sets/" + setCode + "/items").json().forEach(node -> items.add(node.get("code").asString()));
        assertThat(items).containsExactly("NEW", "OLD");

        // the values the application requires survive a full restore
        assertThat(api.get("/api/meta/sets/VALUE_TYPE/items").json().size()).isGreaterThanOrEqualTo(7);
        api.delete("/api/meta/sets/" + setCode);
    }

    @Test
    void productDefinitionsRoundTripThroughTheWorkbook() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        String baseCode = "BKP_" + suffix;
        String productCode = "BKPP_" + suffix;
        JsonNode base = api.post("/api/catalog/platforms/MW/base-products", api.toJson(Map.of(
                "code", baseCode, "name", baseCode))).json();
        String baseId = base.get("id").asString();
        addField(baseId, "testCaseId", "_TestCaseId", "TEXT", "MANAGED", true);
        addField(baseId, "tradeId", "Trade ID", "TEXT", "BUSINESS", true);
        api.post("/api/catalog/base-products/" + baseId + "/products", api.toJson(Map.of(
                "code", productCode, "name", productCode)));

        byte[] export = api.getBytes("/api/definitions/export?scope=PRODUCT");
        JsonNode plan = preview("PRODUCT", export);
        assertThat(plan.get("counts").get("ADD").asInt()).isZero();
        assertThat(plan.get("counts").get("INVALID").asInt()).isZero();
        // a file exported from the same catalogue has nothing to change
        assertThat(plan.get("counts").get("CONFLICT").asInt()).isZero();
        assertThat(plan.get("counts").get("UNCHANGED").asInt()).isGreaterThan(0);

        // editing the base field also updates the derived product copy, so both rows are conflicts
        patchBaseField(baseId, "tradeId", "Trade ID renamed");
        JsonNode conflictPlan = preview("PRODUCT", export);
        List<String> conflicts = new ArrayList<>();
        conflictPlan.get("rows").forEach(row -> {
            if ("CONFLICT".equals(row.get("status").asString())) {
                conflicts.add(row.get("key").asString());
            }
        });
        assertThat(conflicts).as(conflictPlan.toString())
                .containsExactly("MW/" + baseCode + "/tradeId", productCode + "/tradeId");
        JsonNode applied = api.post("/api/definitions/import/" + conflictPlan.get("planId").asString(),
                api.toJson(Map.of("mode", "MERGE_OVERWRITE"))).json();
        assertThat(applied.get("updated").asInt()).isEqualTo(2);
        assertThat(baseFieldHeader(baseId, "tradeId")).isEqualTo("Trade ID");
    }

    // ------------------------------------------------------------------ helpers

    private void addField(String baseId, String fieldKey, String header, String valueType, String role,
            boolean required) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("fieldKey", fieldKey);
        payload.put("header", header);
        payload.put("displayName", header);
        payload.put("aliases", List.of());
        payload.put("valueTypeCode", valueType);
        payload.put("roleCode", role);
        payload.put("tags", List.of());
        payload.put("required", required);
        var added = api.post("/api/catalog/base-products/" + baseId + "/fields", api.toJson(payload));
        assertThat(added.ok()).as(added.body()).isTrue();
    }

    private void patchBaseField(String baseId, String fieldKey, String header) {
        for (JsonNode field : api.get("/api/catalog/base-products/" + baseId).json().get("fields")) {
            if (!fieldKey.equals(field.get("fieldKey").asString())) {
                continue;
            }
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("fieldKey", fieldKey);
            payload.put("header", header);
            payload.put("displayName", header);
            payload.put("aliases", List.of());
            payload.put("valueTypeCode", field.get("valueTypeCode").asString());
            payload.put("roleCode", field.get("roleCode").asString());
            payload.put("tags", List.of());
            payload.put("required", field.get("required").asBoolean());
            payload.put("sortOrder", field.get("sortOrder").asInt());
            var patched = api.patch("/api/catalog/base-products/" + baseId + "/fields/"
                    + field.get("id").asString(), api.toJson(payload));
            assertThat(patched.ok()).as(patched.body()).isTrue();
            return;
        }
        throw new IllegalStateException("Field " + fieldKey + " not found");
    }

    private String baseFieldHeader(String baseId, String fieldKey) {
        for (JsonNode field : api.get("/api/catalog/base-products/" + baseId).json().get("fields")) {
            if (fieldKey.equals(field.get("fieldKey").asString())) {
                return field.get("header").asString();
            }
        }
        throw new IllegalStateException("Field " + fieldKey + " not found");
    }

    private JsonNode rowOf(JsonNode plan, String status) {
        for (JsonNode row : plan.get("rows")) {
            if (status.equals(row.get("status").asString())) {
                return row;
            }
        }
        throw new IllegalStateException("No " + status + " row in " + plan.get("rows"));
    }

    private JsonNode preview(String scope, byte[] workbook) {
        var response = api.upload("/api/definitions/import/preview?scope=" + scope, "definitions.xlsx",
                workbook);
        assertThat(response.ok()).as(response.body()).isTrue();
        return response.json();
    }

    /** Builds a workbook from a sheet name → rows mapping, the first row holding the columns. */
    private static byte[] workbook(Map<String, List<List<String>>> sheets) throws Exception {
        try (XSSFWorkbook book = new XSSFWorkbook();
                java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream()) {
            for (Map.Entry<String, List<List<String>>> entry : sheets.entrySet()) {
                Sheet sheet = book.createSheet(entry.getKey());
                List<String> header = ac.core.service.definition.DefinitionTable.bySheet(entry.getKey())
                        .orElseThrow().columns();
                Row head = sheet.createRow(0);
                for (int i = 0; i < header.size(); i++) {
                    head.createCell(i).setCellValue(header.get(i));
                }
                int index = 1;
                for (List<String> values : entry.getValue()) {
                    Row row = sheet.createRow(index++);
                    for (int i = 0; i < values.size(); i++) {
                        if (!values.get(i).isEmpty()) {
                            row.createCell(i).setCellValue(values.get(i));
                        }
                    }
                }
            }
            book.write(out);
            return out.toByteArray();
        }
    }
}
