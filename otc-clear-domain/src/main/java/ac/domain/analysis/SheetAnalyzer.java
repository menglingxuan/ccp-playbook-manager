package ac.domain.analysis;

import ac.domain.casefile.CaseRow;
import ac.domain.casefile.ConversionContext;
import ac.domain.casefile.IssueLevel;
import ac.domain.casefile.RowConverter;
import ac.domain.casefile.ValidationIssue;
import ac.domain.layout.ColumnAssignment;
import ac.domain.layout.ColumnKind;
import ac.domain.layout.ColumnLayoutResolver;
import ac.domain.layout.LayoutException;
import ac.domain.layout.SheetLayout;
import ac.domain.product.ColumnNames;
import ac.domain.product.FieldDef;
import ac.domain.product.ProductSchema;
import ac.domain.table.RawTable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Builds previews and full analyses for one sheet. */
public final class SheetAnalyzer {

    public static final int DEFAULT_SAMPLE_LIMIT = 20;

    private SheetAnalyzer() {
    }

    /**
     * Resolves the layout, honouring explicitly provided wizard parameters.
     *
     * @return resolution result; {@code layout} is empty when resolution failed
     */
    public static LayoutResolution resolveLayout(ProductSchema schema, RawTable table,
            PreviewOptions options) {
        int headerRow = options.headerRow() > 0
                ? options.headerRow()
                : SheetDetector.detectHeaderRow(schema, table);
        int tradeStartColumn = options.tradeStartColumn() > 0
                ? options.tradeStartColumn()
                : SheetDetector.detectTradeStartColumn(schema, table, headerRow);
        try {
            SheetLayout layout = ColumnLayoutResolver.resolve(schema, table, headerRow, tradeStartColumn);
            if (options.dataStartRow() > 0 && options.dataStartRow() != layout.dataStartRow()) {
                layout = new SheetLayout(layout.headerRow(), options.dataStartRow(),
                        layout.tradeStartColumn(), layout.tradeEndColumn(), layout.lastColumn(),
                        layout.assignments(), layout.missingFields(), layout.blockContiguous());
            }
            List<ValidationIssue> issues = new ArrayList<>(layoutIssues(schema, table, layout));
            if (options.dataEndRow() > 0 && options.dataEndRow() < layout.dataStartRow()) {
                issues.add(ValidationIssue.error("INVALID_DATA_RANGE",
                        "The data end row " + options.dataEndRow() + " is above the data start row "
                                + layout.dataStartRow()));
            }
            boolean valid = issues.stream().noneMatch(issue -> issue.level() == IssueLevel.ERROR);
            return new LayoutResolution(Optional.of(layout), issues, valid);
        } catch (LayoutException e) {
            List<ValidationIssue> issues = List.of(ValidationIssue.error(e.code(), e.getMessage()));
            return new LayoutResolution(Optional.empty(), issues, false);
        }
    }

    public static SheetPreview preview(ProductSchema schema, RawTable table, PreviewOptions options,
            int sampleLimit) {
        LayoutResolution resolution = resolveLayout(schema, table, options);
        if (resolution.layout().isEmpty()) {
            return new SheetPreview(schema.code(), false, resolution.issues(), null, List.of(),
                    new PreviewStats(0, 0, 0, 0));
        }
        SheetLayout layout = resolution.layout().get();
        ConversionContext context = new ConversionContext(schema, layout, table.sheetName(),
                table.sheetName(), 32, false);

        List<SampleRow> samples = new ArrayList<>();
        int sampleInvalid = 0;
        int rowNo = 0;
        int lastRow = lastRowIndex(table, options);
        for (int rowIndex = layout.dataStartRow() - 1; rowIndex < lastRow && samples.size() < sampleLimit;
                rowIndex++) {
            CaseRow row = RowConverter.convert(context, table, rowIndex + 1, rowNo + 1);
            if (row.empty()) {
                continue;
            }
            rowNo++;
            if (!row.valid()) {
                sampleInvalid++;
            }
            samples.add(new SampleRow(row.sourceRow(), row.data(), row.formulas(), row.managed(),
                    row.extras(), row.issues(), row.valid()));
        }

        int dataRows = countNonEmptyRows(table, layout.dataStartRow(), lastRow);
        PreviewStats stats = new PreviewStats(dataRows, layout.mappedTradeColumnCount(),
                layout.managedColumnCount(), sampleInvalid);
        return new SheetPreview(schema.code(), resolution.valid(), resolution.issues(), layout, samples, stats);
    }

    public static SheetAnalysis analyze(ProductSchema schema, RawTable table, PreviewOptions options,
            String fileName, int hashLength, boolean normalizeFormulaRefs) {
        return analyze(schema, table, options, fileName, hashLength, normalizeFormulaRefs,
                ImportStopPolicy.CONTINUE);
    }

    public static SheetAnalysis analyze(ProductSchema schema, RawTable table, PreviewOptions options,
            String fileName, int hashLength, boolean normalizeFormulaRefs, ImportStopPolicy stopPolicy) {
        LayoutResolution resolution = resolveLayout(schema, table, options);
        if (resolution.layout().isEmpty()) {
            return new SheetAnalysis(null, List.of(), resolution.issues(), 0, 0, 0, 0);
        }
        SheetLayout layout = resolution.layout().get();
        ConversionContext context = new ConversionContext(schema, layout, fileName, table.sheetName(),
                hashLength, normalizeFormulaRefs);

        List<CaseRow> rows = new ArrayList<>();
        int valid = 0;
        int invalid = 0;
        int empty = 0;
        int rowNo = 0;
        int lastRow = lastRowIndex(table, options);
        ValidationIssue stopped = null;
        List<ValidationIssue> stoppedRowIssues = List.of();
        for (int rowIndex = layout.dataStartRow() - 1; rowIndex < lastRow; rowIndex++) {
            CaseRow row = RowConverter.convert(context, table, rowIndex + 1, rowNo + 1);
            if (row.empty()) {
                empty++;
                if (stopPolicy.stopAtEmptyRow()) {
                    stopped = ValidationIssue.warn("IMPORT_STOPPED",
                            "Import stopped at the first empty row (row " + row.sourceRow() + ")");
                    break;
                }
                continue;
            }
            if (stopPolicy.stopAtFirstError() && !row.valid()) {
                // The broken row is not imported, but its issues explain why the sheet stopped here.
                stopped = ValidationIssue.warn("IMPORT_STOPPED",
                        "Import stopped before the first row with errors (row " + row.sourceRow() + ")");
                stoppedRowIssues = row.issues();
                break;
            }
            rowNo++;
            if (row.valid()) {
                valid++;
            } else {
                invalid++;
            }
            rows.add(row);
        }

        List<ValidationIssue> issues = new ArrayList<>(resolution.issues());
        issues.addAll(stoppedRowIssues);
        if (stopped != null) {
            issues.add(stopped);
        }
        if (rows.isEmpty()) {
            issues.add(ValidationIssue.error("NO_DATA_ROWS",
                    "No data rows found below row " + layout.dataStartRow()));
        }
        return new SheetAnalysis(layout, rows, issues, rows.size(), valid, invalid, empty);
    }

    private static List<ValidationIssue> layoutIssues(ProductSchema schema, RawTable table,
            SheetLayout layout) {
        List<ValidationIssue> issues = new ArrayList<>();
        for (String missing : layout.missingFields()) {
            FieldDef field = schema.byKey().get(missing);
            if (field != null && field.required()) {
                issues.add(ValidationIssue.error("MISSING_REQUIRED_FIELD",
                        "Required field '" + field.header() + "' has no matching column")
                        .forField(field.key()));
            }
        }
        for (ColumnAssignment assignment : layout.assignments()) {
            if (assignment.kind() == ColumnKind.UNMAPPED) {
                issues.add(ValidationIssue.warn("UNMAPPED_TRADE_COLUMN",
                                "Column '" + describe(assignment) + "' inside the trade block matched no field")
                        .atColumn(assignment.columnNumber(), assignment.header()));
            }
        }
        if (!layout.blockContiguous() && !schema.strictMatch()) {
            issues.add(ValidationIssue.warn("TRADE_BLOCK_NOT_CONTIGUOUS",
                    "The trade columns contain columns that match no field; a non strict product matches"
                            + " columns by name, so the remaining columns are still mapped"));
        }
        if (schema.strictMatch()) {
            issues.addAll(strictIssues(schema, layout));
        } else {
            issues.addAll(nameMatchingIssues(schema, layout));
        }
        if (layout.dataStartRow() > table.rowCount()) {
            issues.add(ValidationIssue.error("NO_DATA_ROWS",
                    "No data rows found below row " + layout.headerRow()));
        }
        return issues;
    }

    /** Strict mode requires one column per declared field, in the declared order. */
    private static List<ValidationIssue> strictIssues(ProductSchema schema, SheetLayout layout) {
        List<ValidationIssue> issues = new ArrayList<>();
        List<FieldDef> expected = schema.fields();
        List<ColumnAssignment> region = blockAssignments(layout);
        if (region.size() != expected.size()) {
            issues.add(ValidationIssue.error("STRICT_ORDER_MISMATCH",
                    "Strict mode: the definition region has " + region.size()
                            + " column(s) but the product defines " + expected.size() + " field(s)"));
            return issues;
        }
        for (int index = 0; index < expected.size(); index++) {
            ColumnAssignment assignment = region.get(index);
            String expectedHeader = expected.get(index).header();
            if (ColumnNames.same(assignment.header(), expectedHeader)) {
                continue;
            }
            boolean knownName = expected.stream()
                    .anyMatch(field -> ColumnNames.same(field.header(), assignment.header()));
            issues.add(knownName
                    ? ValidationIssue.error("STRICT_ORDER_MISMATCH",
                                    "Strict mode: column " + assignment.columnNumber() + " should be '"
                                            + expectedHeader + "' but the file has '"
                                            + describe(assignment) + "'")
                            .atColumn(assignment.columnNumber(), assignment.header())
                    : ValidationIssue.error("TRADE_BLOCK_NOT_CONTIGUOUS",
                                    "Strict mode: column " + assignment.columnNumber() + " ("
                                            + describe(assignment)
                                            + ") matches no product field, the definition region must be contiguous")
                            .atColumn(assignment.columnNumber(), assignment.header()));
            break;
        }
        if (issues.isEmpty() && schema.hasDuplicateHeaders()) {
            long duplicated = schema.fields().stream().filter(FieldDef::hasDuplicateHeader).count();
            issues.add(ValidationIssue.warn("DUPLICATE_HEADER_MAPPING",
                    duplicated + " product field name(s) are declared more than once and matched by position"));
        }
        return issues;
    }

    /** Non strict mode matches columns by name, so product names must be unique on both sides. */
    private static List<ValidationIssue> nameMatchingIssues(ProductSchema schema, SheetLayout layout) {
        List<ValidationIssue> issues = new ArrayList<>();
        if (schema.hasDuplicateTradeHeaders()) {
            issues.add(ValidationIssue.error("DUPLICATE_PRODUCT_HEADER",
                    "Product field name(s) " + String.join(" / ", schema.duplicateTradeHeaders())
                            + " are declared more than once; a non strict product cannot map them"));
        }
        List<String> duplicated = duplicatedBlockHeaders(layout);
        if (!duplicated.isEmpty()) {
            issues.add(ValidationIssue.warn("DUPLICATE_SOURCE_HEADER",
                    "Column name(s) " + String.join(" / ", duplicated)
                            + " appear more than once; only the first occurrence is mapped to the field and the"
                            + " remaining columns stay unmapped"));
        }
        return issues;
    }

    private static List<ColumnAssignment> blockAssignments(SheetLayout layout) {
        return layout.assignments().stream()
                .filter(item -> item.columnNumber() >= layout.tradeStartColumn()
                        && item.columnNumber() <= layout.tradeEndColumn())
                .toList();
    }

    /** Header texts of the trade block that appear more than once. */
    private static List<String> duplicatedBlockHeaders(SheetLayout layout) {
        Map<String, String> firstHeader = new LinkedHashMap<>();
        Set<String> duplicated = new java.util.LinkedHashSet<>();
        for (ColumnAssignment assignment : blockAssignments(layout)) {
            String header = assignment.header();
            if (header == null || header.isBlank()) {
                continue;
            }
            String key = ColumnNames.key(header);
            String previous = firstHeader.putIfAbsent(key, header);
            if (previous != null) {
                duplicated.add(previous);
            }
        }
        return List.copyOf(duplicated);
    }

    private static String describe(ColumnAssignment assignment) {
        return assignment.header() == null || assignment.header().isBlank()
                ? "column " + assignment.columnNumber()
                : assignment.header();
    }

    private static int countNonEmptyRows(RawTable table, int dataStartRow, int lastRowIndex) {
        int count = 0;
        for (int rowIndex = dataStartRow - 1; rowIndex < lastRowIndex; rowIndex++) {
            if (!table.row(rowIndex).isBlank()) {
                count++;
            }
        }
        return count;
    }

    /**
     * Exclusive upper bound of the data rows: the configured end row, clamped to the sheet size.
     */
    private static int lastRowIndex(RawTable table, PreviewOptions options) {
        if (options.dataEndRow() <= 0) {
            return table.rowCount();
        }
        return Math.min(table.rowCount(), options.dataEndRow());
    }

    /**
     * Layout resolution outcome.
     *
     * @param layout resolved layout when successful
     * @param issues problems found while resolving
     * @param valid  true when no blocking issue exists
     */
    public record LayoutResolution(Optional<SheetLayout> layout, List<ValidationIssue> issues,
            boolean valid) {
    }
}
