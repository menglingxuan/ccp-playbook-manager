package ac.domain.table;

import static org.assertj.core.api.Assertions.assertThat;

import ac.domain.product.FieldCodec;
import ac.domain.product.FieldDef;
import ac.domain.product.FieldRole;
import ac.domain.product.FieldType;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Boolean cells keep the spelling Excel shows, so an exported CSV matches the source file. */
class BooleanCellTextTest {

    @TempDir
    Path directory;

    private static FieldDef bool(String key) {
        return FieldDef.of(key, 1, key, List.of(), FieldType.BOOLEAN, FieldRole.BUSINESS, false, false,
                null, null, 1);
    }

    @Test
    void booleanCellsAreReadAsUpperCaseText() throws IOException {
        Path file = directory.resolve("bools.xlsx");
        try (Workbook workbook = new XSSFWorkbook(); OutputStream out = Files.newOutputStream(file)) {
            Sheet sheet = workbook.createSheet("IRS");
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("US Person");
            header.createCell(1).setCellValue("Cleared");
            header.createCell(2).setCellValue("Flag");
            Row row = sheet.createRow(1);
            row.createCell(0).setCellValue(true);
            row.createCell(1).setCellValue(false);
            // a formula cell needs a cached result before the reader knows its type
            var formula = (org.apache.poi.ss.usermodel.Cell) row.createCell(2);
            formula.setCellFormula("1=1");
            workbook.getCreationHelper().createFormulaEvaluator().evaluateFormulaCell(formula);
            workbook.write(out);
        }

        RawTable table = ExcelTableReader.readSheet(file, 0, 100);

        assertThat(table.row(1).text(0)).isEqualTo("TRUE");
        assertThat(table.row(1).text(1)).isEqualTo("FALSE");
        assertThat(table.row(1).text(2)).isEqualTo("TRUE");
    }

    @Test
    void recalculatedBooleanResultsKeepUpperCaseText() throws IOException {
        Path file = directory.resolve("boolFormula.xlsx");
        try (Workbook workbook = new XSSFWorkbook(); OutputStream out = Files.newOutputStream(file)) {
            Sheet sheet = workbook.createSheet("IRS");
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("Notional");
            header.createCell(1).setCellValue("US Person");
            Row row = sheet.createRow(1);
            row.createCell(0).setCellValue(1000);
            CellStyle style = workbook.createCellStyle();
            row.createCell(1).setCellFormula("A2>500");
            row.getCell(1).setCellStyle(style);
            workbook.write(out);
        }

        WorkbookRecalculator.Result result = WorkbookRecalculator.recalculate(file, "IRS", List.of(
                new WorkbookRecalculator.CaseTarget("case-1", 2, List.of(
                        new WorkbookRecalculator.CellTarget(1, "notional", bool("notional"), "1000", null),
                        new WorkbookRecalculator.CellTarget(2, "usPerson", bool("usPerson"), "",
                                "=A2>500")))));

        assertThat(result.values().get("case-1")).containsEntry("usPerson", "TRUE");
        assertThat(result.failed()).isZero();
    }

    @Test
    void formattedBooleansUseUpperCaseText() {
        FieldDef field = bool("usPerson");

        assertThat(FieldCodec.format(field, Boolean.TRUE)).isEqualTo("TRUE");
        assertThat(FieldCodec.format(field, Boolean.FALSE)).isEqualTo("FALSE");
        // parsing stays case insensitive, so a lower case source value is still accepted
        assertThat(FieldCodec.parse(field, "true")).isEqualTo(Boolean.TRUE);
        assertThat(FieldCodec.parse(field, "FALSE")).isEqualTo(Boolean.FALSE);
    }
}
