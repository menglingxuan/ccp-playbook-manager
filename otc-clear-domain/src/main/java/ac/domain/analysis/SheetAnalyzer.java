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
import ac.domain.product.FieldDef;
import ac.domain.product.HeaderNormalizer;
import ac.domain.product.ProductSchema;
import ac.domain.table.RawTable;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

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
                        layout.assignments(), layout.missingFields(), layout.ambiguous());
            }
            List<ValidationIssue> issues = layoutIssues(schema, table, layout);
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
        for (int rowIndex = layout.dataStartRow() - 1;
                rowIndex < table.rowCount() && samples.size() < sampleLimit; rowIndex++) {
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

        int dataRows = countNonEmptyRows(table, layout.dataStartRow());
        PreviewStats stats = new PreviewStats(dataRows, layout.mappedTradeColumnCount(),
                layout.managedColumnCount(), sampleInvalid);
        return new SheetPreview(schema.code(), resolution.valid(), resolution.issues(), layout, samples, stats);
    }

    public static SheetAnalysis analyze(ProductSchema schema, RawTable table, PreviewOptions options,
            String fileName, int hashLength, boolean normalizeFormulaRefs) {
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
        for (int rowIndex = layout.dataStartRow() - 1; rowIndex < table.rowCount(); rowIndex++) {
            CaseRow row = RowConverter.convert(context, table, rowIndex + 1, rowNo + 1);
            if (row.empty()) {
                empty++;
                continue;
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
        if (layout.ambiguous()) {
            issues.add(ValidationIssue.warn("AMBIGUOUS_TRADE_BLOCK",
                    "Several trade block widths scored equally, the widest one was used"));
        }
        if (layout.mappedTradeColumnCount() == 0) {
            issues.add(ValidationIssue.error("TRADE_BLOCK_NOT_FOUND",
                    "No trade column matched the product definition"));
        }
        if (schema.strictMatch()) {
            String mismatch = strictMismatch(schema, layout);
            if (mismatch != null) {
                issues.add(ValidationIssue.error("STRICT_ORDER_MISMATCH", "Strict mode: " + mismatch));
            }
        }
        if (layout.dataStartRow() > table.rowCount()) {
            issues.add(ValidationIssue.error("NO_DATA_ROWS",
                    "No data rows found below row " + layout.headerRow()));
        }
        long duplicatedHeaders = schema.fields().stream().filter(FieldDef::hasDuplicateHeader).count();
        if (duplicatedHeaders > 0) {
            issues.add(ValidationIssue.warn("DUPLICATE_HEADER_MAPPING",
                    duplicatedHeaders + " product field(s) share a header name and are mapped by position"));
        }
        return issues;
    }

    /**
     * Compares the trade block with the product business fields, position by position.
     *
     * @return a human readable mismatch description, or {@code null} when the order matches
     */
    private static String strictMismatch(ProductSchema schema, SheetLayout layout) {
        List<FieldDef> expected = schema.tradeFields();
        List<ColumnAssignment> block = layout.assignments().stream()
                .filter(item -> item.columnNumber() >= layout.tradeStartColumn()
                        && item.columnNumber() <= layout.tradeEndColumn())
                .toList();
        if (block.size() != expected.size()) {
            return "the sheet has " + block.size() + " trade columns but the product defines "
                    + expected.size();
        }
        for (int index = 0; index < expected.size(); index++) {
            ColumnAssignment assignment = block.get(index);
            String expectedHeader = expected.get(index).header();
            String actualHeader = assignment.kind() == ColumnKind.TRADE ? assignment.header() : null;
            if (actualHeader == null
                    || !HeaderNormalizer.normalize(expectedHeader).equals(HeaderNormalizer.normalize(actualHeader))) {
                return "column " + assignment.columnNumber() + " should be '" + expectedHeader
                        + "' but the file has " + (actualHeader == null ? "an unknown column" : "'" + actualHeader + "'");
            }
        }
        return null;
    }

    private static String describe(ColumnAssignment assignment) {
        return assignment.header() == null || assignment.header().isBlank()
                ? "column " + assignment.columnNumber()
                : assignment.header();
    }

    private static int countNonEmptyRows(RawTable table, int dataStartRow) {
        int count = 0;
        for (int rowIndex = dataStartRow - 1; rowIndex < table.rowCount(); rowIndex++) {
            if (!table.row(rowIndex).isBlank()) {
                count++;
            }
        }
        return count;
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
