package ac.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** Core business record id: same recipe as the business record id, managed columns excluded. */
class CoreBusinessRecordTest extends AbstractIntegrationTest {

    private static final String HEADER = "_TestCaseId,_Remarks,Trade ID,Trade Date,Maturity Date,Notional";

    private static final List<String> KEEP_FIELDS =
            List.of("testCaseId", "remarks", "tradeId", "tradeDate", "maturityDate", "notional");

    @Test
    void coreIdIgnoresManagedColumnsWhileBusinessIdFollowsThem() {
        String product = productWithFields("MW", "IRS", KEEP_FIELDS);
        String suffix = UUID.randomUUID().toString().substring(0, 6);
        String tradeId = "CB-" + suffix;
        String left = importBatch(product, "core_a_" + suffix,
                "TC-1,first," + tradeId + ",2026-01-02,2031-01-02,1000\n");
        // identical trade data, different managed columns
        String right = importBatch(product, "core_b_" + suffix,
                "TC-9,second," + tradeId + ",2026-01-02,2031-01-02,1000\n");

        JsonNode leftCase = firstCase(left);
        JsonNode rightCase = firstCase(right);
        assertThat(leftCase.get("coreBusinessRecordId").asString()).hasSize(32);
        assertThat(leftCase.get("coreBusinessRecordId").asString())
                .isEqualTo(rightCase.get("coreBusinessRecordId").asString());
        // managed columns belong to the business record, so they move it and not the core id
        assertThat(leftCase.get("businessRecordId").asString())
                .isNotEqualTo(rightCase.get("businessRecordId").asString());

        // a trade field still moves the core id
        String other = importBatch(product, "core_c_" + suffix,
                "TC-1,first," + tradeId + ",2026-01-02,2031-01-02,2000\n");
        assertThat(firstCase(other).get("coreBusinessRecordId").asString())
                .isNotEqualTo(leftCase.get("coreBusinessRecordId").asString());
    }

    @Test
    void editingATradeFieldRecomputesTheCoreId() {
        String product = productWithFields("MW", "IRS", KEEP_FIELDS);
        String suffix = UUID.randomUUID().toString().substring(0, 6);
        String batchId = importBatch(product, "core_edit_" + suffix,
                "TC-1,first,CE-" + suffix + ",2026-01-02,2031-01-02,1000\n");
        JsonNode before = firstCase(batchId);
        String caseId = before.get("id").asString();

        var response = api.patch("/api/cases/" + caseId, api.toJson(Map.of(
                "version", before.get("version").asInt(),
                "values", Map.of("notional", "1234"))));
        assertThat(response.ok()).as(response.body()).isTrue();
        JsonNode after = response.json();
        assertThat(after.get("coreBusinessRecordId").asString())
                .isNotEqualTo(before.get("coreBusinessRecordId").asString());

        // the vertical view exposes the new identity row
        JsonNode identity = api.get("/api/cases/" + caseId + "/vertical").json().get("sections").get(0);
        String coreRow = null;
        for (JsonNode row : identity.get("rows")) {
            if ("coreBusinessRecordId".equals(row.get("fieldKey").asString())) {
                coreRow = row.get("value").asString();
            }
        }
        assertThat(coreRow).isEqualTo(after.get("coreBusinessRecordId").asString());
    }

    @Test
    void editingAManagedColumnMovesOnlyTheBusinessId() {
        String product = productWithFields("MW", "IRS", KEEP_FIELDS);
        String suffix = UUID.randomUUID().toString().substring(0, 6);
        String batchId = importBatch(product, "core_remarks_" + suffix,
                "TC-1,first,CR-" + suffix + ",2026-01-02,2031-01-02,1000\n");
        JsonNode before = firstCase(batchId);
        String caseId = before.get("id").asString();

        var response = api.patch("/api/cases/" + caseId, api.toJson(Map.of(
                "version", before.get("version").asInt(),
                "managed", Map.of("_Remarks", "changed"))));
        assertThat(response.ok()).as(response.body()).isTrue();
        JsonNode after = response.json();
        assertThat(after.get("businessRecordId").asString())
                .isNotEqualTo(before.get("businessRecordId").asString());
        assertThat(after.get("coreBusinessRecordId").asString())
                .isEqualTo(before.get("coreBusinessRecordId").asString());
    }

    @Test
    void duplicatesRecordIdsInsideOneBatch() {
        // no unique key field, so identical rows are kept and can share record ids
        String product = productWithFields("MW", "IRS",
                List.of("testCaseId", "remarks", "tradeDate", "maturityDate", "notional"));
        String suffix = UUID.randomUUID().toString().substring(0, 6);
        String header = "_TestCaseId,_Remarks,Trade Date,Maturity Date,Notional\n";
        String differentRemarks = importCsv(product, "core_dup_" + suffix, header + """
                TC-1,first,2026-01-02,2031-01-02,1000
                TC-2,second,2026-01-02,2031-01-02,1000
                TC-3,third,2026-01-03,2031-01-02,1000
                """);

        JsonNode page = api.get("/api/batches/" + differentRemarks + "/cases?size=50").json();
        JsonNode duplicates = page.get("duplicates");
        assertThat(page.get("items")).hasSize(3);
        assertThat(duplicates.get("core")).hasSize(1);
        assertThat(duplicates.get("business")).isEmpty();
        String coreId = page.get("items").get(0).get("coreBusinessRecordId").asString();
        assertThat(duplicates.get("core").get(0).asString()).isEqualTo(coreId);

        // identical rows, managed columns included: both ids repeat
        String sameRows = importCsv(product, "core_dup2_" + suffix, header + """
                TC-1,first,2026-01-02,2031-01-02,1000
                TC-1,first,2026-01-02,2031-01-02,1000
                """);
        JsonNode both = api.get("/api/batches/" + sameRows + "/cases?size=50").json().get("duplicates");
        assertThat(both.get("core")).hasSize(1);
        assertThat(both.get("business")).hasSize(1);
    }

    private JsonNode firstCase(String batchId) {
        var response = api.get("/api/batches/" + batchId + "/cases?size=10");
        assertThat(response.ok()).as(response.body()).isTrue();
        JsonNode items = response.json().get("items");
        assertThat(items).isNotEmpty();
        return items.get(0);
    }

    private String importBatch(String productCode, String fileNamePrefix, String rows) {
        return importCsv(productCode, fileNamePrefix, HEADER + "\n" + rows);
    }

    private String importCsv(String productCode, String fileNamePrefix, String csv) {
        var upload = api.upload("/api/uploads", fileNamePrefix + ".csv", csv.getBytes(StandardCharsets.UTF_8));
        assertThat(upload.ok()).as(upload.body()).isTrue();
        var imported = api.post("/api/imports", api.toJson(Map.of(
                "uploadId", upload.json().get("uploadId").asString(), "force", false,
                "sheets", List.of(Map.of("sheetIndex", 0, "productCode", productCode)))));
        assertThat(imported.ok()).as(imported.body()).isTrue();
        return imported.json().get("batches").get(0).get("batchId").asString();
    }
}
