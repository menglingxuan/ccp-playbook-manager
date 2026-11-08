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

    private static final List<String> FIELDS = List.of("testCaseId", "remarks", "tradeId", "tradeDate",
            "maturityDate", "notional", "notionalCurrency", "fixedRate", "floatingRate", "direction");

    /** Same columns without the duplicated {@code Rate}; usable without strict matching. */
    private static final List<String> UNIQUE_FIELDS = List.of("testCaseId", "remarks", "tradeId", "tradeDate",
            "maturityDate", "notional", "notionalCurrency", "fixedRate", "direction");

    /** Business columns of {@link #UNIQUE_FIELDS}; the first two entries are managed columns. */
    private static final int UNIQUE_TRADE_COLUMNS = 7;

    private static final String ORDERED_CSV = """
            _TestCaseId,_Remarks,Trade ID,Trade Date,Maturity Date,Notional,Notional Currency,Rate,Rate,Direction
            %s,note,TC-1,2026-01-02,2031-01-02,1000000,USD,0.03,0.04,BUY
            """;

    private static final String SWAPPED_CSV = """
            _TestCaseId,_Remarks,Trade ID,Maturity Date,Trade Date,Notional,Notional Currency,Rate,Rate,Direction
            %s,note,TC-1,2031-01-02,2026-01-02,1000000,USD,0.03,0.04,BUY
            """;

    private static final String MISSING_COLUMN_CSV = """
            _TestCaseId,_Remarks,Trade ID,Trade Date,Maturity Date,Notional,Notional Currency,Rate,Rate
            %s,note,TC-1,2026-01-02,2031-01-02,1000000,USD,0.03,0.04
            """;

    private static final String EXTRA_COLUMN_CSV = """
            _TestCaseId,_Remarks,Trade ID,Trade Date,Maturity Date,Notional,Notional Currency,Rate,Rate,Direction,Notes
            %s,note,TC-1,2026-01-02,2031-01-02,1000000,USD,0.03,0.04,BUY,extra
            """;

    private static final String FOREIGN_COLUMN_CSV = """
            _TestCaseId,_Remarks,Trade ID,Trade Date,Maturity Date,Notional,Notional Currency,Rate,Bogus,Direction
            %s,note,TC-1,2026-01-02,2031-01-02,1000000,USD,0.03,0.04,BUY
            """;

    /** Single {@code Rate} column, so the file can be imported by name without strict matching. */
    private static final String UNIQUE_CSV = """
            Trade ID,Trade Date,Maturity Date,Notional,Notional Currency,Rate,Direction
            %s,2026-01-02,2031-01-02,1000000,USD,0.03,BUY
            """;

    private static final String DUPLICATED_RATE_CSV = """
            Trade ID,Trade Date,Maturity Date,Notional,Notional Currency,Rate,Rate,Direction
            %s,2026-01-02,2031-01-02,1000000,USD,0.03,0.04,BUY
            """;

    @Test
    void strictModeBlocksImportsThatIgnoreTheFieldOrder() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String productCode = productWithFields("MW", "IRS", FIELDS);
        String productId = productIdOf(productCode);

        var enabled = api.patch("/api/catalog/products/" + productId,
                api.toJson(Map.of("strictMatch", true)));
        assertThat(enabled.ok()).as(enabled.body()).isTrue();
        assertThat(enabled.json().get("product").get("strictMatch").asBoolean()).isTrue();
        assertThat(enabled.json().get("product").get("definitionValid").asBoolean()).isTrue();

        // columns beyond the declared field list stay managed columns
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

        // switching the duplicated definition back to name matching makes it invalid
        var disabled = api.patch("/api/catalog/products/" + productId,
                api.toJson(Map.of("strictMatch", false)));
        assertThat(disabled.ok()).as(disabled.body()).isTrue();
        assertThat(disabled.json().get("product").get("strictMatch").asBoolean()).isFalse();
        assertThat(disabled.json().get("product").get("definitionValid").asBoolean()).isFalse();
    }

    @Test
    void nonStrictProductWithDuplicateColumnsCannotBeImported() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String productCode = productWithFields("MW", "IRS", FIELDS);

        var response = api.post("/api/uploads/" + upload(ORDERED_CSV.formatted("DUP-" + suffix), "dup.csv")
                + "/preview", api.toJson(Map.of("sheetIndex", 0, "productCode", productCode)));

        assertThat(response.status()).isEqualTo(409);
        assertThat(response.json().get("code").asString()).isEqualTo("PRODUCT_DEFINITION_INVALID");
        assertThat(response.body()).contains("Rate");
    }

    @Test
    void strictModeRejectsForeignColumnsInsideTheTradeBlock() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String productCode = productWithFields("MW", "IRS", FIELDS);
        assertThat(api.patch("/api/catalog/products/" + productIdOf(productCode),
                api.toJson(Map.of("strictMatch", true))).ok()).isTrue();

        var preview = preview(productCode, FOREIGN_COLUMN_CSV.formatted("CON-" + suffix), "foreign.csv");

        assertThat(preview.json().get("preview").get("valid").asBoolean()).isFalse();
        assertThat(preview.body()).contains("TRADE_BLOCK_NOT_CONTIGUOUS");
    }

    @Test
    void nonStrictModeWarnsAboutDuplicatedSourceColumns() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String productCode = productWithFields("MW", "IRS", UNIQUE_FIELDS);

        var preview = preview(productCode, DUPLICATED_RATE_CSV.formatted("SRC-" + suffix), "dup-src.csv");

        JsonNode sheet = preview.json().get("preview");
        assertThat(sheet.get("valid").asBoolean()).isTrue();
        assertThat(preview.body()).contains("DUPLICATE_SOURCE_HEADER");
        assertThat(sheet.get("stats").get("mappedTradeColumns").asInt()).isEqualTo(UNIQUE_TRADE_COLUMNS);
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
        assertThat(created.json().get("fieldCount").asInt()).isEqualTo(22);
        assertThat(created.json().get("strictMatch").asBoolean()).isFalse();

        String productId = created.json().get("id").asString();
        JsonNode fields = api.get("/api/catalog/products/" + productId).json().get("fields");
        assertThat(fields).hasSize(22);
        for (JsonNode field : fields) {
            if (!"BUSINESS".equals(field.get("roleCode").asString())) {
                continue;
            }
            assertThat(field.get("required").asBoolean())
                    .as("business field %s must be required", field.get("fieldKey").asString())
                    .isTrue();
        }

        JsonNode optional = null;
        for (JsonNode field : fields) {
            if ("BUSINESS".equals(field.get("roleCode").asString())
                    && !field.get("requiredFromBase").asBoolean()) {
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

    @Test
    void reorderedProductFieldsStillMatchTheFileByName() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String productCode = productWithFields("MW", "IRS", UNIQUE_FIELDS);
        String productId = productIdOf(productCode);

        JsonNode fields = api.get("/api/catalog/products/" + productId).json().get("fields");
        String tradeIdField = fieldIdOf(fields, "tradeId");
        String tradeDateField = fieldIdOf(fields, "tradeDate");
        List<String> order = new java.util.ArrayList<>();
        order.add(tradeDateField);
        order.add(tradeIdField);
        for (JsonNode field : fields) {
            String id = field.get("id").asString();
            if (!order.contains(id)) {
                order.add(id);
            }
        }
        var reordered = api.post("/api/catalog/products/" + productId + "/fields/reorder",
                api.toJson(Map.of("fieldIds", order)));
        assertThat(reordered.ok()).as(reordered.body()).isTrue();

        var preview = preview(productCode, UNIQUE_CSV.formatted("ORD-" + suffix), "ordered2.csv");
        JsonNode sheet = preview.json().get("preview");
        assertThat(sheet.get("valid").asBoolean()).isTrue();
        assertThat(sheet.get("stats").get("mappedTradeColumns").asInt()).isEqualTo(UNIQUE_TRADE_COLUMNS);
        assertThat(preview.body()).doesNotContain("MISSING_REQUIRED_FIELD")
                .doesNotContain("UNMAPPED_TRADE_COLUMN");
    }

    /** Preview result keeping the upload id next to the parsed body. */
    private record Preview(String uploadId, JsonNode json, String body) {
    }

    @Test
    void fieldsDroppedByTheProductStayOutsideTheMapping() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String productCode = productWithFields("MW", "IRS",
                List.of("tradeId", "tradeDate", "maturityDate", "notional", "fixedRate", "direction"));
        String csv = """
                Trade ID,Trade Date,Maturity Date,Notional,Rate,Counterparty,Portfolio,Direction
                DROP-%s,2026-01-02,2031-01-02,1000000,0.03,CP-1,PF-01,BUY
                """.formatted(suffix);

        var preview = preview(productCode, csv, "dropped.csv");
        JsonNode sheet = preview.json().get("preview");
        assertThat(sheet.get("valid").asBoolean()).isTrue();
        assertThat(sheet.get("layout").get("missingFields")).isEmpty();
        assertThat(sheet.get("stats").get("mappedTradeColumns").asInt()).isEqualTo(6);
        assertThat(preview.body()).doesNotContain("MISSING_REQUIRED_FIELD");
        assertThat(preview.body()).contains("UNMAPPED_TRADE_COLUMN")
                .contains("TRADE_BLOCK_NOT_CONTIGUOUS");
    }

    /** Uploads the CSV and returns the upload id. */
    private String upload(String csv, String fileName) {
        var upload = api.upload("/api/uploads", fileName, csv.getBytes(StandardCharsets.UTF_8));
        assertThat(upload.ok()).as(upload.body()).isTrue();
        return upload.json().get("uploadId").asString();
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
