package ac.core.service.definition;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

/**
 * Reads and writes the Excel form of a definition export.
 *
 * <p>Every {@link DefinitionTable} becomes one worksheet whose first row holds the column names of
 * {@link DefinitionTable#columns()}. The format is meant to be edited by hand, so the reader accepts
 * extra columns, ignores the order of the columns and tolerates sheets it does not know.
 */
public final class DefinitionExcel {

    private DefinitionExcel() {
    }

    /** Writes one workbook holding a sheet per table of the bundle. */
    public static byte[] write(DefinitionBundle bundle) {
        try (XSSFWorkbook workbook = new XSSFWorkbook();
                ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            CellStyle header = headerStyle(workbook);
            for (DefinitionTable table : DefinitionTable.values()) {
                List<DefinitionBundle.Row> rows = bundle.rows(table);
                if (rows.isEmpty() && !bundle.tables().containsKey(table)) {
                    continue;
                }
                Sheet sheet = workbook.createSheet(table.sheet());
                Row head = sheet.createRow(0);
                List<String> columns = table.columns();
                for (int i = 0; i < columns.size(); i++) {
                    Cell cell = head.createCell(i);
                    cell.setCellValue(columns.get(i));
                    cell.setCellStyle(header);
                    sheet.setColumnWidth(i, columnWidth(columns.get(i)));
                }
                int index = 1;
                for (DefinitionBundle.Row row : rows) {
                    Row target = sheet.createRow(index++);
                    for (int i = 0; i < columns.size(); i++) {
                        String value = row.get(columns.get(i));
                        if (value != null) {
                            target.createCell(i).setCellValue(value);
                        }
                    }
                }
                sheet.createFreezePane(0, 1);
            }
            workbook.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("Cannot write the definition workbook: " + e.getMessage(), e);
        }
    }

    /**
     * Reads a workbook into a bundle.
     *
     * @param scope  tables expected in the file; sheets of the other scope are ignored
     * @param stream workbook content
     * @param tenant tenant the rows belong to
     * @throws IllegalArgumentException when the file is not a readable workbook or a sheet lacks a
     *                                  required column
     */
    public static DefinitionBundle read(DefinitionScope scope, InputStream stream, String tenantId) {
        try (XSSFWorkbook workbook = new XSSFWorkbook(stream)) {
            DataFormatter formatter = new DataFormatter(Locale.ROOT);
            Map<DefinitionTable, List<DefinitionBundle.Row>> tables = new LinkedHashMap<>();
            for (int index = 0; index < workbook.getNumberOfSheets(); index++) {
                Sheet sheet = workbook.getSheetAt(index);
                DefinitionTable table = DefinitionTable.bySheet(sheet.getSheetName()).orElse(null);
                if (table == null || table.scope() != scope) {
                    continue;
                }
                tables.put(table, readSheet(sheet, table, formatter));
            }
            if (tables.isEmpty()) {
                throw new IllegalArgumentException("The file has no sheet of scope " + scope.name()
                        + "; expected one of " + scope.tables().stream().map(DefinitionTable::sheet).toList());
            }
            return new DefinitionBundle(tenantId, tables);
        } catch (IOException e) {
            throw new IllegalArgumentException("Cannot read the workbook: " + e.getMessage(), e);
        }
    }

    private static List<DefinitionBundle.Row> readSheet(Sheet sheet, DefinitionTable table,
            DataFormatter formatter) {
        Row header = sheet.getRow(sheet.getFirstRowNum());
        if (header == null) {
            return List.of();
        }
        Map<Integer, String> columns = new LinkedHashMap<>();
        for (Cell cell : header) {
            String name = formatter.formatCellValue(cell).trim().toLowerCase(Locale.ROOT);
            if (!name.isEmpty()) {
                columns.put(cell.getColumnIndex(), name);
            }
        }
        List<String> missing = table.columns().stream()
                .filter(required -> !columns.containsValue(required))
                .toList();
        // only the columns that carry the key are strictly required, the rest may stay absent
        List<String> keyColumns = DefinitionKeys.keyColumns(table);
        List<String> missingKeys = missing.stream().filter(keyColumns::contains).toList();
        if (!missingKeys.isEmpty()) {
            throw new IllegalArgumentException("Sheet " + table.sheet() + " is missing the column(s) "
                    + String.join(", ", missingKeys));
        }
        List<DefinitionBundle.Row> rows = new ArrayList<>();
        for (int index = sheet.getFirstRowNum() + 1; index <= sheet.getLastRowNum(); index++) {
            Row source = sheet.getRow(index);
            if (source == null) {
                continue;
            }
            DefinitionBundle.Row row = new DefinitionBundle.Row();
            boolean empty = true;
            for (Map.Entry<Integer, String> column : columns.entrySet()) {
                Cell cell = source.getCell(column.getKey());
                String value = cell == null ? "" : formatter.formatCellValue(cell).trim();
                if (!value.isEmpty()) {
                    empty = false;
                    row.put(column.getValue(), value);
                }
            }
            if (!empty) {
                rows.add(row);
            }
        }
        return rows;
    }

    private static CellStyle headerStyle(XSSFWorkbook workbook) {
        CellStyle style = workbook.createCellStyle();
        Font font = workbook.createFont();
        font.setBold(true);
        style.setFont(font);
        return style;
    }

    private static int columnWidth(String column) {
        int width = Math.max(12, column.length() + 4);
        return Math.min(60, width) * 256;
    }

    /** Reads a cell value as text; used by the reader and by tests. */
    static String text(Cell cell, DataFormatter formatter) {
        if (cell == null) {
            return "";
        }
        if (cell.getCellType() == CellType.BOOLEAN) {
            return Boolean.toString(cell.getBooleanCellValue());
        }
        return formatter.formatCellValue(cell).trim();
    }
}
