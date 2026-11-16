package ac.web;

import static org.assertj.core.api.Assertions.assertThat;

import ac.core.service.definition.DefinitionTable;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** Bad input has to fail as an invalid request, never as a server error or a lost diagnosis. */
class InputRobustnessTest extends AbstractIntegrationTest {

    @Test
    void pageNumberBeyondIntegerRangeDoesNotFail() {
        var response = api.get("/api/batches?page=2147483647&size=100");

        assertThat(response.ok()).as(response.body()).isTrue();
        assertThat(response.json().get("items")).isEmpty();
    }

    @Test
    void nonNumericQueryParameterIsReportedAsInvalidRequest() {
        var response = api.get("/api/batches?page=1&size=abc");

        assertThat(response.status()).isEqualTo(400);
        assertThat(response.json().get("code").asString()).isEqualTo("INVALID_REQUEST");
        assertThat(response.json().get("detail").asString()).contains("size");
    }

    @Test
    void malformedCsvIsAnInvalidRequest() {
        byte[] broken = "a,b\r\n\"unclosed,1\r\n".getBytes(StandardCharsets.UTF_8);

        var response = api.upload("/api/uploads", "broken.csv", broken);

        assertThat(response.status()).isEqualTo(400);
        assertThat(response.json().get("code").asString()).isEqualTo("INVALID_REQUEST");
    }

    @Test
    void typeMismatchKeepsTheSourceRow() {
        String productCode = productWithFields("MW", "IRS", List.of("tradeId", "tradeDate"));
        String csv = "Trade ID,Trade Date\r\nTRD-" + suffix() + ",not-a-date\r\n";

        var upload = api.upload("/api/uploads", "dates.csv", csv.getBytes(StandardCharsets.UTF_8));
        assertThat(upload.ok()).as(upload.body()).isTrue();
        String uploadId = upload.json().get("uploadId").asString();

        var preview = api.post("/api/uploads/" + uploadId + "/preview",
                api.toJson(Map.of("sheetIndex", 0, "productCode", productCode)));
        assertThat(preview.ok()).as(preview.body()).isTrue();
        JsonNode sample = preview.json().get("preview").get("sampleRows").get(0);
        assertThat(sample.get("valid").asBoolean()).isFalse();
        JsonNode mismatch = issueWithCode(sample.get("issues"), "TYPE_MISMATCH");
        assertThat(mismatch.get("row").asInt()).as(mismatch.toString()).isEqualTo(2);
        assertThat(mismatch.get("column").asInt()).isEqualTo(2);

        var imported = api.post("/api/imports", api.toJson(Map.of(
                "uploadId", uploadId,
                "sheets", List.of(Map.of("sheetIndex", 0, "productCode", productCode)),
                "stopAtFirstError", false)));
        assertThat(imported.ok()).as(imported.body()).isTrue();
        JsonNode reported = issueWithCode(imported.json().get("issues"), "TYPE_MISMATCH");
        assertThat(reported.get("row").asInt()).as(reported.toString()).isEqualTo(2);
    }

    @Test
    void unreadableCellInvalidatesOnlyItsRow() throws Exception {
        byte[] workbook = metaSetWorkbook("sort_order", "not-a-number");

        var response = api.upload("/api/definitions/import/preview?scope=META", "meta.xlsx", workbook);

        assertThat(response.ok()).as(response.body()).isTrue();
        JsonNode invalid = rowWithStatus(response.json(), "INVALID");
        assertThat(invalid.get("messages").toString()).contains("sort_order");
        assertThat(invalid.get("key").asString()).contains("NO_SORT");
    }

    private static JsonNode issueWithCode(JsonNode issues, String code) {
        for (JsonNode issue : issues) {
            if (code.equals(issue.get("code").asString())) {
                return issue;
            }
        }
        throw new IllegalStateException("No " + code + " issue in " + issues);
    }

    private static JsonNode rowWithStatus(JsonNode plan, String status) {
        for (JsonNode row : plan.get("rows")) {
            if (status.equals(row.get("status").asString())) {
                return row;
            }
        }
        throw new IllegalStateException("No " + status + " row in " + plan.get("rows"));
    }

    /** One META_SET row whose {@code sort_order} cell holds text instead of a number. */
    private static byte[] metaSetWorkbook(String column, String value) throws Exception {
        try (XSSFWorkbook book = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = book.createSheet(DefinitionTable.META_SET.sheet());
            List<String> header = DefinitionTable.META_SET.columns();
            Row head = sheet.createRow(0);
            for (int index = 0; index < header.size(); index++) {
                head.createCell(index).setCellValue(header.get(index));
            }
            Map<String, String> row = Map.of("code", "NO_SORT_" + suffix(), "name", "No sort",
                    "description", "", "built_in", "false", column, value, "type", "VALUE");
            Row data = sheet.createRow(1);
            for (int index = 0; index < header.size(); index++) {
                String text = row.getOrDefault(header.get(index), "");
                data.createCell(index).setCellValue(text);
            }
            book.write(out);
            return out.toByteArray();
        }
    }

    private static String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
