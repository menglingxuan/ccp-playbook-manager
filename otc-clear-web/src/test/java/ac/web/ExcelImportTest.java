package ac.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.CreationHelper;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** Workbook with a documentation sheet, an offset header and formula cells. */
class ExcelImportTest extends AbstractIntegrationTest {

    @Test
    void detectsOffsetHeaderAndCapturesFormulas() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        byte[] workbook = buildWorkbook("IRS-" + suffix);

        var upload = api.upload("/api/uploads", "book_" + suffix + ".xlsx", workbook);
        assertThat(upload.ok()).as(upload.body()).isTrue();
        JsonNode uploadJson = upload.json();
        assertThat(uploadJson.get("sourceType").asString()).isEqualTo("XLSX");
        assertThat(uploadJson.get("sheets")).hasSize(2);

        var preview = api.post("/api/uploads/" + uploadJson.get("uploadId").asString() + "/preview",
                api.toJson(Map.of("sheetIndex", 1)));
        assertThat(preview.ok()).as(preview.body()).isTrue();
        JsonNode previewJson = preview.json();
        assertThat(previewJson.get("productCode").asString()).isEqualTo("IRS");
        JsonNode layout = previewJson.get("preview").get("layout");
        assertThat(layout.get("headerRow").asInt()).isEqualTo(3);
        assertThat(layout.get("tradeStartColumn").asInt()).isEqualTo(3);
        assertThat(previewJson.get("preview").get("valid").asBoolean()).isTrue();

        JsonNode sample = previewJson.get("preview").get("sampleRows").get(0);
        assertThat(sample.get("formulas").get("notional").asString()).isEqualTo("=250000*1");

        var importResponse = api.post("/api/imports", api.toJson(Map.of(
                "uploadId", uploadJson.get("uploadId").asString(),
                "force", false,
                "sheets", List.of(
                        Map.of("sheetIndex", 0, "productCode", "IRS"),
                        Map.of("sheetIndex", 1, "productCode", "IRS")))));
        assertThat(importResponse.ok()).as(importResponse.body()).isTrue();
        JsonNode importJson = importResponse.json();
        assertThat(importJson.get("batchCount").asInt()).isEqualTo(1);
        assertThat(importJson.get("status").asString()).isEqualTo("PARTIAL");

        String batchId = importJson.get("batches").get(0).get("batchId").asString();
        var cases = api.get("/api/batches/" + batchId + "/cases?page=1&size=10");
        JsonNode items = cases.json().get("items");
        assertThat(items).hasSize(1);
        assertThat(items.get(0).get("formulaCount").asInt()).isEqualTo(1);

        var detail = api.get("/api/cases/" + items.get(0).get("id").asString());
        assertThat(detail.ok()).isTrue();
        assertThat(detail.json().get("formulas").get("notional").asString()).isEqualTo("=250000*1");
    }

    private byte[] buildWorkbook(String tradeId) {
        try (Workbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet instructions = workbook.createSheet("Instructions");
            instructions.createRow(0).createCell(0).setCellValue("Fill in the IRS sheet only");

            Sheet sheet = workbook.createSheet("IRS");
            sheet.createRow(0).createCell(0).setCellValue("OTC Clear test data");
            sheet.createRow(1).createCell(0).setCellValue("Generated for automated tests");

            Row header = sheet.createRow(2);
            String[] headers = {"_TestCaseId", "_Remarks", "Trade ID", "Trade Date", "Maturity Date",
                    "Notional", "Notional Currency", "Rate", "Rate", "Direction"};
            for (int index = 0; index < headers.length; index++) {
                header.createCell(index).setCellValue(headers[index]);
            }

            Row row = sheet.createRow(3);
            row.createCell(0).setCellValue("TC-X-" + tradeId);
            row.createCell(1).setCellValue("generated");
            row.createCell(2).setCellValue(tradeId);
            CreationHelper helper = workbook.getCreationHelper();
            CellStyle dateStyle = workbook.createCellStyle();
            dateStyle.setDataFormat(helper.createDataFormat().getFormat("yyyy-mm-dd"));
            Cell tradeDate = row.createCell(3);
            tradeDate.setCellValue(LocalDate.of(2026, 5, 6));
            tradeDate.setCellStyle(dateStyle);
            Cell maturity = row.createCell(4);
            maturity.setCellValue(LocalDate.of(2031, 5, 6));
            maturity.setCellStyle(dateStyle);
            row.createCell(5).setCellFormula("250000*1");
            row.createCell(6).setCellValue("USD");
            row.createCell(7).setCellValue(0.031);
            row.createCell(8).setCellValue(0.041);
            row.createCell(9).setCellValue("BUY");

            // Evaluate first so the cached values are persisted next to the formula text.
            workbook.getCreationHelper().createFormulaEvaluator().evaluateAll();

            workbook.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
