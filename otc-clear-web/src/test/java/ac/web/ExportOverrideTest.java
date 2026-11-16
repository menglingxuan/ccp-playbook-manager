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
 * Exports accept transient field overrides: the CSV carries the overridden values and formulas while
 * the stored cases keep their values, and the export record lists what was overridden.
 */
class ExportOverrideTest extends AbstractIntegrationTest {

    private static final String[] HEADER = {"Trade ID", "Trade Date", "Maturity Date", "Notional",
            "Notional Currency", "Rate", "Direction"};

    @Test
    void valueOverrideOnlyChangesTheExportedFile() throws IOException {
        Fixture fixture = fixture("OVR");
        Map<String, Object> override = new LinkedHashMap<>();
        override.put("fieldKey", "notional");
        override.put("mode", "VALUE");
        override.put("value", "3000000");

        var response = api.post("/api/exports", api.toJson(payload(fixture.batchId(), List.of(override))));
        assertThat(response.ok()).as(response.body()).isTrue();
        JsonNode result = response.json();
        assertThat(result.get("overrides")).hasSize(1);
        assertThat(result.get("overrides").get(0).get("fieldKey").asString()).isEqualTo("notional");
        assertThat(result.get("overrides").get(0).get("header").asString()).isEqualTo("Notional");
        assertThat(result.get("overrides").get(0).get("mode").asString()).isEqualTo("VALUE");
        assertThat(result.get("overrides").get(0).get("display").asString()).isEqualTo("3000000");

        String csv = download(result.get("exportId").asString());
        assertThat(cell(csv, "Notional")).isEqualTo("3000000");
        // the formula over the notional column sees the overridden value as well
        assertThat(cell(csv, "Rate")).isEqualTo("3");
        // nothing was written back
        assertThat(caseValue(fixture.caseId(), "notional")).isEqualTo("1000000");
        assertThat(caseValue(fixture.caseId(), "fixedRate")).isEqualTo("0");
    }

    @Test
    void formulaOverrideIsRecalculatedForTheExportOnly() throws IOException {
        Fixture fixture = fixture("OVF");
        Map<String, Object> formula = new LinkedHashMap<>();
        formula.put("fieldKey", "fixedRate");
        formula.put("mode", "FORMULA");
        formula.put("formula", "=D2/500000");

        var response = api.post("/api/exports", api.toJson(payload(fixture.batchId(), List.of(formula))));
        assertThat(response.ok()).as(response.body()).isTrue();
        assertThat(response.json().get("overrides")).hasSize(1);
        assertThat(response.json().get("overrides").get(0).get("display").asString())
                .isEqualTo("\u0192 =D2/500000");

        String csv = download(response.json().get("exportId").asString());
        assertThat(cell(csv, "Rate")).isEqualTo("2");
        assertThat(caseValue(fixture.caseId(), "fixedRate")).isEqualTo("0");
    }

    @Test
    void clearingARequiredFieldIsRefusedBeforeTheFileIsWritten() throws IOException {
        Fixture fixture = fixture("OVC");
        Map<String, Object> clear = new LinkedHashMap<>();
        clear.put("fieldKey", "notional");
        clear.put("mode", "CLEAR");

        var response = api.post("/api/exports", api.toJson(payload(fixture.batchId(), List.of(clear))));
        assertThat(response.status()).isEqualTo(400);
        assertThat(response.body()).contains("REQUIRED_VALUE_MISSING");
        assertThat(caseValue(fixture.caseId(), "notional")).isEqualTo("1000000");
    }

    @Test
    void overridingTheKeyFieldOfSeveralRowsIsRefused() throws IOException {
        Fixture fixture = fixture("OVK", 2);
        Map<String, Object> override = new LinkedHashMap<>();
        override.put("fieldKey", "tradeId");
        override.put("mode", "VALUE");
        override.put("value", "DUP-1");

        var response = api.post("/api/exports", api.toJson(payload(fixture.batchId(), List.of(override))));
        assertThat(response.status()).isEqualTo(409);
        assertThat(response.body()).contains("duplicate keys");
    }

    @Test
    void invalidOverrideValueIsRejected() throws IOException {
        Fixture fixture = fixture("OVI");
        Map<String, Object> override = new LinkedHashMap<>();
        override.put("fieldKey", "notional");
        override.put("mode", "VALUE");
        override.put("value", "not-a-number");

        var response = api.post("/api/exports", api.toJson(payload(fixture.batchId(), List.of(override))));
        assertThat(response.status()).isEqualTo(400);
    }

    @Test
    void precheckRendersTheOverriddenSampleRow() throws IOException {
        Fixture fixture = fixture("OVP");
        Map<String, Object> override = new LinkedHashMap<>();
        override.put("fieldKey", "notional");
        override.put("mode", "VALUE");
        override.put("value", "4000000");

        Map<String, Object> payload = payload(fixture.batchId(), List.of(override));
        payload.put("recalculateFormulas", false);
        var response = api.post("/api/exports/precheck", api.toJson(payload));
        assertThat(response.ok()).as(response.body()).isTrue();
        List<String> headers = new ArrayList<>();
        response.json().get("headers").forEach(node -> headers.add(node.asString()));
        List<String> sample = new ArrayList<>();
        response.json().get("sampleRow").forEach(node -> sample.add(node.asString()));
        assertThat(sample.get(headers.indexOf("Notional"))).isEqualTo("4000000");
    }

    // ----------------------------------------------------------------- helpers

    private record Fixture(String batchId, String caseId) {
    }

    /** Imports a workbook with one or two rows and returns the batch it produced. */
    private Fixture fixture(String prefix) throws IOException {
        return fixture(prefix, 1);
    }

    private Fixture fixture(String prefix, int rows) throws IOException {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String productCode = productWithFields("MW", "IRS", List.of("tradeId", "tradeDate", "maturityDate",
                "notional", "notionalCurrency", "fixedRate", "direction"));
        String uploadId = api.upload("/api/uploads", prefix.toLowerCase() + "_" + suffix + ".xlsx",
                workbook(prefix + "-" + suffix, rows)).json().get("uploadId").asString();
        assertThat(api.post("/api/uploads/" + uploadId + "/preview", api.toJson(Map.of(
                "sheetIndex", 0, "productCode", productCode))).ok()).isTrue();
        var imported = api.post("/api/imports", api.toJson(Map.of(
                "uploadId", uploadId, "force", false,
                "sheets", List.of(Map.of("sheetIndex", 0, "productCode", productCode)))));
        assertThat(imported.ok()).as(imported.body()).isTrue();
        String batchId = imported.json().get("batches").get(0).get("batchId").asString();
        String caseId = api.get("/api/batches/" + batchId + "/cases?page=1&size=10").json()
                .get("items").get(0).get("id").asString();
        return new Fixture(batchId, caseId);
    }

    private byte[] workbook(String tradeId, int rows) throws IOException {
        try (Workbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("IRS");
            Row header = sheet.createRow(0);
            for (int index = 0; index < HEADER.length; index++) {
                header.createCell(index).setCellValue(HEADER[index]);
            }
            CreationHelper helper = workbook.getCreationHelper();
            CellStyle dateStyle = workbook.createCellStyle();
            dateStyle.setDataFormat(helper.createDataFormat().getFormat("yyyy-mm-dd"));
            for (int index = 0; index < rows; index++) {
                Row row = sheet.createRow(index + 1);
                row.createCell(0).setCellValue(tradeId + "-" + (index + 1));
                row.createCell(1).setCellValue(LocalDate.of(2026, 1, 2));
                row.getCell(1).setCellStyle(dateStyle);
                row.createCell(2).setCellValue(LocalDate.of(2031, 1, 2));
                row.getCell(2).setCellStyle(dateStyle);
                row.createCell(3).setCellValue(1000000 + index);
                row.createCell(4).setCellValue("USD");
                row.createCell(5).setCellFormula("D" + (index + 2) + "/1000000");
                row.createCell(6).setCellValue("BUY");
            }
            workbook.write(out);
            return out.toByteArray();
        }
    }

    private Map<String, Object> payload(String batchId, List<Map<String, Object>> overrides) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("batchId", batchId);
        payload.put("caseIds", List.of());
        payload.put("options", Map.of("delimiter", ",", "quoteAll", false, "includeHeader", true,
                "lineEnding", "\r\n", "bom", false, "managedColumns", "NONE", "formulaAsValue", true));
        payload.put("overrides", overrides);
        return payload;
    }

    private String download(String exportId) {
        return new String(api.getBytes("/api/exports/" + exportId + "/download"), StandardCharsets.UTF_8);
    }

    /** Splits a CSV document into header and value rows, honouring quoted cells. */
    private List<List<String>> csv(String text) {
        List<List<String>> rows = new ArrayList<>();
        for (String line : text.split("\r\n")) {
            if (!line.isBlank()) {
                rows.add(splitCsv(line));
            }
        }
        return rows;
    }

    private List<String> splitCsv(String line) {
        List<String> cells = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        for (char ch : line.toCharArray()) {
            if (ch == '"') {
                quoted = !quoted;
            } else if (ch == ',' && !quoted) {
                cells.add(current.toString());
                current.setLength(0);
            } else {
                current.append(ch);
            }
        }
        cells.add(current.toString());
        return cells;
    }

    /** Value of the first data row in the named column. */
    private String cell(String csvText, String header) {
        List<List<String>> rows = csv(csvText);
        int index = rows.get(0).indexOf(header);
        assertThat(index).as("column %s in %s", header, rows.get(0)).isGreaterThanOrEqualTo(0);
        return rows.get(1).get(index);
    }

    private String caseValue(String caseId, String fieldKey) {
        return api.get("/api/cases/" + caseId).json().get("values").get(fieldKey).asString();
    }
}
