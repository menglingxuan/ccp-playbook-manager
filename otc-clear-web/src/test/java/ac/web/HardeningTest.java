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

/** Guards added after the exploratory test round: rule limits, preview limits and API contracts. */
class HardeningTest extends AbstractIntegrationTest {

    @Test
    void ruleWithNestedQuantifiersIsRejected() {
        String setCode = validationSet();

        Map<String, Object> item = new LinkedHashMap<>();
        item.put("code", "CATASTROPHIC_" + suffix());
        item.put("label", "catastrophic");
        item.put("content", "(.*a){14}");
        item.put("ruleType", "REGEX");
        var response = api.post("/api/meta/sets/" + setCode + "/items", api.toJson(item));

        assertThat(response.status()).isEqualTo(400);
        assertThat(response.body()).contains("nested quantifiers");
    }

    @Test
    void ruleSampleTextIsLimited() {
        String text = "a".repeat(5_000);

        var response = api.post("/api/meta/rules/match",
                api.toJson(Map.of("ruleType", "REGEX", "expression", "a+", "text", text)));

        assertThat(response.ok()).as(response.body()).isTrue();
        assertThat(response.json().get("valid").asBoolean()).isTrue();
        assertThat(response.json().get("message").asString()).contains("limited");
    }

    @Test
    void csvPreviewKeepsTheTotalRowCount() {
        String productCode = productWithFields("MW", "IRS", List.of("tradeId"));
        StringBuilder csv = new StringBuilder("Trade ID\r\n");
        for (int index = 0; index < 420; index++) {
            csv.append("TRD-").append(index).append('-').append(suffix()).append("\r\n");
        }
        var upload = api.upload("/api/uploads", "many.csv", csv.toString().getBytes(StandardCharsets.UTF_8));
        assertThat(upload.ok()).as(upload.body()).isTrue();
        JsonNode sheet = upload.json().get("sheets").get(0);
        assertThat(sheet.get("rowCount").asInt()).isEqualTo(421);

        var preview = api.post("/api/uploads/" + upload.json().get("uploadId").asString() + "/preview",
                api.toJson(Map.of("productCode", productCode, "sampleLimit", 5)));
        assertThat(preview.ok()).as(preview.body()).isTrue();
        JsonNode stats = preview.json().get("preview").get("stats");
        // only the first rows are held in memory, but the reported row count is the real one
        assertThat(stats.get("dataRows").asInt()).isEqualTo(420);
        // the configured sample size is the floor, so asking for fewer returns that floor
        assertThat(preview.json().get("preview").get("sampleRows")).hasSize(20);
    }

    @Test
    void oversizedCellIsRejected() {
        String productCode = productWithFields("MW", "IRS", List.of("tradeId"));
        String cell = "x".repeat(40_000);
        String csv = "Trade ID,Remarks\r\nTRD-" + suffix() + "," + cell + "\r\n";

        // the guarded read happens while the upload is described, so the file is refused up front
        var upload = api.upload("/api/uploads", "huge-cell.csv", csv.getBytes(StandardCharsets.UTF_8));

        assertThat(upload.status()).isEqualTo(400);
        assertThat(upload.body()).contains("limit is");
        assertThat(productCode).isNotBlank();
    }

    @Test
    void bulkUpdateNeedsAnExplicitScope() {
        String productCode = productWithFields("MW", "IRS", List.of("tradeId"));
        String batchId = importOne(productCode);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("fieldKey", "tradeId");
        payload.put("mode", "VALUE");
        payload.put("value", "X");
        var response = api.post("/api/batches/" + batchId + "/cases/bulk-update", api.toJson(payload));

        assertThat(response.status()).isEqualTo(400);
        assertThat(response.body()).contains("scope=BATCH");

        payload.put("scope", "BATCH");
        assertThat(api.post("/api/batches/" + batchId + "/cases/bulk-update", api.toJson(payload)).ok())
                .isTrue();
    }

    @Test
    void settingsUpdateMergesTheSubmittedFields() {
        var original = api.get("/api/settings").json();
        try {
            assertThat(api.put("/api/settings", api.toJson(Map.of("delimiter", ";"))).ok()).isTrue();
            assertThat(api.get("/api/settings").json().get("delimiter").asString()).isEqualTo(";");

            // only the BOM changes, the delimiter stays as it was saved
            assertThat(api.put("/api/settings", api.toJson(Map.of("bom", true))).ok()).isTrue();
            JsonNode merged = api.get("/api/settings").json();
            assertThat(merged.get("delimiter").asString()).isEqualTo(";");
            assertThat(merged.get("bom").asBoolean()).isTrue();
        } finally {
            api.put("/api/settings", api.toJson(Map.of(
                    "delimiter", String.valueOf(original.get("delimiter").asString()),
                    "bom", original.get("bom").asBoolean())));
        }
    }

    private String importOne(String productCode) {
        String csv = "Trade ID\r\nTRD-" + suffix() + "\r\n";
        var upload = api.upload("/api/uploads", "bulk_scope.csv", csv.getBytes(StandardCharsets.UTF_8));
        assertThat(upload.ok()).as(upload.body()).isTrue();
        var imported = api.post("/api/imports", api.toJson(Map.of(
                "uploadId", upload.json().get("uploadId").asString(),
                "sheets", List.of(Map.of("sheetIndex", 0, "productCode", productCode)))));
        assertThat(imported.ok()).as(imported.body()).isTrue();
        return imported.json().get("batches").get(0).get("batchId").asString();
    }

    /** A fresh VALIDATION meta set, so the test does not depend on the seeded rules. */
    private String validationSet() {
        String code = "VAL_" + suffix().toUpperCase();
        Map<String, Object> set = new LinkedHashMap<>();
        set.put("code", code);
        set.put("name", code);
        set.put("type", "VALIDATION");
        var created = api.post("/api/meta/sets", api.toJson(set));
        assertThat(created.ok()).as(created.body()).isTrue();
        return code;
    }

    private static String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private static List<String> listOf(String value) {
        List<String> items = new ArrayList<>();
        items.add(value);
        return items;
    }
}
