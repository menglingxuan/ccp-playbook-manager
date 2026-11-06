package ac.domain.table;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.FormulaError;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.openxml4j.util.ZipSecureFile;

/**
 * Reads xls/xlsx sheets into {@link RawTable}. Formula cells keep both the cached value and the
 * formula text so the import pipeline can store both.
 */
public final class ExcelTableReader {

    static {
        // Excel files produced by business users are highly compressible; the default guard rejects them.
        ZipSecureFile.setMinInflateRatio(0.01);
    }

    public static final int DEFAULT_MAX_ROWS = 200_000;

    private ExcelTableReader() {
    }

    public static List<SheetMeta> listSheets(Path file, int maxSheets) {
        try (Workbook workbook = open(file)) {
            int count = workbook.getNumberOfSheets();
            if (count > maxSheets) {
                throw new TableReadException("Workbook has " + count + " sheets, limit is " + maxSheets);
            }
            List<SheetMeta> metas = new ArrayList<>(count);
            for (int index = 0; index < count; index++) {
                Sheet sheet = workbook.getSheetAt(index);
                // Only the first rows are inspected: header discovery never looks deeper.
                int columns = 0;
                int lastProbe = Math.min(sheet.getLastRowNum(), sheet.getFirstRowNum() + 50);
                for (int rowIndex = sheet.getFirstRowNum(); rowIndex <= lastProbe; rowIndex++) {
                    Row row = sheet.getRow(rowIndex);
                    if (row != null) {
                        columns = Math.max(columns, row.getLastCellNum());
                    }
                }
                metas.add(new SheetMeta(index, sheet.getSheetName(), sheet.getLastRowNum() + 1, columns,
                        workbook.isSheetHidden(index)));
            }
            return metas;
        } catch (IOException | RuntimeException e) {
            throw wrap(file, e);
        }
    }

    public static RawTable readSheet(Path file, int sheetIndex, int maxRows) {
        try (Workbook workbook = open(file)) {
            if (sheetIndex < 0 || sheetIndex >= workbook.getNumberOfSheets()) {
                throw new TableReadException("Sheet index " + sheetIndex + " does not exist");
            }
            Sheet sheet = workbook.getSheetAt(sheetIndex);
            int firstRow = sheet.getFirstRowNum();
            int lastRow = sheet.getLastRowNum();
            int rowCount = lastRow - firstRow + 1;
            if (rowCount > maxRows) {
                throw new TableReadException(
                        "Sheet '" + sheet.getSheetName() + "' has " + rowCount + " rows, limit is " + maxRows);
            }

            RawTable.Builder builder = RawTable.builder(sheet.getSheetName());
            for (int rowIndex = firstRow; rowIndex <= lastRow; rowIndex++) {
                Row row = sheet.getRow(rowIndex);
                builder.addRow(convertRow(row));
            }
            return builder.build();
        } catch (IOException | RuntimeException e) {
            throw wrap(file, e);
        }
    }

    public static RawTable readFirstSheet(Path file, int maxRows) {
        return readSheet(file, 0, maxRows);
    }

    private static Workbook open(Path file) throws IOException {
        if (file == null || !Files.isRegularFile(file)) {
            throw new TableReadException("File does not exist: " + file);
        }
        return WorkbookFactory.create(file.toFile(), null, true);
    }

    private static RawRow convertRow(Row row) {
        if (row == null) {
            return RawRow.plain(new String[0]);
        }
        int columns = Math.max(row.getLastCellNum(), 0);
        if (columns == 0) {
            return RawRow.plain(new String[0]);
        }
        String[] texts = new String[columns];
        String[] formulas = null;
        String[] errors = null;
        for (int column = 0; column < columns; column++) {
            Cell cell = row.getCell(column, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
            if (cell == null) {
                texts[column] = "";
                continue;
            }
            if (cell.getCellType() == CellType.FORMULA) {
                if (formulas == null) {
                    formulas = new String[columns];
                    errors = new String[columns];
                }
                String formulaText = null;
                try {
                    formulaText = cell.getCellFormula();
                } catch (RuntimeException e) {
                    errors[column] = "SHARED_FORMULA_NO_TEXT";
                }
                if (formulaText == null || formulaText.isBlank()) {
                    errors[column] = "SHARED_FORMULA_NO_TEXT";
                } else {
                    formulas[column] = "=" + formulaText;
                }
                CellType cachedType = cell.getCachedFormulaResultType();
                texts[column] = rawText(cell, cachedType);
                if (cachedType == CellType.ERROR) {
                    errors[column] = "FORMULA_ERROR";
                }
            } else {
                texts[column] = rawText(cell, cell.getCellType());
            }
        }
        return new RawRow(texts, formulas, errors);
    }

    private static String rawText(Cell cell, CellType type) {
        return switch (type) {
            case STRING -> cell.getStringCellValue();
            case NUMERIC -> numericText(cell);
            case BOOLEAN -> Boolean.toString(cell.getBooleanCellValue());
            case ERROR -> FormulaError.forInt(cell.getErrorCellValue()).getString();
            case BLANK, _NONE -> "";
            default -> "";
        };
    }

    private static String numericText(Cell cell) {
        if (DateUtil.isCellDateFormatted(cell)) {
            LocalDateTime value = cell.getLocalDateTimeCellValue();
            if (value == null) {
                return "";
            }
            if (value.toLocalTime().equals(LocalTime.MIDNIGHT)) {
                return value.toLocalDate().toString();
            }
            return value.toString();
        }
        return BigDecimal.valueOf(cell.getNumericCellValue()).stripTrailingZeros().toPlainString();
    }

    private static TableReadException wrap(Path file, Exception e) {
        if (e instanceof TableReadException tableReadException) {
            return tableReadException;
        }
        return new TableReadException("Cannot read workbook " + file.getFileName() + ": " + e.getMessage(), e);
    }
}
