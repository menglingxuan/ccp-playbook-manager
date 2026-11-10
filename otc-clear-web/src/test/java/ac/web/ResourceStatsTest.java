package ac.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** Resource statistics: file areas, database rows and runtime figures of the tenant. */
class ResourceStatsTest extends AbstractIntegrationTest {

    private static final String CSV_TEMPLATE = """
            _TestCaseId,_Remarks,Trade ID,Trade Date,Maturity Date,Notional,Notional Currency,Rate,Direction
            TC-1,first,%s,2026-01-02,2031-01-02,1500000.00,USD,0.0325,BUY
            """;

    private static final List<String> KEEP_FIELDS = List.of("testCaseId", "remarks", "tradeId", "tradeDate",
            "maturityDate", "notional", "notionalCurrency", "fixedRate", "direction");

    @Test
    void statsReportStorageDatabaseAndRuntime() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        importBatch("stats_" + suffix, "STA-" + suffix);

        JsonNode stats = api.get("/api/resources/stats").json();
        assertThat(stats.get("tenantId").asString()).isEqualTo("default");
        assertThat(stats.get("generatedAt").asString()).isNotBlank();
        assertThat(stats.get("metrics").get("heap").asBoolean()).isTrue();
        assertThat(stats.get("metrics").get("disk").asBoolean()).isTrue();
        assertThat(stats.get("totalFiles").asLong()).isPositive();
        assertThat(stats.get("totalBytes").asLong()).isPositive();
        assertThat(stats.get("diskTotalBytes").asLong()).isPositive();

        Map<String, JsonNode> areas = new java.util.LinkedHashMap<>();
        stats.get("areas").forEach(area -> areas.put(area.get("key").asString(), area));
        assertThat(areas).containsKeys("uploads", "batches", "exports", "jobs", "db");
        assertThat(areas.get("batches").get("bytes").asLong()).isPositive();
        assertThat(areas.get("batches").get("files").asLong()).isPositive();

        JsonNode database = stats.get("database");
        assertThat(database.get("product").asString()).containsIgnoringCase("h2");
        assertThat(database.get("version").asString()).isNotBlank();
        assertThat(database.get("display").asString()).containsIgnoringCase("h2");
        assertThat(database.get("totalRows").asLong()).isPositive();
        Map<String, Long> tables = new java.util.LinkedHashMap<>();
        database.get("tables").forEach(table -> tables.put(table.get("table").asString(),
                table.get("rows").asLong()));
        assertThat(tables).containsKeys("import_task", "import_batch", "test_case", "audit_log",
                "meta_item", "base_product_field");
        assertThat(tables.get("import_batch")).isPositive();

        JsonNode runtime = stats.get("runtime");
        assertThat(runtime.get("heapUsedBytes").asLong()).isPositive();
        assertThat(runtime.get("heapMaxBytes").asLong()).isPositive();
        assertThat(runtime.get("processors").asInt()).isPositive();
        assertThat(runtime.get("threads").asInt()).isPositive();
    }

    @Test
    void statsPageAndContextExposeDatabaseVersion() {
        var page = api.get("/resources");
        assertThat(page.status()).as(page.body()).isEqualTo(200);
        assertThat(page.body()).contains("资源统计");

        JsonNode context = api.get("/api/context").json();
        assertThat(context.get("database").asString()).containsIgnoringCase("h2");
        assertThat(context.get("auditEnabled").asBoolean()).isTrue();
        assertThat(context.get("profiles").isArray()).isTrue();
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
