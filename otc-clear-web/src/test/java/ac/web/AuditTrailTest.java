package ac.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** Audit trail: only product definitions and the field meta dictionary are recorded. */
class AuditTrailTest extends AbstractIntegrationTest {

    private static final String CSV_TEMPLATE = """
            _TestCaseId,_Remarks,Trade ID,Trade Date,Maturity Date,Notional,Notional Currency,Rate,Direction
            TC-1,first,%s,2026-01-02,2031-01-02,1500000.00,USD,0.0325,BUY
            """;

    private static final List<String> KEEP_FIELDS = List.of("testCaseId", "remarks", "tradeId", "tradeDate",
            "maturityDate", "notional", "notionalCurrency", "fixedRate", "direction");

    @Test
    void productDefinitionChangesAreAudited() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String code = "AUD_" + suffix.toUpperCase();

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("code", code);
        payload.put("name", code);
        var created = api.post("/api/catalog/platforms/MW/base-products", api.toJson(payload));
        assertThat(created.ok()).as(created.body()).isTrue();

        JsonNode page = api.get("/api/audit?module=PRODUCT_DEFINITION&q=" + code).json();
        assertThat(page.get("total").asInt()).isEqualTo(1);
        JsonNode item = page.get("items").get(0);
        assertThat(item.get("module").asString()).isEqualTo("PRODUCT_DEFINITION");
        assertThat(item.get("entityType").asString()).isEqualTo("BASE_PRODUCT");
        assertThat(item.get("action").asString()).isEqualTo("CREATE");
        assertThat(item.get("detail").asString()).contains(code);
        assertThat(item.get("createdAt").asString()).isNotBlank();
    }

    @Test
    void metaDictionaryChangesAreAudited() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String setCode = "AUD_SET_" + suffix.toUpperCase();

        Map<String, Object> set = new LinkedHashMap<>();
        set.put("code", setCode);
        set.put("name", setCode);
        assertThat(api.post("/api/meta/sets", api.toJson(set)).ok()).isTrue();

        Map<String, Object> item = new LinkedHashMap<>();
        item.put("code", "FIRST");
        item.put("label", "First");
        assertThat(api.post("/api/meta/sets/" + setCode + "/items", api.toJson(item)).ok()).isTrue();
        Map<String, Object> rename = new LinkedHashMap<>();
        rename.put("label", "First updated");
        assertThat(api.patch("/api/meta/sets/" + setCode + "/items/FIRST", api.toJson(rename)).ok()).isTrue();

        JsonNode page = api.get("/api/audit?module=META_DICTIONARY&q=" + setCode).json();
        assertThat(page.get("total").asInt()).isEqualTo(3);
        List<String> actions = new ArrayList<>();
        page.get("items").forEach(row -> actions.add(row.get("entityType").asString() + "/"
                + row.get("action").asString()));
        assertThat(actions).containsExactlyInAnyOrder("META_SET/CREATE", "META_ITEM/CREATE",
                "META_ITEM/UPDATE");

        assertThat(api.get("/api/audit?entityType=META_ITEM&q=" + setCode).json().get("total").asInt())
                .isEqualTo(2);
        assertThat(api.get("/api/audit?action=UPDATE&q=" + setCode).json().get("total").asInt()).isEqualTo(1);
    }

    @Test
    void importsAndCaseEditsAreNotAudited() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        importBatch("audit_import_" + suffix, "AUD-" + suffix);

        assertThat(api.get("/api/audit?module=TASK").json().get("total").asInt()).isZero();
        assertThat(api.get("/api/audit?module=BATCH").json().get("total").asInt()).isZero();
        assertThat(api.get("/api/audit?action=IMPORT").json().get("total").asInt()).isZero();
    }

    @Test
    void facetsAndDateFilterFollowTheActiveFilter() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String setCode = "AUD_FACET_" + suffix.toUpperCase();
        Map<String, Object> set = new LinkedHashMap<>();
        set.put("code", setCode);
        set.put("name", setCode);
        assertThat(api.post("/api/meta/sets", api.toJson(set)).ok()).isTrue();

        JsonNode facets = api.get("/api/audit/facets?module=META_DICTIONARY").json();
        assertThat(facets.get("modules").get("META_DICTIONARY").asInt()).isGreaterThanOrEqualTo(1);
        assertThat(facets.get("entities").get("META_SET").asInt()).isGreaterThanOrEqualTo(1);
        assertThat(facets.get("actions").get("CREATE").asInt()).isGreaterThanOrEqualTo(1);
        assertThat(facets.get("total").asInt()).isGreaterThanOrEqualTo(1);

        String today = LocalDate.now(ZoneId.of("Asia/Shanghai")).toString();
        assertThat(api.get("/api/audit?q=" + setCode + "&from=" + today + "&to=" + today)
                .json().get("total").asInt()).isEqualTo(1);
        assertThat(api.get("/api/audit?q=" + setCode + "&to=2000-01-01").json().get("total").asInt())
                .isZero();
    }

    @Test
    void auditPageRenders() {
        var response = api.get("/audit");
        assertThat(response.status()).as(response.body()).isEqualTo(200);
        assertThat(response.body()).contains("审计日志");
    }

    private void importBatch(String fileNamePrefix, String tradeId) {
        String productCode = productWithFields("MW", "IRS", KEEP_FIELDS);
        String csv = CSV_TEMPLATE.formatted(tradeId);
        var upload = api.upload("/api/uploads", fileNamePrefix + ".csv", csv.getBytes(StandardCharsets.UTF_8));
        assertThat(upload.ok()).as(upload.body()).isTrue();
        var imported = api.post("/api/imports", api.toJson(Map.of(
                "uploadId", upload.json().get("uploadId").asString(), "force", false,
                "sheets", List.of(Map.of("sheetIndex", 0, "productCode", productCode)))));
        assertThat(imported.ok()).as(imported.body()).isTrue();
    }
}
