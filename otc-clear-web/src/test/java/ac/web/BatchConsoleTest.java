package ac.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** Batch console: metadata, filters, sorting, facets, case sidebar and global search. */
class BatchConsoleTest extends AbstractIntegrationTest {

    private static final String CSV_TEMPLATE = """
            _TestCaseId,_Remarks,Trade ID,Trade Date,Maturity Date,Notional,Notional Currency,Rate,Direction
            TC-1,first,%s,2026-01-02,2031-01-02,"1,500,000.00",USD,0.0325,BUY
            TC-2,second,%s-B,2026-02-03,2031-02-03,2000000,EUR,0.025,SELL
            """;

    private static final List<String> KEEP_FIELDS = List.of("testCaseId", "remarks", "tradeId", "tradeDate",
            "maturityDate", "notional", "notionalCurrency", "fixedRate", "direction");

    @Test
    void metaPersistsFavoritePinnedTagsAndNote() {
        String batchId = importOneBatch("meta_a");
        String url = "/api/batches/" + batchId + "/meta";

        var updated = api.patch(url, api.toJson(Map.of(
                "favorite", true,
                "pinned", true,
                "tags", List.of("  smoke ", "Regression", "SMOKE"),
                "note", "  console note  ")));
        assertThat(updated.ok()).as(updated.body()).isTrue();
        JsonNode meta = updated.json();
        assertThat(meta.get("favorite").asBoolean()).isTrue();
        assertThat(meta.get("pinned").asBoolean()).isTrue();
        assertThat(meta.get("note").asString()).isEqualTo("console note");
        // trimmed and de-duplicated case-insensitively, order kept
        assertThat(meta.get("tags").toString()).isEqualTo("[\"smoke\",\"Regression\"]");

        // omitting a field keeps the stored value
        var partial = api.patch(url, api.toJson(Map.of("note", "only the note")));
        assertThat(partial.ok()).isTrue();
        assertThat(partial.json().get("favorite").asBoolean()).isTrue();
        assertThat(partial.json().get("tags").toString()).isEqualTo("[\"smoke\",\"Regression\"]");

        // clearing tags and favoriting off
        var cleared = api.patch(url, api.toJson(Map.of("tags", List.of(), "favorite", false)));
        assertThat(cleared.ok()).isTrue();
        assertThat(cleared.json().get("tags").toString()).isEqualTo("[]");
        assertThat(cleared.json().get("favorite").asBoolean()).isFalse();
    }

    @Test
    void metaValidationRejectsBadTagsAndNotes() {
        String batchId = importOneBatch("meta_b");
        String url = "/api/batches/" + batchId + "/meta";

        assertThat(api.patch(url, api.toJson(Map.of("tags", List.of("a,b")))).status())
                .as("a comma would break the tag column").isEqualTo(400);
        assertThat(api.patch(url, api.toJson(Map.of("tags", List.of("x".repeat(33)))))
                .status()).isEqualTo(400);
        List<String> tooMany = new ArrayList<>();
        for (int i = 0; i < 11; i++) {
            tooMany.add("tag" + i);
        }
        assertThat(api.patch(url, api.toJson(Map.of("tags", tooMany))).status()).isEqualTo(400);
        assertThat(api.patch(url, api.toJson(Map.of("note", "n".repeat(501)))).status()).isEqualTo(400);
    }

    @Test
    void listFiltersAndSortsWithFacets() throws Exception {
        String tag = "tag" + UUID.randomUUID().toString().substring(0, 6);
        String first = importOneBatch("sort_a");
        // creation timestamps are stored per second, keep the batches in different seconds
        Thread.sleep(1100);
        String second = importOneBatch("sort_b");
        assertThat(api.patch("/api/batches/" + second + "/meta",
                api.toJson(Map.of("tags", List.of(tag), "favorite", true))).ok()).isTrue();

        assertThat(batchIds("/api/batches?sort=created_asc&size=50"))
                .as("oldest first").containsSubsequence(first, second);
        assertThat(batchIds("/api/batches?sort=created_desc&size=50"))
                .as("newest first").containsSubsequence(second, first);

        String tagged = "/api/batches?tag=" + tag;
        assertThat(batchIds(tagged)).containsExactly(second);
        assertThat(batchIds("/api/batches?favorite=true&tag=" + tag)).containsExactly(second);
        assertThat(batchIds("/api/batches?favorite=false&tag=" + tag)).isEmpty();

        JsonNode facets = api.get("/api/batches/facets?tag=" + tag).json();
        assertThat(facets.get("total").asLong()).isEqualTo(1);
        assertThat(facets.get("favorite").asLong()).isEqualTo(1);
        assertThat(facets.get("pinned").asLong()).isZero();
        assertThat(facets.get("tags").toString()).contains(tag);
        assertThat(facets.get("products").toString()).contains("IRS");

        JsonNode dock = api.get("/api/batches/dock?limit=10").json();
        assertThat(dock.isArray()).isTrue();
        assertThat(dock.size()).isGreaterThan(0);
        // pinned first, then newest first
        List<String> dockIds = new ArrayList<>();
        dock.forEach(item -> dockIds.add(item.get("id").asString()));
        assertThat(dockIds).contains(second);
    }

    @Test
    void caseSidebarSortsAndSummarizes() {
        String batchId = importOneBatch("cases_a");
        JsonNode page = api.get("/api/batches/" + batchId + "/cases?size=50").json();
        assertThat(page.get("total").asInt()).isEqualTo(2);
        assertThat(page.get("summary").get("total").asLong()).isEqualTo(2);
        assertThat(page.get("summary").get("withFormula").asLong()).isZero();

        List<String> ascending = caseKeys(batchId, "row_asc");
        List<String> descending = caseKeys(batchId, "row_desc");
        assertThat(ascending).hasSize(2);
        assertThat(descending).isEqualTo(ascending.reversed());

        List<String> byKey = caseKeys(batchId, "key_asc");
        assertThat(byKey).isSorted();
    }

    @Test
    void globalSearchSupportsQualifiers() throws Exception {
        String token = "SRCH" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        String batchId = importOneBatch("search_" + token.toLowerCase(), token + "-A", token + "-B");
        String tag = "tag" + token.toLowerCase();
        assertThat(api.patch("/api/batches/" + batchId + "/meta",
                api.toJson(Map.of("tags", List.of(tag)))).ok()).isTrue();

        JsonNode all = api.get("/api/search?q=" + encode(token)).json();
        assertThat(all.get("batches").size()).isEqualTo(1);
        assertThat(all.get("batches").get(0).get("batchId").asString()).isEqualTo(batchId);
        assertThat(all.get("cases").size()).as("both trade ids contain the token").isEqualTo(2);

        JsonNode byTag = api.get("/api/search?q=" + encode("tag:" + tag)).json();
        assertThat(byTag.get("batches").size()).isEqualTo(1);
        assertThat(byTag.get("cases").size())
                .as("a batch qualifier also scopes the cases").isEqualTo(2);

        JsonNode values = api.get("/api/search?q=" + encode("value:" + token + "-A")).json();
        assertThat(values.get("cases").size()).isEqualTo(1);
        assertThat(values.get("cases").get(0).get("keyValue").asString()).isEqualTo(token + "-A");

        JsonNode scopeCase = api.get("/api/search?q=" + encode("scope:case " + token)).json();
        assertThat(scopeCase.get("batches").size()).isZero();
        assertThat(scopeCase.get("cases").size()).isEqualTo(2);

        JsonNode product = api.get("/api/search?q=" + encode("product:OIS " + token)).json();
        assertThat(product.get("batches").size()).as("product excludes batches of IRS").isZero();

        assertThat(api.get("/api/search?q=a").status()).as("a single character is refused").isEqualTo(400);
    }

    private List<String> caseKeys(String batchId, String sort) {
        JsonNode page = api.get("/api/batches/" + batchId + "/cases?size=50&sort=" + sort).json();
        List<String> keys = new ArrayList<>();
        page.get("items").forEach(item -> keys.add(item.get("keyValue").asString()));
        return keys;
    }

    private List<String> batchIds(String path) {
        JsonNode page = api.get(path).json();
        List<String> ids = new ArrayList<>();
        page.get("items").forEach(item -> ids.add(item.get("id").asString()));
        return ids;
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private String importOneBatch(String fileNamePrefix) {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        return importOneBatch(fileNamePrefix + "_" + suffix,
                "BC-" + suffix, "BC-" + suffix + "-B");
    }

    private String importOneBatch(String fileNamePrefix, String tradeId, String secondTradeId) {
        String productCode = productWithFields("MW", "IRS", KEEP_FIELDS);
        String csv = CSV_TEMPLATE.formatted(tradeId, secondTradeId);
        var upload = api.upload("/api/uploads", fileNamePrefix + ".csv", csv.getBytes(StandardCharsets.UTF_8));
        assertThat(upload.ok()).as(upload.body()).isTrue();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("uploadId", upload.json().get("uploadId").asString());
        payload.put("force", false);
        payload.put("sheets", List.of(Map.of("sheetIndex", 0, "productCode", productCode)));
        var imported = api.post("/api/imports", api.toJson(payload));
        assertThat(imported.ok()).as(imported.body()).isTrue();
        return imported.json().get("batches").get(0).get("batchId").asString();
    }
}
