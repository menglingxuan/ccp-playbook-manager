package ac.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.JsonNode;

/** "Stop before the error row" also covers rows rejected at import time by the duplicate key rule. */
@TestPropertySource(properties = "app.import.reject-duplicate-key=true")
class ImportDuplicateStopTest extends AbstractIntegrationTest {

    @Test
    void stopsBeforeTheRowRejectedForARepeatedTradeId() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String productCode = productWithFields("MW", "IRS", List.of("testCaseId", "remarks", "tradeId",
                "tradeDate", "maturityDate", "notional", "notionalCurrency", "fixedRate", "direction"));
        String csv = """
                Trade ID,Trade Date,Maturity Date,Notional,Notional Currency,Rate,Direction
                DUP-%s,2026-01-02,2031-01-02,1000,USD,0.03,BUY
                DUP-%s,2026-01-03,2031-01-03,2000,USD,0.04,SELL
                LATER-%s,2026-01-04,2031-01-04,3000,USD,0.05,BUY
                """.formatted(suffix, suffix, suffix);

        JsonNode stopped = importSheet(csv, "dupstop_" + suffix + ".csv", productCode, Map.of());
        assertThat(stopped.get("totalRows").asInt()).isEqualTo(1);
        assertThat(stopped.get("invalidRows").asInt()).isEqualTo(1);
        assertThat(stopped.get("issues").toString())
                .contains("KEY_DUPLICATE")
                .contains("IMPORT_STOPPED");

        JsonNode continued = importSheet(csv, "dupstop2_" + suffix + ".csv", productCode,
                Map.of("stopAtFirstError", false));
        assertThat(continued.get("totalRows").asInt()).isEqualTo(2);
        assertThat(continued.get("invalidRows").asInt()).isEqualTo(1);
        assertThat(continued.get("issues").toString()).doesNotContain("IMPORT_STOPPED");
    }

    /** Uploads and imports the CSV with extra top level request flags, returning the import response. */
    private JsonNode importSheet(String csv, String fileName, String productCode, Map<String, Object> flags) {
        var upload = api.upload("/api/uploads", fileName, csv.getBytes(StandardCharsets.UTF_8));
        assertThat(upload.ok()).as(upload.body()).isTrue();
        Map<String, Object> payload = new LinkedHashMap<>(flags);
        payload.put("uploadId", upload.json().get("uploadId").asString());
        payload.put("force", false);
        payload.put("sheets", List.of(Map.of("sheetIndex", 0, "productCode", productCode)));
        var imported = api.post("/api/imports", api.toJson(payload));
        assertThat(imported.ok()).as(imported.body()).isTrue();
        return imported.json();
    }
}
