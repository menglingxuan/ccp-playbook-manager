package ac.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** Strict matching switch, unique field order and the required business fields of products. */
class ProductStrictModeTest extends AbstractIntegrationTest {

    private static final List<String> FIELDS = List.of("tradeId", "tradeDate", "maturityDate", "notional",
            "notionalCurrency", "fixedRate", "floatingRate", "direction");

    private static final String ORDERED_CSV = """
            Trade ID,Trade Date,Maturity Date,Notional,Notional Currency,Rate,Rate,Direction
            %s,2026-01-02,2031-01-02,1000000,USD,0.03,0.04,BUY
            """;

    private static final String SWAPPED_CSV = """
            Trade ID,Maturity Date,Trade Date,Notional,Notional Currency,Rate,Rate,Direction
            %s,2031-01-02,2026-01-02,1000000,USD,0.03,0.04,BUY
            """;

    private static final String MISSING_COLUMN_CSV = """
            Trade ID,Trade Date,Maturity Date,Notional,Notional Currency,Rate,Rate
            %s,2026-01-02,2031-01-02,1000000,USD,0.03,0.04
            """;

    private static final String EXTRA_COLUMN_CSV = """
            Trade ID,Trade Date,Maturity Date,Notional,Notional Currency,Rate,Rate,Direction,Notes
            %s,2026-01-02,2031-01-02,1000000,USD,0.03,0.04,BUY,extra
            """;

    @Test
    void strictModeBlocksImportsThatIgnoreTheFieldOrder() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String productCode = productWithFields("MW", "IRS", FIELDS);
        String productId = productIdOf(productCode);

        var lenient = preview(productCode, EXTRA_COLUMN_CSV.formatted("STR-" + suffix), "extra.csv");
        assertThat(lenient.json().get("preview").get("valid").asBoolean()).isTrue();

        var enabled = api.patch("/api/catalog/products/" + productId,
                api.toJson(Map.of("strictMatch", true)));
        assertThat(enabled.ok()).as(enabled.body()).isTrue();
        assertThat(enabled.json().get("product").get("strictMatch").asBoolean()).isTrue();

        var extraColumn = preview(productCode, EXTRA_COLUMN_CSV.formatted("STR-" + suffix), "extra2.csv");
        assertThat(extraColumn.json().get("preview").get("valid").asBoolean()).isTrue();

        var swapped = preview(productCode, SWAPPED_CSV.formatted("STR-" + suffix), "swapped.csv");
        assertThat(swapped.json().get("preview").get("valid").asBoolean()).isFalse();
        assertThat(swapped.body()).contains("STRICT_ORDER_MISMATCH").contains("should be 'Trade Date'");

        var blocked = api.post("/api/imports", api.toJson(Map.of(
                "uploadId", swapped.uploadId(),
                "force", false,
                "sheets", List.of(Map.of("sheetIndex", 0, "productCode", productCode)))));
        assertThat(blocked.ok()).as(blocked.body()).isTrue();
        assertThat(blocked.json().get("status").asString()).isEqualTo("FAILED");
        assertThat(blocked.json().get("batchCount").asInt()).isZero();

        var ordered = preview(productCode, ORDERED_CSV.formatted("STR-" + suffix), "ordered.csv");
        assertThat(ordered.json().get("preview").get("valid").asBoolean()).isTrue();
        var accepted = api.post("/api/imports", api.toJson(Map.of(
                "uploadId", ordered.uploadId(),
                "force", false,
                "sheets", List.of(Map.of("sheetIndex", 0, "productCode", productCode)))));
        assertThat(accepted.json().get("status").asString()).isEqualTo("SUCCEEDED");

        var disabled = api.patch("/api/catalog/products/" + productId,
                api.toJson(Map.of("strictMatch", false)));
        assertThat(disabled.ok()).as(disabled.body()).isTrue();
        assertThat(disabled.json().get("product").get("strictMatch").asBoolean()).isFalse();
    }

    @Test
    void strictModeRejectsMissingColumns() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String productCode = productWithFields("MW", "IRS", FIELDS);
        String productId = productIdOf(productCode);
        assertThat(api.patch("/api/catalog/products/" + productId,
                api.toJson(Map.of("strictMatch", true))).ok()).isTrue();

        var preview = preview(productCode, MISSING_COLUMN_CSV.formatted("STR-" + suffix), "missing.csv");
        assertThat(preview.json().get("preview").get("valid").asBoolean()).isFalse();
        assertThat(preview.body()).contains("STRICT_ORDER_MISMATCH");
    }

    @Test
    void productInheritsEveryBaseFieldAndRequiresTheBusinessOnes() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String baseId = baseIdOf("MW", "OIS");
        String productCode = "OIS_FULL_" + suffix;
        var created = api.post("/api/catalog/base-products/" + baseId + "/products",
                api.toJson(Map.of("code", productCode, "name", "Full inheritance")));
        assertThat(created.ok()).as(created.body()).isTrue();
        assertThat(created.json().get("fieldCount").asInt()).isEqualTo(20);
        assertThat(created.json().get("strictMatch").asBoolean()).isFalse();

        String productId = created.json().get("id").asString();
        JsonNode fields = api.get("/api/catalog/products/" + productId).json().get("fields");
        assertThat(fields).hasSize(20);
        for (JsonNode field : fields) {
            assertThat(field.get("required").asBoolean())
                    .as("business field %s must be required", field.get("fieldKey").asString())
                    .isTrue();
        }

        JsonNode optional = null;
        for (JsonNode field : fields) {
            if (!field.get("requiredFromBase").asBoolean()) {
                optional = field;
                break;
            }
        }
        assertThat(optional).isNotNull();
        int currentOrder = optional.get("sortOrder").asInt();
        var relaxed = api.patch("/api/catalog/products/" + productId + "/fields/" + optional.get("id").asString(),
                api.toJson(Map.of("required", false, "sortOrder", currentOrder, "tags", List.of(), "remark", "")));
        assertThat(relaxed.ok()).as(relaxed.body()).isTrue();
        assertThat(relaxed.json().get("required").asBoolean()).isTrue();
    }

    @Test
    void fieldOrderMustBeUnique() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String baseId = baseIdOf("CFETS", "NDS");
        var first = api.post("/api/catalog/base-products/" + baseId + "/fields", api.toJson(Map.of(
                "header", "Order A", "valueTypeCode", "TEXT", "roleCode", "BUSINESS", "required", true,
                "sortOrder", 1)));
        assertThat(first.ok()).as(first.body()).isTrue();
        var second = api.post("/api/catalog/base-products/" + baseId + "/fields", api.toJson(Map.of(
                "header", "Order B", "valueTypeCode", "TEXT", "roleCode", "BUSINESS", "required", true,
                "sortOrder", 2)));
        assertThat(second.ok()).as(second.body()).isTrue();
        String firstId = first.json().get("id").asString();
        String secondId = second.json().get("id").asString();

        var clash = api.post("/api/catalog/base-products/" + baseId + "/fields", api.toJson(Map.of(
                "header", "Order C", "valueTypeCode", "TEXT", "roleCode", "BUSINESS", "required", true,
                "sortOrder", 2)));
        assertThat(clash.status()).isEqualTo(409);
        assertThat(clash.body()).contains("already used");

        var moved = api.patch("/api/catalog/base-products/" + baseId + "/fields/" + firstId, api.toJson(Map.of(
                "header", "Order A", "valueTypeCode", "TEXT", "roleCode", "BUSINESS", "required", true,
                "sortOrder", 2)));
        assertThat(moved.status()).isEqualTo(409);

        String productCode = "NDS_ORDER_" + suffix;
        var product = api.post("/api/catalog/base-products/" + baseId + "/products",
                api.toJson(Map.of("code", productCode, "name", "Order rules")));
        assertThat(product.ok()).as(product.body()).isTrue();
        String productId = product.json().get("id").asString();
        JsonNode fields = api.get("/api/catalog/products/" + productId).json().get("fields");

        var productClash = api.patch("/api/catalog/products/" + productId + "/fields/"
                + fieldIdOf(fields, "orderB"), api.toJson(Map.of(
                "required", true, "sortOrder", 1, "tags", List.of(), "remark", "")));
        assertThat(productClash.status()).isEqualTo(409);

        assertThat(api.delete("/api/catalog/products/" + productId).ok()).isTrue();
        assertThat(api.delete("/api/catalog/base-products/" + baseId + "/fields/" + firstId).ok()).isTrue();
        assertThat(api.delete("/api/catalog/base-products/" + baseId + "/fields/" + secondId).ok()).isTrue();
    }

    /** Preview result keeping the upload id next to the parsed body. */
    private record Preview(String uploadId, JsonNode json, String body) {
    }

    private Preview preview(String productCode, String csv, String fileName) {
        var upload = api.upload("/api/uploads", fileName, csv.getBytes(StandardCharsets.UTF_8));
        assertThat(upload.ok()).as(upload.body()).isTrue();
        String uploadId = upload.json().get("uploadId").asString();
        var response = api.post("/api/uploads/" + uploadId + "/preview",
                api.toJson(Map.of("sheetIndex", 0, "productCode", productCode)));
        assertThat(response.ok()).as(response.body()).isTrue();
        return new Preview(uploadId, response.json(), response.body());
    }

    private String baseIdOf(String platformCode, String baseCode) {
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

    private static String fieldIdOf(JsonNode fields, String fieldKey) {
        for (JsonNode field : fields) {
            if (fieldKey.equals(field.get("fieldKey").asString())) {
                return field.get("id").asString();
            }
        }
        throw new IllegalStateException("Field " + fieldKey + " not found");
    }
}
