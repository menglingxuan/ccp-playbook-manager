package ac.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
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

/** A boolean imported from Excel keeps the TRUE / FALSE spelling in the exported CSV. */
class BooleanCellExportTest extends AbstractIntegrationTest {

    private static final String[] HEADER = {"Trade ID", "Trade Date", "Maturity Date", "Notional",
            "Notional Currency", "Direction", "US Person"};

    @Test
    void exportedBooleanMatchesTheExcelSpelling() throws IOException {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String productCode = productWithFields("MW", "IRS", List.of("tradeId", "tradeDate",
                "maturityDate", "notional", "notionalCurrency", "direction", "usPerson"));
        String uploadId = api.upload("/api/uploads", "bools_" + suffix + ".xlsx", workbook(suffix))
                .json().get("uploadId").asString();
        assertThat(api.post("/api/uploads/" + uploadId + "/preview",
                api.toJson(Map.of("sheetIndex", 0, "productCode", productCode))).ok()).isTrue();
        var imported = api.post("/api/imports", api.toJson(Map.of("uploadId", uploadId, "force", false,
                "sheets", List.of(Map.of("sheetIndex", 0, "productCode", productCode)))));
        assertThat(imported.ok()).as(imported.body()).isTrue();
        String batchId = imported.json().get("batches").get(0).get("batchId").asString();

        JsonNode items = api.get("/api/batches/" + batchId + "/cases?page=1&size=10").json().get("items");
        assertThat(items).hasSize(2);
        assertThat(api.get("/api/cases/" + items.get(0).get("id").asString()).json()
                .get("values").get("usPerson").asString()).isEqualTo("TRUE");
        assertThat(api.get("/api/cases/" + items.get(1).get("id").asString()).json()
                .get("values").get("usPerson").asString()).isEqualTo("FALSE");

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("batchId", batchId);
        payload.put("caseIds", List.of());
        payload.put("options", Map.of("delimiter", ",", "quoteAll", false, "includeHeader", true,
                "lineEnding", "\r\n", "bom", false, "managedColumns", "NONE", "formulaAsValue", true));
        payload.put("overrides", List.of());
        var exported = api.post("/api/exports", api.toJson(payload));
        assertThat(exported.ok()).as(exported.body()).isTrue();
        String csv = new String(api.getBytes(
                "/api/exports/" + exported.json().get("exportId").asString() + "/download"),
                StandardCharsets.UTF_8);

        assertThat(csv).contains("US Person");
        assertThat(csv).as(csv).contains(",BUY,TRUE");
        assertThat(csv).as(csv).contains(",SELL,FALSE");
        assertThat(csv).as(csv).doesNotContain("true").doesNotContain("false");
    }

    /** A batch with a formula is recalculated before export; booleans keep their spelling there too. */
    @Test
    void exportedBooleanKeepsTheSpellingWhenTheBatchIsRecalculated() throws IOException {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String productCode = productWithFields("MW", "IRS", List.of("tradeId", "tradeDate",
                "maturityDate", "notional", "notionalCurrency", "direction", "usPerson"));
        String uploadId = api.upload("/api/uploads", "boolsf_" + suffix + ".xlsx",
                formulaWorkbook(suffix)).json().get("uploadId").asString();
        assertThat(api.post("/api/uploads/" + uploadId + "/preview",
                api.toJson(Map.of("sheetIndex", 0, "productCode", productCode))).ok()).isTrue();
        var imported = api.post("/api/imports", api.toJson(Map.of("uploadId", uploadId, "force", false,
                "sheets", List.of(Map.of("sheetIndex", 0, "productCode", productCode)))));
        assertThat(imported.ok()).as(imported.body()).isTrue();
        String batchId = imported.json().get("batches").get(0).get("batchId").asString();

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("batchId", batchId);
        payload.put("caseIds", List.of());
        payload.put("options", Map.of("delimiter", ",", "quoteAll", false, "includeHeader", true,
                "lineEnding", "\r\n", "bom", false, "managedColumns", "NONE", "formulaAsValue", true));
        payload.put("overrides", List.of());
        var exported = api.post("/api/exports", api.toJson(payload));
        assertThat(exported.ok()).as(exported.body()).isTrue();
        String csv = new String(api.getBytes(
                "/api/exports/" + exported.json().get("exportId").asString() + "/download"),
                StandardCharsets.UTF_8);

        assertThat(csv).as(csv).contains(",BUY,TRUE");
        assertThat(csv).as(csv).contains(",SELL,FALSE");
        assertThat(csv).as(csv).doesNotContain("true").doesNotContain("false");
    }

    private byte[] workbook(String suffix) throws IOException {
        try (Workbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("IRS");
            Row header = sheet.createRow(0);
            for (int index = 0; index < HEADER.length; index++) {
                header.createCell(index).setCellValue(HEADER[index]);
            }
            CreationHelper helper = workbook.getCreationHelper();
            CellStyle dateStyle = workbook.createCellStyle();
            dateStyle.setDataFormat(helper.createDataFormat().getFormat("yyyy-mm-dd"));
            writeRow(sheet, 1, suffix + "-1", "BUY", true, dateStyle);
            writeRow(sheet, 2, suffix + "-2", "SELL", false, dateStyle);
            workbook.write(out);
            return out.toByteArray();
        }
    }

    /** Second workbook whose notional is a formula, so the export recalculates the batch. */
    private byte[] formulaWorkbook(String suffix) throws IOException {
        try (Workbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("IRS");
            Row header = sheet.createRow(0);
            for (int index = 0; index < HEADER.length; index++) {
                header.createCell(index).setCellValue(HEADER[index]);
            }
            CreationHelper helper = workbook.getCreationHelper();
            CellStyle dateStyle = workbook.createCellStyle();
            dateStyle.setDataFormat(helper.createDataFormat().getFormat("yyyy-mm-dd"));
            var evaluator = helper.createFormulaEvaluator();
            for (int index = 0; index < 2; index++) {
                writeRow(sheet, index + 1, suffix + "-F" + (index + 1), index == 0 ? "BUY" : "SELL",
                        index == 0, dateStyle);
                var notional = (org.apache.poi.ss.usermodel.Cell) sheet.getRow(index + 1).getCell(3);
                notional.setCellFormula((1000000 + index) + "*1");
                evaluator.evaluateFormulaCell(notional);
            }
            workbook.write(out);
            return out.toByteArray();
        }
    }

    private void writeRow(Sheet sheet, int index, String tradeId, String direction, boolean usPerson,
            CellStyle dateStyle) {
        Row row = sheet.createRow(index);
        row.createCell(0).setCellValue(tradeId);
        row.createCell(1).setCellValue(LocalDate.of(2026, 1, 2));
        row.getCell(1).setCellStyle(dateStyle);
        row.createCell(2).setCellValue(LocalDate.of(2031, 1, 2));
        row.getCell(2).setCellStyle(dateStyle);
        row.createCell(3).setCellValue(1000000);
        row.createCell(4).setCellValue("USD");
        row.createCell(5).setCellValue(direction);
        row.createCell(6).setCellValue(usPerson);
    }
}
