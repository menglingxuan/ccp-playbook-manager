package ac.domain.table;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.CreationHelper;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TableReaderTest {

    @TempDir
    Path tempDir;

    @Test
    void readsValuesDatesAndFormulasFromXlsx() throws IOException {
        Path file = tempDir.resolve("sample.xlsx");
        try (Workbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("IRS");
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("Trade ID");
            header.createCell(1).setCellValue("Trade Date");
            header.createCell(2).setCellValue("Notional");
            header.createCell(3).setCellValue("Rate");

            Row row = sheet.createRow(1);
            row.createCell(0).setCellValue("IRS-0001");
            CreationHelper helper = workbook.getCreationHelper();
            CellStyle dateStyle = workbook.createCellStyle();
            dateStyle.setDataFormat(helper.createDataFormat().getFormat("yyyy-mm-dd"));
            Cell dateCell = row.createCell(1);
            dateCell.setCellValue(LocalDate.of(2026, 11, 3));
            dateCell.setCellStyle(dateStyle);
            row.createCell(2).setCellFormula("3*4");
            row.createCell(3).setCellValue(0.0325);

            try (OutputStream out = Files.newOutputStream(file)) {
                workbook.write(out);
            }
        }

        RawTable table = ExcelTableReader.readSheet(file, 0, 1000);

        assertThat(table.sheetName()).isEqualTo("IRS");
        assertThat(table.row(1).text(0)).isEqualTo("IRS-0001");
        assertThat(table.row(1).text(1)).isEqualTo("2026-11-03");
        assertThat(table.row(1).formula(2)).isEqualTo("=3*4");
        assertThat(table.row(1).text(3)).isEqualTo("0.0325");
    }

    @Test
    void listsSheetsWithMetadata() throws IOException {
        Path file = tempDir.resolve("multi.xlsx");
        try (Workbook workbook = new XSSFWorkbook()) {
            workbook.createSheet("First").createRow(0).createCell(0).setCellValue("A");
            workbook.createSheet("Second").createRow(0).createCell(0).setCellValue("B");
            try (OutputStream out = Files.newOutputStream(file)) {
                workbook.write(out);
            }
        }

        assertThat(ExcelTableReader.listSheets(file, 10)).extracting(SheetMeta::name)
                .containsExactly("First", "Second");
        assertThatThrownBy(() -> ExcelTableReader.listSheets(file, 1))
                .isInstanceOf(TableReadException.class);
    }

    @Test
    void readsSemicolonSeparatedCsvWithBom() throws IOException {
        Path file = tempDir.resolve("cases.csv");
        String content = "\uFEFFTrade ID;Notional\nIRS-1;1 000,50\n";
        Files.writeString(file, content, StandardCharsets.UTF_8);

        RawTable table = CsvTableReader.read(file, 100);

        assertThat(table.sheetName()).isEqualTo("cases");
        assertThat(table.text(0, 0)).isEqualTo("Trade ID");
        assertThat(table.text(1, 1)).isEqualTo("1 000,50");
    }

    @Test
    void detectsDelimiterFromContent() {
        assertThat(CsvTableReader.detectDelimiter("a,b,c\n1,2,3")).isEqualTo(',');
        assertThat(CsvTableReader.detectDelimiter("a;b;c\n1;2;3")).isEqualTo(';');
        assertThat(CsvTableReader.detectDelimiter("a\tb\tc\n1\t2\t3")).isEqualTo('\t');
        assertThat(CsvTableReader.detectDelimiter("a|b|c\n1|2|3")).isEqualTo('|');
    }

    @Test
    void enforcesRowLimit() throws IOException {
        Path file = tempDir.resolve("big.csv");
        Files.writeString(file, "a\n1\n2\n", StandardCharsets.UTF_8);

        assertThatThrownBy(() -> CsvTableReader.read(file, 1)).isInstanceOf(TableReadException.class);
    }
}
