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

/**
 * A formula that cannot be recalculated refuses the export instead of silently writing the value the
 * source file cached. Switching the recalculation off exports the stored values on purpose.
 */
class ExportFormulaFailureTest extends AbstractIntegrationTest {

    private static final String[] HEADER = {"Trade ID", "Trade State", "Rate"};

    @Test
    void unrecalculableFormulaRefusesTheExportAndTheStoredValueCanBeExported() throws IOException {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String productCode = productWithFields("MW", "IRS", List.of("tradeId", "tradeState", "fixedRate"));
        String uploadId = api.upload("/api/uploads", "formula_" + suffix + ".xlsx", workbook(suffix))
                .json().get("uploadId").asString();
        assertThat(api.post("/api/uploads/" + uploadId + "/preview",
                api.toJson(Map.of("sheetIndex", 0, "productCode", productCode))).ok()).isTrue();
        var imported = api.post("/api/imports", api.toJson(Map.of("uploadId", uploadId, "force", false,
                "sheets", List.of(Map.of("sheetIndex", 0, "productCode", productCode)))));
        assertThat(imported.ok()).as(imported.body()).isTrue();
        String batchId = imported.json().get("batches").get(0).get("batchId").asString();

        // B2 holds the text "3M", so the arithmetic cannot be evaluated
        Map<String, Object> formula = new LinkedHashMap<>();
        formula.put("fieldKey", "fixedRate");
        formula.put("mode", "FORMULA");
        formula.put("formula", "=WEBSERVICE(\"http://localhost/rates\")");
        formula.put("scope", "BATCH");
        var updated = api.post("/api/batches/" + batchId + "/cases/bulk-update", api.toJson(formula));
        assertThat(updated.ok()).as(updated.body()).isTrue();

        var precheck = api.post("/api/exports/precheck", api.toJson(payload(batchId, null)));
        assertThat(precheck.json().get("valid").asBoolean()).isFalse();
        assertThat(precheck.body()).contains("FORMULA_RECALC_FAILED").contains("Rate");

        var refused = api.post("/api/exports", api.toJson(payload(batchId, null)));
        assertThat(refused.status()).isEqualTo(400);
        assertThat(refused.body()).contains("FORMULA_RECALC_FAILED");

        var exported = api.post("/api/exports", api.toJson(payload(batchId, Boolean.FALSE)));
        assertThat(exported.ok()).as(exported.body()).isTrue();
        String csv = new String(api.getBytes(
                "/api/exports/" + exported.json().get("exportId").asString() + "/download"),
                StandardCharsets.UTF_8);
        assertThat(rateColumn(csv)).as(csv).isEqualTo("0.0325");
    }

    /** Value of the Rate column of the first data row, the header decides the position. */
    private static String rateColumn(String csv) {
        String[] lines = csv.split("\r\n");
        int index = List.of(lines[0].split(",")).indexOf("Rate");
        assertThat(index).as(csv).isGreaterThanOrEqualTo(0);
        return lines[1].split(",")[index];
    }

    private Map<String, Object> payload(String batchId, Boolean recalculate) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("batchId", batchId);
        payload.put("caseIds", List.of());
        payload.put("options", Map.of("delimiter", ",", "quoteAll", false, "includeHeader", true,
                "lineEnding", "\r\n", "bom", false, "managedColumns", "NONE", "formulaAsValue", true));
        payload.put("overrides", List.of());
        if (recalculate != null) {
            payload.put("recalculateFormulas", recalculate);
        }
        return payload;
    }

    private byte[] workbook(String suffix) throws IOException {
        try (Workbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("IRS");
            Row header = sheet.createRow(0);
            for (int index = 0; index < HEADER.length; index++) {
                header.createCell(index).setCellValue(HEADER[index]);
            }
            Row row = sheet.createRow(1);
            row.createCell(0).setCellValue(suffix + "-1");
            row.createCell(1).setCellValue("3M");
            row.createCell(2).setCellValue(0.0325);
            workbook.write(out);
            return out.toByteArray();
        }
    }
}
