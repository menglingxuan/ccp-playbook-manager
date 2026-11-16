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

/** Batch comparison: read-only diff of two batches, nothing is stored. */
class BatchCompareTest extends AbstractIntegrationTest {

    private static final String HEADER =
            "_TestCaseId,_Remarks,Trade ID,Trade Date,Maturity Date,Notional,Notional Currency,Rate,Direction";

    private static final List<String> KEEP_FIELDS = List.of("testCaseId", "remarks", "tradeId", "tradeDate",
            "maturityDate", "notional", "notionalCurrency", "fixedRate", "direction");

    @Test
    void comparesFieldNamesAndValues() {
        String product = productWithFields("MW", "IRS", KEEP_FIELDS);
        String suffix = UUID.randomUUID().toString().substring(0, 6);
        String left = importBatch(product, "cmp_left_" + suffix, """
                TC-1,first,TL-%s-1,2026-01-02,2031-01-02,1000,USD,0.03,BUY
                TC-2,second,TL-%s-2,2026-02-03,2031-02-03,2000,EUR,0.02,SELL
                """.formatted(suffix, suffix));
        // same trade ids, the second notional differs
        String right = importBatch(product, "cmp_right_" + suffix, """
                TC-1,first,TL-%s-1,2026-01-02,2031-01-02,1000,USD,0.03,BUY
                TC-2,second,TL-%s-2,2026-02-03,2031-02-03,9999,EUR,0.02,SELL
                """.formatted(suffix, suffix));

        JsonNode values = compare(left, right, "VALUES", "KEY", false);
        assertThat(values.get("mode").asString()).isEqualTo("VALUES");
        assertThat(values.get("align").asString()).isEqualTo("KEY");
        assertThat(values.get("truncated").asBoolean()).isFalse();
        // same product on both sides: identical field lists, no field differences
        assertThat(values.get("summary").get("fieldsDifferent").asInt()).isZero();
        assertThat(values.get("summary").get("fieldsCompared").asInt()).isPositive();
        assertThat(values.get("cases")).hasSize(2);
        assertThat(values.get("summary").get("casesDifferent").asInt()).isEqualTo(1);
        assertThat(values.get("summary").get("casesSame").asInt()).isEqualTo(1);

        JsonNode differing = null;
        for (JsonNode row : values.get("cases")) {
            if ("DIFFERENT".equals(row.get("status").asString())) {
                differing = row;
            }
        }
        assertThat(differing).isNotNull();
        assertThat(differing.get("key").asString()).isEqualTo("TL-" + suffix + "-2");
        assertThat(differing.get("values")).hasSize(1);
        JsonNode value = differing.get("values").get(0);
        assertThat(value.get("fieldKey").asString()).isEqualTo("notional");
        assertThat(value.get("leftValue").asString()).isEqualTo("2000");
        assertThat(value.get("rightValue").asString()).isEqualTo("9999");

        // field names only: no case rows are compared
        JsonNode fields = compare(left, right, "FIELDS", "KEY", false);
        assertThat(fields.get("mode").asString()).isEqualTo("FIELDS");
        assertThat(fields.get("cases")).isEmpty();
        assertThat(fields.get("fields")).hasSize(9);
    }

    @Test
    void compareOrderReportsMovedRows() {
        String product = productWithFields("MW", "IRS", KEEP_FIELDS);
        String suffix = UUID.randomUUID().toString().substring(0, 6);
        String left = importBatch(product, "cmp_order_a_" + suffix, """
                TC-1,first,TO-%s-1,2026-01-02,2031-01-02,1000,USD,0.03,BUY
                TC-2,second,TO-%s-2,2026-02-03,2031-02-03,2000,EUR,0.02,SELL
                """.formatted(suffix, suffix));
        // the same two trades in the opposite order
        String right = importBatch(product, "cmp_order_b_" + suffix, """
                TC-2,second,TO-%s-2,2026-02-03,2031-02-03,2000,EUR,0.02,SELL
                TC-1,first,TO-%s-1,2026-01-02,2031-01-02,1000,USD,0.03,BUY
                """.formatted(suffix, suffix));

        JsonNode withoutOrder = compare(left, right, "VALUES", "KEY", false);
        assertThat(withoutOrder.get("summary").get("casesSame").asInt()).isEqualTo(2);
        assertThat(withoutOrder.get("summary").get("orderDifferent").asBoolean()).isFalse();

        JsonNode withOrder = compare(left, right, "VALUES", "KEY", true);
        assertThat(withOrder.get("compareOrder").asBoolean()).isTrue();
        assertThat(withOrder.get("summary").get("orderDifferent").asBoolean()).isTrue();
        List<String> statuses = new ArrayList<>();
        withOrder.get("cases").forEach(row -> statuses.add(row.get("status").asString()));
        assertThat(statuses).contains("MOVED");
    }

    @Test
    void comparesManagedColumnsWithExactText() {
        String product = productWithFields("MW", "IRS", KEEP_FIELDS);
        String suffix = UUID.randomUUID().toString().substring(0, 6);
        String left = importBatch(product, "cmp_managed_a_" + suffix, """
                TC-1,first,TM-%s-1,2026-01-02,2031-01-02,1000,USD,0.03,BUY
                """.formatted(suffix));
        // same trade, but the managed columns and the written decimal scale differ
        String right = importBatch(product, "cmp_managed_b_" + suffix, """
                TC-9,second,TM-%s-1,2026-01-02,2031-01-02,1000.00,USD,0.0300,BUY
                """.formatted(suffix));

        JsonNode result = compare(left, right, "VALUES", "KEY", false);
        assertThat(result.get("cases")).hasSize(1);
        JsonNode row = result.get("cases").get(0);
        assertThat(row.get("status").asString()).isEqualTo("DIFFERENT");
        Map<String, JsonNode> values = new LinkedHashMap<>();
        row.get("values").forEach(value -> values.put(value.get("fieldKey").asString(), value));
        // managed columns live in managed_json and take part in the comparison
        assertThat(values).containsKeys("_TestCaseId", "_Remarks");
        assertThat(values.get("_Remarks").get("leftValue").asString()).isEqualTo("first");
        assertThat(values.get("_Remarks").get("rightValue").asString()).isEqualTo("second");
        assertThat(values.get("_TestCaseId").get("leftValue").asString()).isEqualTo("TC-1");
        // values are compared as exact text, so a different decimal scale is a difference
        assertThat(values).containsKeys("notional", "fixedRate");
        assertThat(values.get("notional").get("leftValue").asString()).isEqualTo("1000");
        assertThat(values.get("notional").get("rightValue").asString()).isEqualTo("1000.00");
    }

    @Test
    void compareRejectsBadInput() {
        String product = productWithFields("MW", "IRS", KEEP_FIELDS);
        String suffix = UUID.randomUUID().toString().substring(0, 6);
        String batchId = importBatch(product, "cmp_bad_a_" + suffix, """
                TC-1,first,TB-%s-1,2026-01-02,2031-01-02,1000,USD,0.03,BUY
                """.formatted(suffix));
        String otherId = importBatch(product, "cmp_bad_b_" + suffix, """
                TC-2,second,TB-%s-2,2026-02-03,2031-02-03,2000,EUR,0.02,SELL
                """.formatted(suffix));

        assertThat(api.post("/api/batches/" + batchId + "/compare",
                api.toJson(Map.of("rightBatchId", batchId))).status())
                .as("comparing a batch with itself").isEqualTo(400);
        assertThat(api.post("/api/batches/" + batchId + "/compare",
                api.toJson(Map.of("rightBatchId", "no-such-batch"))).status()).isEqualTo(404);
        assertThat(api.post("/api/batches/" + batchId + "/compare",
                api.toJson(Map.of("rightBatchId", otherId, "mode", "NOPE"))).status()).isEqualTo(400);
        assertThat(api.post("/api/batches/" + batchId + "/compare",
                api.toJson(Map.of("rightBatchId", otherId, "align", "NOPE"))).status()).isEqualTo(400);
        assertThat(api.post("/api/batches/" + batchId + "/compare",
                api.toJson(Map.of())).status()).isEqualTo(400);
    }

    private JsonNode compare(String left, String right, String mode, String align, boolean compareOrder) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("rightBatchId", right);
        payload.put("mode", mode);
        payload.put("align", align);
        payload.put("compareOrder", compareOrder);
        var response = api.post("/api/batches/" + left + "/compare", api.toJson(payload));
        assertThat(response.ok()).as(response.body()).isTrue();
        return response.json();
    }

    private String importBatch(String productCode, String fileNamePrefix, String rows) {
        String csv = HEADER + "\n" + rows;
        var upload = api.upload("/api/uploads", fileNamePrefix + ".csv", csv.getBytes(StandardCharsets.UTF_8));
        assertThat(upload.ok()).as(upload.body()).isTrue();
        var imported = api.post("/api/imports", api.toJson(Map.of(
                "uploadId", upload.json().get("uploadId").asString(), "force", false,
                "sheets", List.of(Map.of("sheetIndex", 0, "productCode", productCode)))));
        assertThat(imported.ok()).as(imported.body()).isTrue();
        return imported.json().get("batches").get(0).get("batchId").asString();
    }
}
