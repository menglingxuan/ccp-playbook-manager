package ac.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** Case comparison: read-only diff of two cases, nothing is stored. */
class CaseCompareTest extends AbstractIntegrationTest {

    private static final String HEADER =
            "_TestCaseId,_Remarks,Trade ID,Trade Date,Maturity Date,Notional,Notional Currency,Rate,Direction";

    private static final List<String> WIDE_FIELDS = List.of("testCaseId", "remarks", "tradeId", "tradeDate",
            "maturityDate", "notional", "notionalCurrency", "fixedRate", "direction");

    private static final List<String> NARROW_FIELDS = List.of("testCaseId", "remarks", "tradeId", "tradeDate");

    @Test
    void comparesTwoCasesOfOneBatch() {
        String product = productWithFields("MW", "IRS", WIDE_FIELDS);
        String suffix = UUID.randomUUID().toString().substring(0, 6);
        String batchId = importBatch(product, "case_cmp_" + suffix, """
                TC-1,first,CC-%s-1,2026-01-02,2031-01-02,1000,USD,0.03,BUY
                TC-2,second,CC-%s-2,2026-01-02,2031-01-02,1000,USD,0.03,BUY
                """.formatted(suffix, suffix));
        List<String> caseIds = caseIds(batchId);
        assertThat(caseIds).hasSize(2);

        JsonNode result = compare(caseIds.get(0), caseIds.get(1));
        assertThat(result.get("left").get("testCaseId").asString()).isEqualTo("TC-1");
        assertThat(result.get("right").get("testCaseId").asString()).isEqualTo("TC-2");
        assertThat(result.get("left").get("batchName").asString()).isNotBlank();
        // only the case id, the remarks and the trade id differ
        assertThat(result.get("summary").get("fieldsCompared").asInt()).isEqualTo(WIDE_FIELDS.size());
        assertThat(result.get("summary").get("fieldsDifferent").asInt()).isEqualTo(3);
        assertThat(result.get("summary").get("fieldsLeftOnly").asInt()).isZero();
        assertThat(result.get("summary").get("fieldsRightOnly").asInt()).isZero();

        Map<String, String> statuses = statuses(result);
        assertThat(statuses).containsEntry("tradeId", "DIFFERENT").containsEntry("notional", "SAME");
        JsonNode tradeId = fieldOf(result, "tradeId");
        assertThat(tradeId.get("leftValue").asString()).isEqualTo("CC-" + suffix + "-1");
        assertThat(tradeId.get("rightValue").asString()).isEqualTo("CC-" + suffix + "-2");
        assertThat(fieldOf(result, "_Remarks").get("scope").asString()).isEqualTo("MANAGED");
    }

    @Test
    void comparesCasesOfDifferentProductsAcrossBatches() {
        String wide = productWithFields("MW", "IRS", WIDE_FIELDS);
        String narrow = productWithFields("MW", "IRS", NARROW_FIELDS);
        String suffix = UUID.randomUUID().toString().substring(0, 6);
        String leftBatch = importBatch(wide, "case_cmp_wide_" + suffix, """
                TC-1,first,CW-%s-1,2026-01-02,2031-01-02,1000,USD,0.03,BUY
                """.formatted(suffix));
        String rightBatch = importBatch(narrow, "case_cmp_narrow_" + suffix, """
                TC-1,first,CW-%s-1,2026-01-02
                """.formatted(suffix));

        JsonNode result = compare(caseIds(leftBatch).get(0), caseIds(rightBatch).get(0));
        assertThat(result.get("summary").get("fieldsLeftOnly").asInt()).isEqualTo(WIDE_FIELDS.size() - NARROW_FIELDS.size());
        assertThat(result.get("summary").get("fieldsRightOnly").asInt()).isZero();
        Map<String, String> statuses = statuses(result);
        assertThat(statuses).containsEntry("notional", "LEFT_ONLY").containsEntry("tradeId", "SAME");
        assertThat(fieldOf(result, "notional").get("rightValue").isNull()).isTrue();
        // different batches, same trade: the headers carry both batch names
        assertThat(result.get("left").get("batchId").asString()).isEqualTo(leftBatch);
        assertThat(result.get("right").get("batchId").asString()).isEqualTo(rightBatch);
    }

    @Test
    void compareRejectsBadInput() {
        String product = productWithFields("MW", "IRS", WIDE_FIELDS);
        String suffix = UUID.randomUUID().toString().substring(0, 6);
        String batchId = importBatch(product, "case_cmp_bad_" + suffix, """
                TC-1,first,CB-%s-1,2026-01-02,2031-01-02,1000,USD,0.03,BUY
                """.formatted(suffix));
        String caseId = caseIds(batchId).get(0);

        assertThat(api.post("/api/cases/" + caseId + "/compare",
                api.toJson(Map.of("rightCaseId", caseId))).status())
                .as("comparing a case with itself").isEqualTo(400);
        assertThat(api.post("/api/cases/" + caseId + "/compare",
                api.toJson(Map.of("rightCaseId", "no-such-case"))).status()).isEqualTo(404);
        assertThat(api.post("/api/cases/" + caseId + "/compare", api.toJson(Map.of())).status())
                .isEqualTo(400);
    }

    private JsonNode compare(String leftId, String rightId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("rightCaseId", rightId);
        var response = api.post("/api/cases/" + leftId + "/compare", api.toJson(payload));
        assertThat(response.ok()).as(response.body()).isTrue();
        return response.json();
    }

    private JsonNode fieldOf(JsonNode result, String fieldKey) {
        for (JsonNode field : result.get("fields")) {
            if (fieldKey.equals(field.get("fieldKey").asString())) {
                return field;
            }
        }
        throw new IllegalStateException("Field " + fieldKey + " is missing from the result");
    }

    private Map<String, String> statuses(JsonNode result) {
        Map<String, String> statuses = new LinkedHashMap<>();
        for (JsonNode field : result.get("fields")) {
            statuses.put(field.get("fieldKey").asString(), field.get("status").asString());
        }
        return statuses;
    }

    private List<String> caseIds(String batchId) {
        var response = api.get("/api/batches/" + batchId + "/cases?size=50");
        assertThat(response.ok()).as(response.body()).isTrue();
        return response.json().get("items").valueStream()
                .map(item -> item.get("id").asString())
                .toList();
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
