package ac.web;

import static org.assertj.core.api.Assertions.assertThat;

import ac.tools.SampleWorkbookGenerator;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;

/** Guards the shape of the generated demo data: 70x200 worksheet and 30x100 CCSIRS CSV. */
class SampleDataTest extends AbstractIntegrationTest {

    @TempDir
    Path samples;

    @Test
    void wideWorksheetKeepsSeventyColumnsAndTwoHundredRows() throws IOException {
        SampleWorkbookGenerator.generate(samples);
        byte[] workbook = Files.readAllBytes(samples.resolve("irs_workbook.xlsx"));
        String productCode = productWithFields("MW", "IRS", List.of("testCaseId", "remarks", "tradeId",
                "tradeDate", "effectiveDate", "maturityDate", "notional", "notionalCurrency", "fixedRate",
                "fixedFrequency", "floatingFrequency", "dayCount", "businessDayConvention", "paymentCalendar",
                "clearingHouse", "clearingMember", "account", "counterparty", "direction", "upi",
                "reportingParty", "tradeState", "novationDate", "portfolio", "usPerson"));

        var upload = api.upload("/api/uploads", "irs_workbook.xlsx", workbook);
        assertThat(upload.ok()).as(upload.body()).isTrue();
        JsonNode sheets = upload.json().get("sheets");
        assertThat(sheets).hasSize(5);

        int wideIndex = -1;
        for (JsonNode sheet : sheets) {
            if (SampleWorkbookGenerator.WIDE_SHEET_NAME.equals(sheet.get("name").asString())) {
                wideIndex = sheet.get("index").asInt();
                assertThat(sheet.get("columnCount").asInt())
                        .isEqualTo(SampleWorkbookGenerator.WIDE_COLUMN_COUNT);
            }
        }
        assertThat(wideIndex).as("IRS_Wide sheet must exist").isGreaterThanOrEqualTo(0);

        var preview = api.post("/api/uploads/" + upload.json().get("uploadId").asString() + "/preview",
                api.toJson(java.util.Map.of("sheetIndex", wideIndex, "productCode", productCode)));
        assertThat(preview.ok()).as(preview.body()).isTrue();
        JsonNode body = preview.json();
        assertThat(body.get("productCode").asString()).isEqualTo(productCode);

        JsonNode sheetPreview = body.get("preview");
        assertThat(sheetPreview.get("valid").asBoolean()).isTrue();
        JsonNode layout = sheetPreview.get("layout");
        assertThat(layout.get("headerRow").asInt()).isEqualTo(2);
        assertThat(layout.get("tradeStartColumn").asInt()).isEqualTo(5);
        assertThat(layout.get("tradeEndColumn").asInt()).isEqualTo(28);
        assertThat(layout.get("lastColumn").asInt()).isEqualTo(SampleWorkbookGenerator.WIDE_COLUMN_COUNT);
        assertThat(layout.get("missingFields")).isEmpty();

        JsonNode stats = sheetPreview.get("stats");
        assertThat(stats.get("dataRows").asInt()).isEqualTo(SampleWorkbookGenerator.WIDE_ROW_COUNT);
        assertThat(stats.get("mappedTradeColumns").asInt()).isEqualTo(23);
        assertThat(stats.get("managedColumns").asInt()).isEqualTo(2);

        JsonNode sample = sheetPreview.get("sampleRows").get(0);
        assertThat(sample.get("values").get("tradeId").asString()).isEqualTo("IRS-W-0001");
        assertThat(sample.get("formulas").get("notional").asString()).isEqualTo("=1012500.00*1");
        assertThat(sample.get("managed").get("_Remarks").asString()).isNotEmpty();
        // columns outside the product definition never become managed columns
        assertThat(sample.get("extras").get("Booking System").asString()).isNotEmpty();

        var imported = api.post("/api/imports", api.toJson(java.util.Map.of(
                "uploadId", upload.json().get("uploadId").asString(),
                "force", false,
                "sheets", List.of(java.util.Map.of("sheetIndex", wideIndex, "productCode", productCode)))));
        assertThat(imported.ok()).as(imported.body()).isTrue();
        assertThat(imported.json().get("status").asString()).isEqualTo("SUCCEEDED");
        assertThat(imported.json().get("totalRows").asInt())
                .isEqualTo(SampleWorkbookGenerator.WIDE_ROW_COUNT);
    }

    /**
     * The MW/IRS template opens its trade block with the required Report Timestamp, so the standard
     * sheet maps without a single missing field.
     */
    @Test
    void standardSheetMapsTheReportTimestampColumn() throws IOException {
        SampleWorkbookGenerator.generate(samples);
        byte[] workbook = Files.readAllBytes(samples.resolve("irs_workbook.xlsx"));
        String productCode = productWithFields("MW", "IRS", List.of("testCaseId", "remarks",
                "reportTimestamp", "tradeId", "tradeDate", "maturityDate", "notional", "notionalCurrency",
                "fixedRate", "direction"));

        var upload = api.upload("/api/uploads", "irs_workbook.xlsx", workbook);
        int sheetIndex = -1;
        for (JsonNode sheet : upload.json().get("sheets")) {
            if (SampleWorkbookGenerator.STANDARD_SHEET_NAME.equals(sheet.get("name").asString())) {
                sheetIndex = sheet.get("index").asInt();
            }
        }
        assertThat(sheetIndex).as("IRS.Standard sheet must exist").isGreaterThanOrEqualTo(0);

        var preview = api.post("/api/uploads/" + upload.json().get("uploadId").asString() + "/preview",
                api.toJson(java.util.Map.of("sheetIndex", sheetIndex, "productCode", productCode)));
        assertThat(preview.ok()).as(preview.body()).isTrue();
        JsonNode sheetPreview = preview.json().get("preview");
        assertThat(sheetPreview.get("valid").asBoolean()).as(preview.body()).isTrue();
        assertThat(sheetPreview.get("layout").get("missingFields")).isEmpty();

        JsonNode sample = sheetPreview.get("sampleRows").get(0);
        assertThat(sample.get("values").get("reportTimestamp").asString())
                .isEqualTo("2026-11-09T20:34:00.970");
        assertThat(sample.get("values").get("tradeId").asString()).isEqualTo("IRS-1001");
        assertThat(sample.get("managed").get("_Remarks").asString()).isEqualTo("standard row 1");
        assertThat(sample.get("extras").get("Extra Ref").asString()).isEqualTo("REF-A");
    }

    @Test
    void wideCsvKeepsSeventyColumnsAndOneHundredRows() throws IOException {        SampleWorkbookGenerator.generate(samples);
        byte[] csv = Files.readAllBytes(samples.resolve(SampleWorkbookGenerator.WIDE_CSV_FILE));

        var upload = api.upload("/api/uploads", SampleWorkbookGenerator.WIDE_CSV_FILE, csv);
        assertThat(upload.ok()).as(upload.body()).isTrue();
        JsonNode sheet = upload.json().get("sheets").get(0);
        assertThat(sheet.get("columnCount").asInt()).isEqualTo(SampleWorkbookGenerator.CSV_COLUMN_COUNT);

        var preview = api.post("/api/uploads/" + upload.json().get("uploadId").asString() + "/preview",
                api.toJson(java.util.Map.of("sheetIndex", 0)));
        assertThat(preview.ok()).as(preview.body()).isTrue();
        JsonNode body = preview.json();
        assertThat(body.get("productCode").asString()).isEqualTo("CCSIRS");

        JsonNode sheetPreview = body.get("preview");
        assertThat(sheetPreview.get("valid").asBoolean()).isTrue();
        JsonNode layout = sheetPreview.get("layout");
        assertThat(layout.get("tradeStartColumn").asInt()).isEqualTo(5);
        assertThat(layout.get("tradeEndColumn").asInt()).isEqualTo(SampleWorkbookGenerator.CSV_COLUMN_COUNT);
        assertThat(layout.get("lastColumn").asInt()).isEqualTo(SampleWorkbookGenerator.CSV_COLUMN_COUNT);

        JsonNode stats = sheetPreview.get("stats");
        assertThat(stats.get("dataRows").asInt()).isEqualTo(SampleWorkbookGenerator.CSV_ROW_COUNT);
        assertThat(stats.get("mappedTradeColumns").asInt()).isEqualTo(66);
        assertThat(stats.get("managedColumns").asInt()).isEqualTo(4);

        JsonNode sample = sheetPreview.get("sampleRows").get(0);
        assertThat(sample.get("values").get("tradeId").asString()).isEqualTo("CCS-W-0001");
        // Scale is lost by the JSON node itself; the export path keeps it via BigDecimal (see ImportFlowTest).
        assertThat(sample.get("values").get("notional2").decimalValue()).isEqualByComparingTo("1620000.00");

        var imported = api.post("/api/imports", api.toJson(java.util.Map.of(
                "uploadId", upload.json().get("uploadId").asString(),
                "force", false,
                "sheets", List.of(java.util.Map.of("sheetIndex", 0, "productCode", "CCSIRS")))));
        assertThat(imported.ok()).as(imported.body()).isTrue();
        assertThat(imported.json().get("status").asString()).as(imported.body()).isEqualTo("SUCCEEDED");
        assertThat(imported.json().get("totalRows").asInt())
                .isEqualTo(SampleWorkbookGenerator.CSV_ROW_COUNT);
    }
}
