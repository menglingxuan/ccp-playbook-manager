package ac.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.CreationHelper;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * Export recalculates the workbook formulas of the batch: the CSV carries the freshly computed value
 * instead of the cached value the source workbook carried at import time.
 */
class ExportRecalcTest extends AbstractIntegrationTest {

    private static final String[] HEADER = {"Trade ID", "Trade Date", "Maturity Date", "Notional",
            "Notional Currency", "Rate", "Direction"};

    @Test
    void exportRecalculatesFormulasFromTheCurrentCaseValues() throws IOException {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String productCode = productWithFields("MW", "IRS", List.of("tradeId", "tradeDate", "maturityDate",
                "notional", "notionalCurrency", "fixedRate", "direction"));
        byte[] workbook = workbook("RATE-" + suffix);
        String uploadId = api.upload("/api/uploads", "recalc_" + suffix + ".xlsx", workbook)
                .json().get("uploadId").asString();

        assertThat(api.post("/api/uploads/" + uploadId + "/preview", api.toJson(Map.of(
                "sheetIndex", 0, "productCode", productCode))).ok()).isTrue();
        var imported = api.post("/api/imports", api.toJson(Map.of(
                "uploadId", uploadId, "force", false,
                "sheets", List.of(Map.of("sheetIndex", 0, "productCode", productCode)))));
        assertThat(imported.ok()).as(imported.body()).isTrue();
        String batchId = imported.json().get("batches").get(0).get("batchId").asString();
        String caseId = firstCaseId(batchId);

        // the workbook was written without evaluating, so the imported value is the stale cache
        assertThat(caseValue(caseId, "fixedRate")).isEqualTo("0");

        assertThat(rateOf(export(batchId, null))).isEqualTo("1");
        assertThat(rateOf(export(batchId, false))).isEqualTo("0");

        // editing an input field is reflected because the current values are written back first
        JsonNode detail = api.get("/api/cases/" + caseId).json();
        api.patch("/api/cases/" + caseId, api.toJson(Map.of(
                "version", detail.get("version").asInt(),
                "values", Map.of("notional", "2500000"))));
        assertThat(rateOf(export(batchId, null))).isEqualTo("2.5");
    }

    /** Rate is a formula over the notional column, so it is recomputed on export. */
    private byte[] workbook(String tradeId) throws IOException {
        try (Workbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("IRS");
            Row header = sheet.createRow(0);
            for (int index = 0; index < HEADER.length; index++) {
                header.createCell(index).setCellValue(HEADER[index]);
            }
            CreationHelper helper = workbook.getCreationHelper();
            CellStyle dateStyle = workbook.createCellStyle();
            dateStyle.setDataFormat(helper.createDataFormat().getFormat("yyyy-mm-dd"));

            Row row = sheet.createRow(1);
            row.createCell(0).setCellValue(tradeId);
            row.createCell(1).setCellValue(LocalDate.of(2026, 1, 2));
            row.getCell(1).setCellStyle(dateStyle);
            row.createCell(2).setCellValue(LocalDate.of(2031, 1, 2));
            row.getCell(2).setCellStyle(dateStyle);
            row.createCell(3).setCellValue(1000000);
            row.createCell(4).setCellValue("USD");
            row.createCell(5).setCellFormula("D2/1000000");
            row.createCell(6).setCellValue("BUY");
            workbook.write(out);
            return out.toByteArray();
        }
    }

    private String firstCaseId(String batchId) {
        return api.get("/api/batches/" + batchId + "/cases?page=1&size=10").json()
                .get("items").get(0).get("id").asString();
    }

    private String caseValue(String caseId, String fieldKey) {
        return api.get("/api/cases/" + caseId).json().get("data").get(fieldKey).asString();
    }

    /** Runs an export and returns the CSV text. */
    private String export(String batchId, Boolean recalculate) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("batchId", batchId);
        payload.put("caseIds", List.of());
        payload.put("options", Map.of("delimiter", ",", "quoteAll", false, "includeHeader", true,
                "lineEnding", "\r\n", "bom", false, "managedColumns", "NONE",
                "formulaAsValue", true));
        if (recalculate != null) {
            payload.put("recalculateFormulas", recalculate);
        }
        var response = api.post("/api/exports", api.toJson(payload));
        assertThat(response.ok()).as(response.body()).isTrue();
        assertThat(response.json().get("warnings")).as(response.body()).isEmpty();
        String exportId = response.json().get("exportId").asString();
        return new String(api.getBytes("/api/exports/" + exportId + "/download"), StandardCharsets.UTF_8);
    }

    private String rateOf(String csv) {
        List<String> lines = new ArrayList<>(List.of(csv.split("\r\n")));
        List<String> header = splitCsv(lines.get(0));
        int column = header.indexOf("Rate");
        assertThat(column).as("Rate column in %s", header).isGreaterThanOrEqualTo(0);
        return splitCsv(lines.get(1)).get(column);
    }

    /** Minimal splitter: the fixture has no quoted values. */
    private List<String> splitCsv(String line) {
        return List.of(line.split(",", -1));
    }
}
