package ac.domain.table;

import ac.domain.product.FieldCodec;
import ac.domain.product.FieldDef;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.CellValue;
import org.apache.poi.ss.usermodel.CreationHelper;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.FormulaError;
import org.apache.poi.ss.usermodel.FormulaEvaluator;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;

/**
 * Recalculates the formulas of an imported sheet after the current case values have been written
 * back onto it.
 *
 * <p>Export uses this so the CSV carries freshly calculated values instead of the cached values the
 * source workbook happened to carry when it was imported. Cells the evaluator cannot compute keep
 * their stored value and are reported as warnings; nothing is silently dropped.
 */
public final class WorkbookRecalculator {

    /** Excel keeps 15 significant digits, which also hides binary floating point noise. */
    private static final MathContext EXCEL_CONTEXT = new MathContext(15, RoundingMode.HALF_UP);

    private WorkbookRecalculator() {
    }

    /**
     * One cell to write back.
     *
     * @param column        1-based column number
     * @param key           value key returned in the result map
     * @param field         field definition used to restore the native cell type; {@code null} writes text
     * @param canonicalText stored value in canonical text form, ignored when {@code formula} is set
     * @param formula       formula text (with or without a leading {@code =}), {@code null} for plain values
     */
    public record CellTarget(int column, String key, FieldDef field, String canonicalText, String formula) {
    }

    /**
     * One imported row.
     *
     * @param caseId    case identifier returned in the result map
     * @param sourceRow 1-based row number inside the sheet
     * @param cells     mapped cells of that row
     */
    public record CaseTarget(String caseId, int sourceRow, List<CellTarget> cells) {

        public CaseTarget {
            cells = List.copyOf(cells);
        }
    }

    /**
     * Recalculation outcome.
     *
     * @param values      case id to field key to recalculated canonical text
     * @param evaluated   number of formula cells that were computed
     * @param failed      number of formula cells that could not be computed
     * @param warnings    human readable reasons, capped by the caller if needed
     */
    public record Result(Map<String, Map<String, String>> values, int evaluated, int failed,
            List<String> warnings) {

        public Result {
            values = values == null ? Map.of() : values;
            warnings = warnings == null ? List.of() : List.copyOf(warnings);
        }

        public static Result empty() {
            return new Result(Map.of(), 0, 0, List.of());
        }
    }

    public static Result recalculate(Path workbookFile, String sheetName, List<CaseTarget> cases) {
        if (workbookFile == null || !Files.isRegularFile(workbookFile) || cases.isEmpty()) {
            return Result.empty();
        }
        // POI rewrites the file it opened, so work on a throw-away copy: the baseline must stay
        // byte identical, which is what the hash check of the next export relies on.
        Path working = null;
        try {
            working = Files.createTempFile("recalc-", "-" + workbookFile.getFileName());
            Files.copy(workbookFile, working, StandardCopyOption.REPLACE_EXISTING);
            try (Workbook workbook = WorkbookFactory.create(working.toFile(), null, false)) {
                Sheet sheet = resolveSheet(workbook, sheetName);
                if (sheet == null) {
                    return new Result(Map.of(), 0, 0,
                            List.of("Sheet '" + sheetName + "' is missing from the baseline"));
                }
                return evaluate(workbook, sheet, cases);
            }
        } catch (IOException | RuntimeException e) {
            return new Result(Map.of(), 0, 0,
                    List.of("Cannot recalculate the baseline workbook: " + e.getMessage()));
        } finally {
            if (working != null) {
                try {
                    Files.deleteIfExists(working);
                } catch (IOException ignored) {
                    // a leftover temp file is harmless
                }
            }
        }
    }

    private static Sheet resolveSheet(Workbook workbook, String sheetName) {
        if (sheetName == null || sheetName.isBlank()) {
            return workbook.getNumberOfSheets() > 0 ? workbook.getSheetAt(0) : null;
        }
        Sheet byName = workbook.getSheet(sheetName);
        return byName != null ? byName : workbook.getNumberOfSheets() > 0 ? workbook.getSheetAt(0) : null;
    }

    private static Result evaluate(Workbook workbook, Sheet sheet, List<CaseTarget> cases) {
        Map<String, Map<String, String>> values = new LinkedHashMap<>();
        List<ValidationTarget> formulaCells = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        int evaluated = 0;
        int failed = 0;

        for (CaseTarget target : cases) {
            Row row = sheet.getRow(target.sourceRow() - 1);
            if (row == null) {
                row = sheet.createRow(target.sourceRow() - 1);
            }
            Map<String, String> caseValues = new LinkedHashMap<>();
            for (CellTarget cellTarget : target.cells()) {
                Cell cell = row.getCell(cellTarget.column() - 1, Row.MissingCellPolicy.CREATE_NULL_AS_BLANK);
                if (cellTarget.formula() != null && !cellTarget.formula().isBlank()) {
                    if (writeFormula(cell, cellTarget)) {
                        formulaCells.add(new ValidationTarget(target.caseId(), cellTarget.key(),
                                cellTarget.field(), cell));
                        continue;
                    }
                    warnings.add("Column " + cellTarget.column() + " of row " + target.sourceRow()
                            + " kept its stored value: the formula could not be restored");
                } else {
                    writeValue(cell, cellTarget);
                }
                putIfNotEmpty(caseValues, cellTarget.key(), cellTarget.canonicalText());
            }
            values.put(target.caseId(), caseValues);
        }

        if (formulaCells.isEmpty()) {
            return new Result(values, 0, 0, warnings);
        }
        CreationHelper helper = workbook.getCreationHelper();
        FormulaEvaluator evaluator = helper.createFormulaEvaluator();
        evaluator.clearAllCachedResultValues();
        for (ValidationTarget target : formulaCells) {
            try {
                CellValue computed = evaluator.evaluate(target.cell());
                String text = text(target.cell(), target.field(), computed);
                if (text == null) {
                    failed++;
                    warnings.add("Formula of column " + (target.cell().getColumnIndex() + 1) + " in row "
                            + (target.cell().getRowIndex() + 1) + " evaluated to an error");
                    continue;
                }
                putIfNotEmpty(values.get(target.caseId()), target.fieldKey(), text);
                evaluated++;
            } catch (RuntimeException e) {
                failed++;
                warnings.add("Formula of column " + (target.cell().getColumnIndex() + 1) + " in row "
                        + (target.cell().getRowIndex() + 1) + " is not supported: " + e.getMessage());
            }
        }
        return new Result(values, evaluated, failed, warnings);
    }

    private static boolean writeFormula(Cell cell, CellTarget target) {
        String formula = target.formula().trim();
        if (formula.startsWith("=")) {
            formula = formula.substring(1);
        }
        try {
            cell.setCellFormula(formula);
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Restores the native Excel type so following formulas see numbers and dates, not text. */
    private static void writeValue(Cell cell, CellTarget target) {
        Object value;
        try {
            value = target.field() == null
                    ? blankToNull(target.canonicalText()) : FieldCodec.parse(target.field(), target.canonicalText());
        } catch (RuntimeException e) {
            cell.setCellValue(target.canonicalText() == null ? "" : target.canonicalText());
            return;
        }
        if (value == null) {
            cell.setBlank();
        } else if (value instanceof BigDecimal decimal) {
            cell.setCellValue(decimal.doubleValue());
        } else if (value instanceof Integer integer) {
            cell.setCellValue(integer.doubleValue());
        } else if (value instanceof LocalDate date) {
            cell.setCellValue(date);
        } else if (value instanceof LocalDateTime dateTime) {
            cell.setCellValue(dateTime);
        } else if (value instanceof Boolean flag) {
            cell.setCellValue(flag);
        } else {
            cell.setCellValue(String.valueOf(value));
        }
    }

    /** Canonical text of a computed cell, or {@code null} when the formula produced an error. */
    private static String text(Cell cell, FieldDef field, CellValue value) {
        CellType type = value.getCellType();
        return switch (type) {
            case STRING -> value.getStringValue();
            case NUMERIC -> numberText(cell, field, value.getNumberValue());
            case BOOLEAN -> Boolean.toString(value.getBooleanValue());
            case ERROR -> null;
            default -> "";
        };
    }

    /**
     * Formats a computed number. A date field wins over the cell style, because a formula cell that
     * carries no date format would otherwise be exported as a raw serial number.
     */
    private static String numberText(Cell cell, FieldDef field, double raw) {
        boolean date = field != null && field.type() == ac.domain.product.FieldType.DATE
                || DateUtil.isCellDateFormatted(cell);
        if (date) {
            LocalDateTime value = DateUtil.getLocalDateTime(raw);
            if (value == null) {
                return "";
            }
            return value.toLocalTime().equals(LocalTime.MIDNIGHT)
                    ? value.toLocalDate().toString() : value.toString();
        }
        return new BigDecimal(raw).round(EXCEL_CONTEXT).stripTrailingZeros().toPlainString();
    }

    private static void putIfNotEmpty(Map<String, String> values, String key, String text) {
        if (values != null && text != null && !text.isEmpty()) {
            values.put(key, text);
        }
    }

    private static String blankToNull(String text) {
        return text == null || text.isBlank() ? null : text;
    }

    private record ValidationTarget(String caseId, String fieldKey, FieldDef field, Cell cell) {
    }
}
