package ac.domain.table;

import static org.assertj.core.api.Assertions.assertThat;

import ac.domain.product.FieldDef;
import ac.domain.product.FieldRole;
import ac.domain.product.FieldType;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Recalculation of a stored workbook: stale caches, edited inputs, cross sheet refs and failures. */
class WorkbookRecalculatorTest {

    @TempDir
    Path directory;

    private static FieldDef decimal(String key) {
        return FieldDef.of(key, 1, key, List.of(), FieldType.DECIMAL, FieldRole.BUSINESS, false, false,
                null, null, 1);
    }

    private static FieldDef date(String key) {
        return FieldDef.of(key, 1, key, List.of(), FieldType.DATE, FieldRole.BUSINESS, false, false,
                null, null, 1);
    }

    @Test
    void replacesStaleCachedValuesWithFreshResults() throws IOException {
        Path file = directory.resolve("book.xlsx");
        try (Workbook workbook = new XSSFWorkbook(); OutputStream out = Files.newOutputStream(file)) {
            Sheet sheet = workbook.createSheet("IRS");
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("Notional");
            header.createCell(1).setCellValue("Rate");
            Row row = sheet.createRow(1);
            row.createCell(0).setCellValue(1000);
            // written without evaluation, so the imported cached value is 0
            row.createCell(1).setCellFormula("A2/100");
            workbook.write(out);
        }

        WorkbookRecalculator.Result result = WorkbookRecalculator.recalculate(file, "IRS", List.of(
                new WorkbookRecalculator.CaseTarget("case-1", 2, List.of(
                        new WorkbookRecalculator.CellTarget(1, "notional", decimal("notional"), "1500", null),
                        new WorkbookRecalculator.CellTarget(2, "rate", decimal("rate"), "0", "=A2/100")))));

        assertThat(result.values().get("case-1")).containsEntry("rate", "15");
        assertThat(result.evaluated()).isEqualTo(1);
        assertThat(result.failed()).isZero();
    }

    @Test
    void resolvesReferencesToOtherSheetsAndUntouchedRows() throws IOException {
        Path file = directory.resolve("cross.xlsx");
        try (Workbook workbook = new XSSFWorkbook(); OutputStream out = Files.newOutputStream(file)) {
            Sheet rates = workbook.createSheet("Rates");
            rates.createRow(0).createCell(0).setCellValue(0.5);
            Sheet sheet = workbook.createSheet("IRS");
            sheet.createRow(0).createCell(0).setCellValue("Notional");
            sheet.createRow(1).createCell(0).setCellValue(1000);
            sheet.createRow(2).createCell(0).setCellValue(2000);
            // row 4 is outside the imported range but stays available as a reference
            sheet.createRow(3).createCell(0).setCellValue(3000);
            sheet.createRow(1).createCell(1).setCellFormula("A2*Rates!A1");
            workbook.write(out);
        }

        WorkbookRecalculator.Result result = WorkbookRecalculator.recalculate(file, "IRS", List.of(
                new WorkbookRecalculator.CaseTarget("case-1", 2, List.of(
                        new WorkbookRecalculator.CellTarget(1, "notional", decimal("notional"), "1000", null),
                        new WorkbookRecalculator.CellTarget(2, "rate", decimal("rate"), "0", "=A2*Rates!A1")))));

        assertThat(result.values().get("case-1")).containsEntry("rate", "500");
    }

    @Test
    void writesDatesBackAsNativeValues() throws IOException {
        Path file = directory.resolve("dates.xlsx");
        try (Workbook workbook = new XSSFWorkbook(); OutputStream out = Files.newOutputStream(file)) {
            Sheet sheet = workbook.createSheet("IRS");
            sheet.createRow(0).createCell(0).setCellValue("Trade Date");
            sheet.createRow(1).createCell(0).setCellValue(0);
            sheet.createRow(1).createCell(1).setCellFormula("A2+30");
            workbook.write(out);
        }

        WorkbookRecalculator.Result result = WorkbookRecalculator.recalculate(file, "IRS", List.of(
                new WorkbookRecalculator.CaseTarget("case-1", 2, List.of(
                        new WorkbookRecalculator.CellTarget(1, "tradeDate", date("tradeDate"), "2026-01-01",
                                null),
                        new WorkbookRecalculator.CellTarget(2, "maturityDate", date("maturityDate"), "",
                                "=A2+30")))));

        assertThat(result.values().get("case-1")).containsEntry("maturityDate", "2026-01-31");
    }

    @Test
    void reportsUnsupportedFormulasAndKeepsStoredValues() throws IOException {
        Path file = directory.resolve("unsupported.xlsx");
        try (Workbook workbook = new XSSFWorkbook(); OutputStream out = Files.newOutputStream(file)) {
            Sheet sheet = workbook.createSheet("IRS");
            sheet.createRow(0).createCell(0).setCellValue("Rate");
            sheet.createRow(1).createCell(0).setCellValue(0);
            sheet.createRow(1).createCell(1).setCellFormula("WEBSERVICE(\"http://localhost\")");
            workbook.write(out);
        }

        WorkbookRecalculator.Result result = WorkbookRecalculator.recalculate(file, "IRS", List.of(
                new WorkbookRecalculator.CaseTarget("case-1", 2, List.of(
                        new WorkbookRecalculator.CellTarget(1, "rate", decimal("rate"), "0.03", null),
                        new WorkbookRecalculator.CellTarget(2, "other", decimal("other"), "0.04",
                                "=WEBSERVICE(\"http://localhost\")")))));

        assertThat(result.failed()).isEqualTo(1);
        assertThat(result.warnings()).isNotEmpty();
        assertThat(result.values().get("case-1")).doesNotContainKey("other");
    }

    @Test
    void keepsTheStoredValueWhenTheFormulaTextCannotBeRestored() throws IOException {
        Path file = directory.resolve("broken.xlsx");
        try (Workbook workbook = new XSSFWorkbook(); OutputStream out = Files.newOutputStream(file)) {
            Sheet sheet = workbook.createSheet("IRS");
            sheet.createRow(0).createCell(0).setCellValue("Rate");
            sheet.createRow(1).createCell(0).setCellValue(0);
            workbook.write(out);
        }

        WorkbookRecalculator.Result result = WorkbookRecalculator.recalculate(file, "IRS", List.of(
                new WorkbookRecalculator.CaseTarget("case-1", 2, List.of(
                        new WorkbookRecalculator.CellTarget(1, "rate", decimal("rate"), "0.03", "=SUM(")))));

        assertThat(result.values().get("case-1")).containsEntry("rate", "0.03");
        assertThat(result.warnings()).isNotEmpty();
    }

    @Test
    void emptyTargetsShortCircuit() {
        assertThat(WorkbookRecalculator.recalculate(directory.resolve("missing.xlsx"), "IRS", List.of())
                .values()).isEmpty();
    }

    @Test
    void missingSheetFallsBackToTheFirstSheet() throws IOException {
        Path file = directory.resolve("named.xlsx");
        try (Workbook workbook = new XSSFWorkbook(); OutputStream out = Files.newOutputStream(file)) {
            Sheet sheet = workbook.createSheet("Only");
            Row row = sheet.createRow(0);
            row.createCell(0).setCellValue(10);
            row.createCell(1).setCellFormula("A1*2");
            workbook.write(out);
        }

        WorkbookRecalculator.Result result = WorkbookRecalculator.recalculate(file, "Renamed", List.of(
                new WorkbookRecalculator.CaseTarget("case-1", 1, List.of(
                        new WorkbookRecalculator.CellTarget(2, "double", decimal("double"), "", "=A1*2")))));

        assertThat(result.values().get("case-1")).containsEntry("double", "20");
    }

    @Test
    void dateCellsKeepTheirCalendarValue() {
        assertThat(LocalDate.of(2026, 1, 1).plusDays(30).toString()).isEqualTo("2026-01-31");
    }
}
