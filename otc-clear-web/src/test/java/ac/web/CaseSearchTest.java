package ac.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** Global case search: free text, plus raw case, batch and task ids. */
class CaseSearchTest extends AbstractIntegrationTest {

    private static final String HEADER =
            "_TestCaseId,_Remarks,Trade ID,Trade Date,Maturity Date,Notional,Notional Currency,Rate,Direction";

    private static final List<String> KEEP_FIELDS = List.of("testCaseId", "remarks", "tradeId", "tradeDate",
            "maturityDate", "notional", "notionalCurrency", "fixedRate", "direction");

    @Test
    void freeTextFindsCasesOfEveryBatch() {
        String product = productWithFields("MW", "IRS", KEEP_FIELDS);
        String suffix = UUID.randomUUID().toString().substring(0, 6);
        String first = importBatch(product, "search_a_" + suffix, """
                TC-1,first,SF-%s-1,2026-01-02,2031-01-02,1000,USD,0.03,BUY
                """.formatted(suffix));
        String second = importBatch(product, "search_b_" + suffix, """
                TC-2,second,SF-%s-2,2026-01-02,2031-01-02,2000,USD,0.03,SELL
                """.formatted(suffix));

        JsonNode page = search("SF-" + suffix);
        assertThat(page.get("match").asString()).isEqualTo("TEXT");
        assertThat(page.get("total").asInt()).isEqualTo(2);
        // every hit is labelled with its batch and task
        for (JsonNode item : page.get("items")) {
            assertThat(item.get("batchId").asString()).isIn(first, second);
            assertThat(item.get("batchName").asString()).isNotBlank();
            assertThat(item.get("taskId").asString()).isNotBlank();
            assertThat(item.get("taskName").asString()).isNotBlank();
            assertThat(item.get("productName").asString()).isEqualTo(product);
        }
    }

    @Test
    void rawIdsResolveToTheirScope() {
        String product = productWithFields("MW", "IRS", KEEP_FIELDS);
        String suffix = UUID.randomUUID().toString().substring(0, 6);
        String batchId = importBatch(product, "search_id_" + suffix, """
                TC-1,first,SI-%s-1,2026-01-02,2031-01-02,1000,USD,0.03,BUY
                TC-2,second,SI-%s-2,2026-01-03,2031-01-02,2000,USD,0.04,SELL
                """.formatted(suffix, suffix));
        JsonNode cases = api.get("/api/batches/" + batchId + "/cases?size=10").json().get("items");
        String caseId = cases.get(0).get("id").asString();
        String taskId = api.get("/api/batches/" + batchId).json().get("taskId").asString();

        JsonNode byCase = search(caseId);
        assertThat(byCase.get("match").asString()).isEqualTo("CASE");
        assertThat(byCase.get("total").asInt()).isEqualTo(1);
        assertThat(byCase.get("items").get(0).get("id").asString()).isEqualTo(caseId);

        JsonNode byBatch = search(batchId);
        assertThat(byBatch.get("match").asString()).isEqualTo("BATCH");
        assertThat(byBatch.get("total").asInt()).isEqualTo(2);
        assertThat(byBatch.get("items").get(0).get("batchName").asString()).isNotBlank();

        JsonNode byTask = search(taskId);
        assertThat(byTask.get("match").asString()).isEqualTo("TASK");
        assertThat(byTask.get("total").asInt()).isGreaterThanOrEqualTo(2);
        for (JsonNode item : byTask.get("items")) {
            assertThat(item.get("taskId").asString()).isEqualTo(taskId);
        }

        JsonNode unknown = search("11111111-2222-3333-4444-555555555555");
        assertThat(unknown.get("match").asString()).isEqualTo("NONE");
        assertThat(unknown.get("total").asInt()).isZero();
        assertThat(unknown.get("items")).isEmpty();
    }

    @Test
    void searchPaginatesAndKeepsTheHitCount() {
        String product = productWithFields("MW", "IRS", KEEP_FIELDS);
        String suffix = UUID.randomUUID().toString().substring(0, 6);
        importBatch(product, "search_page_" + suffix, """
                TC-1,first,SP-%s-1,2026-01-02,2031-01-02,1000,USD,0.03,BUY
                TC-2,second,SP-%s-2,2026-01-02,2031-01-02,2000,USD,0.03,SELL
                TC-3,third,SP-%s-3,2026-01-02,2031-01-02,3000,USD,0.03,BUY
                """.formatted(suffix, suffix, suffix));

        Map<String, String> params = new LinkedHashMap<>();
        params.put("q", "SP-" + suffix);
        params.put("page", "1");
        params.put("size", "2");
        JsonNode firstPage = api.get("/api/cases/search?" + form(params)).json();
        assertThat(firstPage.get("total").asInt()).isEqualTo(3);
        assertThat(firstPage.get("totalPages").asInt()).isEqualTo(2);
        assertThat(firstPage.get("items")).hasSize(2);

        params.put("page", "2");
        JsonNode secondPage = api.get("/api/cases/search?" + form(params)).json();
        assertThat(secondPage.get("items")).hasSize(1);
    }

    private JsonNode search(String query) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("q", query);
        params.put("size", "20");
        var response = api.get("/api/cases/search?" + form(params));
        assertThat(response.ok()).as(response.body()).isTrue();
        return response.json();
    }

    private static String form(Map<String, String> params) {
        return params.entrySet().stream()
                .map(entry -> URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8) + "="
                        + URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8))
                .reduce((left, right) -> left + "&" + right)
                .orElse("");
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
