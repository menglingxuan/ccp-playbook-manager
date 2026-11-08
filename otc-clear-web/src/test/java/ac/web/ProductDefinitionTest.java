package ac.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** Platform, base product, product and field meta information through the real HTTP API. */
class ProductDefinitionTest extends AbstractIntegrationTest {

    private static String suffix() {
        return UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }

    private String productFieldId(String productId, String fieldKey) {
        JsonNode fields = api.get("/api/catalog/products/" + productId).json().get("fields");
        for (JsonNode field : fields) {
            if (fieldKey.equals(field.get("fieldKey").asString())) {
                return field.get("id").asString();
            }
        }
        throw new IllegalStateException("Product field " + fieldKey + " not found");
    }

    @Test
    void derivesProductFromBaseProductAndKeepsFieldSubset() {
        String suffix = suffix();
        var created = api.post("/api/catalog/platforms/CFETS/base-products",
                api.toJson(Map.of("code", "BWOIS", "name", "BWOIS CFETS", "description", "api test")));
        assertThat(created.ok()).as(created.body()).isTrue();
        String baseId = created.json().get("id").asString();

        var available = api.get("/api/catalog/base-codes/suggestions");
        assertThat(available.ok()).isTrue();
        assertThat(available.body()).contains("BWOIS");

        var duplicate = api.post("/api/catalog/platforms/CFETS/base-products",
                api.toJson(Map.of("code", "BWOIS", "name", "BWOIS again")));
        assertThat(duplicate.status()).isEqualTo(409);

        var custom = api.post("/api/catalog/platforms/CFETS/base-products",
                api.toJson(Map.of("code", "NB_IRS_" + suffix, "name", "NB IRS CFETS")));
        assertThat(custom.ok()).as(custom.body()).isTrue();
        assertThat(custom.json().get("code").asString()).isEqualTo("NB_IRS_" + suffix);
        assertThat(api.delete("/api/catalog/base-products/" + custom.json().get("id").asString()).ok()).isTrue();

        var keyField = api.post("/api/catalog/base-products/" + baseId + "/fields", api.toJson(Map.of(
                "header", "Trade Ref",
                "valueTypeCode", "TEXT",
                "roleCode", "BUSINESS",
                "required", true,
                "tags", List.of("CLEARING"),
                "sortOrder", 1)));
        assertThat(keyField.ok()).as(keyField.body()).isTrue();
        String keyFieldId = keyField.json().get("id").asString();
        assertThat(keyField.json().get("fieldKey").asString()).isEqualTo("tradeRef");
        assertThat(keyField.json().get("tags").get(0).asString()).isEqualTo("CLEARING");

        var conventionField = api.post("/api/catalog/base-products/" + baseId + "/fields", api.toJson(Map.of(
                "header", "Business Day Convention",
                "valueTypeCode", "CONV_METHOD",
                "roleCode", "BUSINESS",
                "required", false,
                "sortOrder", 2)));
        assertThat(conventionField.ok()).as(conventionField.body()).isTrue();
        String conventionFieldId = conventionField.json().get("id").asString();
        assertThat(conventionField.json().get("valueTypeLabel").asString()).contains("Conversion Method");

        String productCode = "BWOIS_" + suffix;
        var product = api.post("/api/catalog/base-products/" + baseId + "/products",
                api.toJson(Map.of("code", productCode, "name", "BWOIS derived")));
        assertThat(product.ok()).as(product.body()).isTrue();
        String productId = product.json().get("id").asString();
        assertThat(product.json().get("fieldCount").asInt()).isEqualTo(2);

        var schema = api.get("/api/products/" + productCode);
        assertThat(schema.ok()).as(schema.body()).isTrue();
        assertThat(schema.json().get("fields")).hasSize(2);

        var removed = api.delete("/api/catalog/products/" + productId + "/fields/" + productFieldId(
                productId, "businessDayConvention"));
        assertThat(removed.ok()).as(removed.body()).isTrue();
        var trimmed = api.get("/api/products/" + productCode);
        assertThat(trimmed.json().get("fields")).hasSize(1);
        assertThat(trimmed.json().get("fields").get(0).get("key").asString()).isEqualTo("tradeRef");

        var baseDetail = api.get("/api/catalog/base-products/" + baseId);
        assertThat(baseDetail.json().get("fields")).hasSize(2);

        assertThat(api.delete("/api/catalog/base-products/" + baseId).status()).isEqualTo(409);

        var cascaded = api.delete("/api/catalog/base-products/" + baseId + "/fields/" + keyFieldId);
        assertThat(cascaded.ok()).as(cascaded.body()).isTrue();
        assertThat(cascaded.json().get("removedProductFields").asInt()).isEqualTo(1);
        assertThat(api.get("/api/products/" + productCode).json().get("fields")).isEmpty();

        assertThat(api.delete("/api/catalog/products/" + productId).ok()).isTrue();
        assertThat(api.delete("/api/catalog/base-products/" + baseId).ok()).isTrue();
        assertThat(api.get("/api/products/" + productCode).status()).isEqualTo(404);
    }

    @Test
    void rejectsDuplicateProductCode() {
        var catalog = api.get("/api/catalog");
        assertThat(catalog.ok()).isTrue();
        String baseId = null;
        for (JsonNode platform : catalog.json()) {
            if (!"MW".equals(platform.get("code").asString())) {
                continue;
            }
            for (JsonNode base : platform.get("baseProducts")) {
                if ("IRS".equals(base.get("code").asString())) {
                    baseId = base.get("id").asString();
                }
            }
        }
        assertThat(baseId).isNotNull();

        var duplicate = api.post("/api/catalog/base-products/" + baseId + "/products",
                api.toJson(Map.of("code", "IRS", "name", "duplicate")));
        assertThat(duplicate.status()).isEqualTo(409);
        assertThat(duplicate.json().get("code").asString()).isEqualTo("CONFLICT");
    }

    @Test
    void productUsedByImportedBatchCannotBeDeleted() {
        String suffix = suffix();
        String productCode = "GUARD_" + suffix;
        var catalog = api.get("/api/catalog");
        String baseId = null;
        for (JsonNode platform : catalog.json()) {
            if (!"MW".equals(platform.get("code").asString())) {
                continue;
            }
            for (JsonNode base : platform.get("baseProducts")) {
                if ("OIS".equals(base.get("code").asString())) {
                    baseId = base.get("id").asString();
                }
            }
        }
        assertThat(baseId).isNotNull();

        var product = api.post("/api/catalog/base-products/" + baseId + "/products",
                api.toJson(Map.of("code", productCode, "name", "guard")));
        assertThat(product.ok()).as(product.body()).isTrue();
        String productId = product.json().get("id").asString();

        String csv = "Trade ID,Trade Date,Maturity Date,Notional\nOIS-" + suffix + ",2026-01-02,2031-01-02,1000\n";
        var upload = api.upload("/api/uploads", "guard_" + suffix + ".csv",
                csv.getBytes(StandardCharsets.UTF_8));
        assertThat(upload.ok()).as(upload.body()).isTrue();
        var importResponse = api.post("/api/imports", api.toJson(Map.of(
                "uploadId", upload.json().get("uploadId").asString(),
                "force", false,
                "sheets", List.of(Map.of("sheetIndex", 0, "productCode", productCode)))));
        assertThat(importResponse.ok()).as(importResponse.body()).isTrue();

        var blocked = api.delete("/api/catalog/products/" + productId);
        assertThat(blocked.status()).isEqualTo(409);
        assertThat(blocked.json().get("code").asString()).isEqualTo("CONFLICT");
    }

    @Test
    void valueTypeReferencingMetaSetValidatesImportedValues() {
        String suffix = suffix();
        var created = api.post("/api/catalog/platforms/CFETS/base-products",
                api.toJson(Map.of("code", "CCSBSW", "name", "CCSBSW CFETS")));
        assertThat(created.ok()).as(created.body()).isTrue();
        String baseId = created.json().get("id").asString();

        assertThat(api.post("/api/catalog/base-products/" + baseId + "/fields", api.toJson(Map.of(
                "header", "Trade Ref", "valueTypeCode", "TEXT", "roleCode", "BUSINESS", "required", true)))
                .ok()).isTrue();
        assertThat(api.post("/api/catalog/base-products/" + baseId + "/fields", api.toJson(Map.of(
                "header", "BDC", "valueTypeCode", "CONV_METHOD", "roleCode", "BUSINESS", "required", false)))
                .ok()).isTrue();

        String productCode = "CCSBSW_" + suffix;
        var product = api.post("/api/catalog/base-products/" + baseId + "/products",
                api.toJson(Map.of("code", productCode, "name", "enum product")));
        assertThat(product.ok()).as(product.body()).isTrue();

        String invalidCsv = "Trade Ref,BDC\nREF-" + suffix + ",Following\n";
        var invalidUpload = api.upload("/api/uploads", "enum_bad_" + suffix + ".csv",
                invalidCsv.getBytes(StandardCharsets.UTF_8));
        var invalidPreview = api.post("/api/uploads/"
                + invalidUpload.json().get("uploadId").asString() + "/preview",
                api.toJson(Map.of("sheetIndex", 0, "productCode", productCode)));
        assertThat(invalidPreview.ok()).as(invalidPreview.body()).isTrue();
        JsonNode invalidRow = invalidPreview.json().get("preview").get("sampleRows").get(0);
        assertThat(invalidRow.get("valid").asBoolean()).isFalse();
        assertThat(invalidPreview.body()).contains("Modified Following");

        String validCsv = "Trade Ref,BDC\nREF-" + suffix + ",modified following\n";
        var validUpload = api.upload("/api/uploads", "enum_ok_" + suffix + ".csv",
                validCsv.getBytes(StandardCharsets.UTF_8));
        var validPreview = api.post("/api/uploads/"
                + validUpload.json().get("uploadId").asString() + "/preview",
                api.toJson(Map.of("sheetIndex", 0, "productCode", productCode)));
        assertThat(validPreview.ok()).as(validPreview.body()).isTrue();
        assertThat(validPreview.json().get("preview").get("sampleRows").get(0).get("valid").asBoolean())
                .isTrue();

        var deleted = api.delete("/api/catalog/products/" + product.json().get("id").asString());
        assertThat(deleted.ok()).as(deleted.body()).isTrue();
        assertThat(api.delete("/api/catalog/base-products/" + baseId).ok()).isTrue();
    }

    @Test
    void ccsirsShipsSeventyPredefinedFields() {
        String baseId = findBaseId("MW", "CCSIRS");
        JsonNode fields = api.get("/api/catalog/base-products/" + baseId).json().get("fields");
        assertThat(fields).hasSize(70);

        int managed = 0;
        int required = 0;
        for (JsonNode field : fields) {
            if ("MANAGED".equals(field.get("roleCode").asString())) {
                managed++;
                assertThat(field.get("required").asBoolean()).isFalse();
            }
            if (field.get("required").asBoolean()) {
                required++;
            }
        }
        assertThat(managed).isEqualTo(4);
        assertThat(required).isEqualTo(50);
        assertThat(fields.get(0).get("header").asString()).isEqualTo("_TestCaseId");
    }

    @Test
    void importsBaseFieldsFromCsvHeaders() {
        String suffix = suffix();
        var created = api.post("/api/catalog/platforms/CFETS/base-products",
                api.toJson(Map.of("code", "NBIRS_" + suffix, "name", "NB IRS")));
        assertThat(created.ok()).as(created.body()).isTrue();
        String baseId = created.json().get("id").asString();

        var imported = api.post("/api/catalog/base-products/" + baseId + "/fields/from-headers",
                api.toJson(Map.of("headers", List.of("Trade ID", "Trade Date", "Notional", "Trade Date", " "))));
        assertThat(imported.ok()).as(imported.body()).isTrue();
        assertThat(imported.json().get("created")).hasSize(4);

        JsonNode fields = api.get("/api/catalog/base-products/" + baseId).json().get("fields");
        assertThat(fields).hasSize(4);
        for (JsonNode field : fields) {
            assertThat(field.get("valueTypeCode").asString()).isEqualTo("TEXT");
            assertThat(field.get("roleCode").asString()).isEqualTo("BUSINESS");
            assertThat(field.get("required").asBoolean()).isTrue();
        }
        assertThat(fields.get(0).get("header").asString()).isEqualTo("Trade ID");
        assertThat(fields.get(1).get("sortOrder").asInt()).isEqualTo(2);
        // a header repeated inside the file keeps both columns, the second one gets a suffix key
        assertThat(fields.get(3).get("fieldKey").asString()).isEqualTo("tradeDate2");

        var again = api.post("/api/catalog/base-products/" + baseId + "/fields/from-headers",
                api.toJson(Map.of("headers", List.of("Trade ID", "Currency"))));
        assertThat(again.ok()).as(again.body()).isTrue();
        assertThat(again.json().get("created")).hasSize(1);

        assertThat(api.delete("/api/catalog/base-products/" + baseId).ok()).isTrue();
    }

    @Test
    void productFieldsKeepAnIndependentOrder() {
        String suffix = suffix();
        String baseId = findBaseId("MW", "NDS");
        String alpha = addBaseField(baseId, "Alpha Ref", "TEXT", "BUSINESS", 1);
        String beta = addBaseField(baseId, "Beta Amount", "DECIMAL", "BUSINESS", 2);
        String gamma = addBaseField(baseId, "Gamma Date", "DATE", "BUSINESS", 3);

        String productCode = "NDS_" + suffix;
        var product = api.post("/api/catalog/base-products/" + baseId + "/products",
                api.toJson(Map.of("code", productCode, "name", "Order test")));
        assertThat(product.ok()).as(product.body()).isTrue();
        String productId = product.json().get("id").asString();

        JsonNode fields = api.get("/api/catalog/products/" + productId).json().get("fields");
        assertThat(fields).hasSize(3);

        var reordered = api.post("/api/catalog/products/" + productId + "/fields/reorder",
                api.toJson(Map.of("fieldIds", java.util.List.of(
                        fieldIdOf(fields, "gammaDate"), fieldIdOf(fields, "alphaRef"),
                        fieldIdOf(fields, "betaAmount")))));
        assertThat(reordered.ok()).as(reordered.body()).isTrue();
        JsonNode ordered = reordered.json().get("fields");
        assertThat(ordered.get(0).get("fieldKey").asString()).isEqualTo("gammaDate");
        assertThat(ordered.get(0).get("sortOrder").asInt()).isEqualTo(1);
        assertThat(ordered.get(0).get("baseSortOrder").asInt()).isEqualTo(3);
        assertThat(ordered.get(1).get("fieldKey").asString()).isEqualTo("alphaRef");
        assertThat(ordered.get(1).get("sortOrder").asInt()).isEqualTo(2);
        assertThat(ordered.get(1).get("baseSortOrder").asInt()).isEqualTo(1);

        JsonNode baseFields = api.get("/api/catalog/base-products/" + baseId).json().get("fields");
        assertThat(baseFields.get(0).get("fieldKey").asString()).isEqualTo("alphaRef");
        assertThat(baseFields.get(0).get("sortOrder").asInt()).isEqualTo(1);

        assertThat(api.delete("/api/catalog/products/" + productId).ok()).isTrue();
        assertThat(api.delete("/api/catalog/base-products/" + baseId + "/fields/" + alpha).ok()).isTrue();
        assertThat(api.delete("/api/catalog/base-products/" + baseId + "/fields/" + beta).ok()).isTrue();
        assertThat(api.delete("/api/catalog/base-products/" + baseId + "/fields/" + gamma).ok()).isTrue();
    }

    private String findBaseId(String platformCode, String baseCode) {
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

    private String addBaseField(String baseId, String header, String valueType, String role, int sortOrder) {
        var response = api.post("/api/catalog/base-products/" + baseId + "/fields", api.toJson(Map.of(
                "header", header, "valueTypeCode", valueType, "roleCode", role,
                "required", true, "sortOrder", sortOrder)));
        assertThat(response.ok()).as(response.body()).isTrue();
        return response.json().get("id").asString();
    }

    private static String fieldIdOf(JsonNode fields, String fieldKey) {
        for (JsonNode field : fields) {
            if (fieldKey.equals(field.get("fieldKey").asString())) {
                return field.get("id").asString();
            }
        }
        throw new IllegalStateException("Field " + fieldKey + " not found");
    }

    @Test
    void newMetaEntriesAreAppendedAndEnabledByDefault() {
        String suffix = suffix();
        String setCode = "NO_ORDER_" + suffix;
        var set = api.post("/api/meta/sets",
                api.toJson(Map.of("code", setCode, "name", "No order", "description", "appended set")));
        assertThat(set.ok()).as(set.body()).isTrue();
        assertThat(set.json().get("sortOrder").asInt()).isEqualTo(api.get("/api/meta/sets").json().size());

        var first = api.post("/api/meta/sets/" + setCode + "/items",
                api.toJson(Map.of("code", "A", "label", "A")));
        assertThat(first.ok()).as(first.body()).isTrue();
        assertThat(first.json().get("enabled").asBoolean()).isTrue();
        assertThat(first.json().get("sortOrder").asInt()).isEqualTo(1);

        var second = api.post("/api/meta/sets/" + setCode + "/items",
                api.toJson(Map.of("code", "B", "label", "B")));
        assertThat(second.ok()).as(second.body()).isTrue();
        assertThat(second.json().get("sortOrder").asInt()).isEqualTo(2);

        var patched = api.patch("/api/meta/sets/" + setCode + "/items/A",
                api.toJson(Map.of("label", "A2")));
        assertThat(patched.ok()).as(patched.body()).isTrue();
        assertThat(patched.json().get("label").asString()).isEqualTo("A2");
        assertThat(patched.json().get("sortOrder").asInt()).isEqualTo(1);
        assertThat(patched.json().get("enabled").asBoolean()).isTrue();

        assertThat(api.delete("/api/meta/sets/" + setCode).ok()).isTrue();
    }

    @Test
    void metaItemOrderMustBeUnique() {
        String suffix = suffix();
        String setCode = "ORDER_SET_" + suffix;
        assertThat(api.post("/api/meta/sets", api.toJson(Map.of("code", setCode, "name", "Order set")))
                .ok()).isTrue();
        assertThat(api.post("/api/meta/sets/" + setCode + "/items",
                api.toJson(Map.of("code", "A", "label", "A", "sortOrder", 1))).ok()).isTrue();

        var clash = api.post("/api/meta/sets/" + setCode + "/items",
                api.toJson(Map.of("code", "B", "label", "B", "sortOrder", 1)));
        assertThat(clash.status()).isEqualTo(409);
        assertThat(clash.body()).contains("already used");

        assertThat(api.post("/api/meta/sets/" + setCode + "/items",
                api.toJson(Map.of("code", "B", "label", "B", "sortOrder", 2))).ok()).isTrue();

        var moved = api.patch("/api/meta/sets/" + setCode + "/items/B",
                api.toJson(Map.of("label", "B", "sortOrder", 1, "enabled", true)));
        assertThat(moved.status()).isEqualTo(409);

        var kept = api.patch("/api/meta/sets/" + setCode + "/items/B",
                api.toJson(Map.of("label", "B2", "sortOrder", 2, "enabled", true)));
        assertThat(kept.ok()).as(kept.body()).isTrue();
        assertThat(kept.json().get("label").asString()).isEqualTo("B2");

        assertThat(api.delete("/api/meta/sets/" + setCode).ok()).isTrue();
    }

    @Test
    void fieldRemarkKeepsLineBreaks() {
        String baseId = findBaseId("MW", "BSW");
        var created = api.post("/api/catalog/base-products/" + baseId + "/fields", api.toJson(Map.of(
                "header", "Remark Field", "valueTypeCode", "TEXT", "roleCode", "BUSINESS", "required", false,
                "remark", "line one\nline two")));
        assertThat(created.ok()).as(created.body()).isTrue();
        assertThat(created.json().get("remark").asString()).isEqualTo("line one\nline two");

        String fieldId = created.json().get("id").asString();
        assertThat(api.delete("/api/catalog/base-products/" + baseId + "/fields/" + fieldId).ok()).isTrue();
    }

    @Test
    void metaItemsGuardFieldReferences() {
        String suffix = suffix();
        assertThat(api.delete("/api/meta/sets/VALUE_TYPE").status()).isEqualTo(400);
        assertThat(api.delete("/api/meta/sets/FIELD_ROLE/items/BUSINESS").status()).isEqualTo(409);

        String setCode = "TEST_ENUM_" + suffix;
        var set = api.post("/api/meta/sets",
                api.toJson(Map.of("code", setCode, "name", "测试枚举", "sortOrder", 9)));
        assertThat(set.ok()).as(set.body()).isTrue();
        var item = api.post("/api/meta/sets/" + setCode + "/items", api.toJson(Map.of(
                "code", "OPTION_A", "label", "选项 A", "sortOrder", 1)));
        assertThat(item.ok()).as(item.body()).isTrue();

        String valueTypeCode = "TEST_REF_" + suffix;
        var valueType = api.post("/api/meta/sets/VALUE_TYPE/items", api.toJson(Map.of(
                "code", valueTypeCode, "label", "引用枚举", "refSetCode", setCode, "sortOrder", 20)));
        assertThat(valueType.ok()).as(valueType.body()).isTrue();

        var options = api.get("/api/meta/options");
        assertThat(options.ok()).isTrue();
        assertThat(options.body()).contains(valueTypeCode);

        assertThat(api.delete("/api/meta/sets/" + setCode).status()).isEqualTo(409);
        assertThat(api.delete("/api/meta/sets/VALUE_TYPE/items/" + valueTypeCode).ok()).isTrue();
        assertThat(api.delete("/api/meta/sets/" + setCode).ok()).isTrue();
    }
}
