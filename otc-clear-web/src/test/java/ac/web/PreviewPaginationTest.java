package ac.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** The wizard pager reads one window at a time while the row count of the whole sheet stays known. */
class PreviewPaginationTest extends AbstractIntegrationTest {

    private static final String CSV_HEADER =
            "_TestCaseId,Trade ID,Trade Date,Maturity Date,Notional,Notional Currency,Rate,Direction";
    private static final List<String> FIELDS = List.of("testCaseId", "tradeId", "tradeDate", "maturityDate",
            "notional", "notionalCurrency", "fixedRate", "direction");
    private static final int ROWS = 45;

    private static String suffix() {
        return UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }

    private static byte[] csv(String suffix) {
        StringBuilder csv = new StringBuilder(CSV_HEADER).append('\n');
        for (int index = 1; index <= ROWS; index++) {
            csv.append("TC-").append(index)
                    .append(",IRS-").append(suffix).append('-').append(index)
                    .append(",2026-01-02,2031-01-02,1000000,USD,0.0325,BUY\n");
        }
        return csv.toString().getBytes(StandardCharsets.UTF_8);
    }

    private JsonNode pageRows(String uploadId, int sheetIndex, String productCode, int limit, int offset) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("sheetIndex", sheetIndex);
        body.put("productCode", productCode);
        body.put("sampleLimit", limit);
        body.put("sampleOffset", offset);
        var response = api.post("/api/uploads/" + uploadId + "/preview/rows", api.toJson(body));
        assertThat(response.ok()).as(response.body()).isTrue();
        return response.json();
    }

    @Test
    void pagesThroughCsvRowsAndReportsTheTotal() {
        String suffix = suffix();
        String productCode = productWithFields("MW", "IRS", FIELDS);
        var upload = api.upload("/api/uploads", "page_" + suffix + ".csv", csv(suffix));
        assertThat(upload.ok()).as(upload.body()).isTrue();
        String uploadId = upload.json().get("uploadId").asString();

        JsonNode first = pageRows(uploadId, 0, productCode, 20, 0);
        assertThat(first.get("totalRows").asInt()).isEqualTo(ROWS);
        assertThat(first.get("offset").asInt()).isZero();
        assertThat(first.get("limit").asInt()).isEqualTo(20);
        assertThat(first.get("rows")).hasSize(20);
        // the row number is the sheet row, so the header stays row 1
        assertThat(first.get("rows").get(0).get("row").asInt()).isEqualTo(2);
        assertThat(textOf(first.get("rows").get(0), "tradeId")).isEqualTo("IRS-" + suffix + "-1");
        assertThat(textOf(first.get("rows").get(19), "tradeId")).isEqualTo("IRS-" + suffix + "-20");

        JsonNode second = pageRows(uploadId, 0, productCode, 20, 20);
        assertThat(second.get("rows")).hasSize(20);
        assertThat(second.get("rows").get(0).get("row").asInt()).isEqualTo(22);
        assertThat(textOf(second.get("rows").get(0), "tradeId")).isEqualTo("IRS-" + suffix + "-21");

        JsonNode last = pageRows(uploadId, 0, productCode, 20, 40);
        assertThat(last.get("rows")).hasSize(5);
        assertThat(textOf(last.get("rows").get(4), "tradeId"))
                .isEqualTo("IRS-" + suffix + "-" + ROWS);
        assertThat(last.get("totalRows").asInt()).isEqualTo(ROWS);

        JsonNode beyond = pageRows(uploadId, 0, productCode, 20, 60);
        assertThat(beyond.get("rows")).isEmpty();
        assertThat(beyond.get("totalRows").asInt()).isEqualTo(ROWS);
    }

    @Test
    void reportsCellIssuesOfTheVisiblePageOnly() {
        String suffix = suffix();
        String productCode = productWithFields("MW", "IRS", FIELDS);
        StringBuilder csv = new StringBuilder(CSV_HEADER).append('\n');
        for (int index = 1; index <= ROWS; index++) {
            // the 25th row carries a value the numeric field cannot read
            String notional = index == 25 ? "not-a-number" : "1000000";
            csv.append("TC-").append(index)
                    .append(",IRS-").append(suffix).append('-').append(index)
                    .append(",2026-01-02,2031-01-02,").append(notional).append(",USD,0.0325,BUY\n");
        }
        var upload = api.upload("/api/uploads", "issue_" + suffix + ".csv",
                csv.toString().getBytes(StandardCharsets.UTF_8));
        assertThat(upload.ok()).as(upload.body()).isTrue();
        String uploadId = upload.json().get("uploadId").asString();

        JsonNode firstPage = pageRows(uploadId, 0, productCode, 20, 0);
        assertThat(firstPage.get("rows").get(0).get("valid").asBoolean()).isTrue();
        assertThat(codes(firstPage.get("issues"))).doesNotContain("TYPE_MISMATCH");

        JsonNode secondPage = pageRows(uploadId, 0, productCode, 20, 20);
        assertThat(codes(secondPage.get("issues"))).contains("TYPE_MISMATCH");
        JsonNode broken = secondPage.get("rows").get(4);
        assertThat(broken.get("row").asInt()).isEqualTo(26);
        assertThat(broken.get("valid").asBoolean()).isFalse();
        assertThat(broken.get("issues").get(0).get("fieldKey").asString()).isEqualTo("notional");
    }

    /** Original cell text of a preview row: text fields live in the raw texts only. */
    private static String textOf(JsonNode row, String fieldKey) {
        JsonNode data = row.get("rawTexts").get("data");
        return data == null || data.get(fieldKey) == null ? null : data.get(fieldKey).asString();
    }

    private static java.util.List<String> codes(JsonNode issues) {        java.util.List<String> codes = new java.util.ArrayList<>();
        issues.forEach((issue) -> codes.add(issue.get("code").asString()));
        return codes;
    }

    @Test
    void pagesThroughXlsxRows() {
        String suffix = suffix();
        String productCode = productWithFields("MW", "IRS", FIELDS);
        var upload = api.upload("/api/uploads", "page_" + suffix + ".xlsx", workbook(suffix));
        assertThat(upload.ok()).as(upload.body()).isTrue();
        String uploadId = upload.json().get("uploadId").asString();

        JsonNode first = pageRows(uploadId, 0, productCode, 20, 0);
        assertThat(first.get("totalRows").asInt()).isEqualTo(ROWS);
        assertThat(first.get("rows")).hasSize(20);
        assertThat(textOf(first.get("rows").get(0), "tradeId")).isEqualTo("IRS-" + suffix + "-1");

        JsonNode last = pageRows(uploadId, 0, productCode, 20, 40);
        assertThat(last.get("rows")).hasSize(5);
        assertThat(textOf(last.get("rows").get(4), "tradeId"))
                .isEqualTo("IRS-" + suffix + "-" + ROWS);
    }

    @Test
    void rawPreviewPagesWholeSheet() {
        String suffix = suffix();
        var upload = api.upload("/api/uploads", "raw_" + suffix + ".csv", csv(suffix));
        assertThat(upload.ok()).as(upload.body()).isTrue();
        String uploadId = upload.json().get("uploadId").asString();

        var firstPage = api.get("/api/uploads/" + uploadId + "/sheets/0/raw?rows=10&offset=0");
        assertThat(firstPage.ok()).as(firstPage.body()).isTrue();
        assertThat(firstPage.json().get("totalRows").asInt()).isEqualTo(ROWS + 1);
        assertThat(firstPage.json().get("rows")).hasSize(10);
        assertThat(firstPage.json().get("rows").get(0).get("row").asInt()).isEqualTo(1);

        var tail = api.get("/api/uploads/" + uploadId + "/sheets/0/raw?rows=10&offset=40");
        assertThat(tail.json().get("rows")).hasSize(ROWS + 1 - 40);
        assertThat(tail.json().get("rows").get(0).get("row").asInt()).isEqualTo(41);
        assertThat(tail.json().get("offset").asInt()).isEqualTo(40);
    }

    private byte[] workbook(String suffix) {
        try (Workbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("IRS");
            Row header = sheet.createRow(0);
            String[] headers = CSV_HEADER.split(",");
            for (int column = 0; column < headers.length; column++) {
                header.createCell(column).setCellValue(headers[column]);
            }
            for (int index = 1; index <= ROWS; index++) {
                Row row = sheet.createRow(index);
                row.createCell(0).setCellValue("TC-" + index);
                row.createCell(1).setCellValue("IRS-" + suffix + "-" + index);
                row.createCell(2).setCellValue("2026-01-02");
                row.createCell(3).setCellValue("2031-01-02");
                row.createCell(4).setCellValue(1000000);
                row.createCell(5).setCellValue("USD");
                row.createCell(6).setCellValue(0.0325);
                row.createCell(7).setCellValue("BUY");
            }
            workbook.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
