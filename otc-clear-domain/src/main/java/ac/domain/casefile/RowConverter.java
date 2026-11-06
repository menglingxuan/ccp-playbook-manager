package ac.domain.casefile;

import ac.domain.layout.ColumnAssignment;
import ac.domain.layout.ColumnKind;
import ac.domain.layout.ManagedKind;
import ac.domain.product.FieldCodec;
import ac.domain.product.FieldDef;
import ac.domain.product.FieldFormatException;
import ac.domain.product.ProductSchema;
import ac.domain.table.RawCell;
import ac.domain.table.RawRow;
import ac.domain.table.RawTable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Converts one raw sheet row into a {@link CaseRow}, capturing typed values, formulas, managed
 * columns, extras and the three record ids.
 */
public final class RowConverter {

    private static final Pattern RELATIVE_ROW_REFERENCE = Pattern.compile("(\\$?[A-Za-z]{1,3})\\$?\\d+");

    private RowConverter() {
    }

    public static CaseRow convert(ConversionContext context, RawTable table, int sourceRow, int rowNo) {
        ProductSchema schema = context.schema();
        RawRow raw = table.row(sourceRow - 1);
        Map<String, Object> data = new LinkedHashMap<>();
        Map<String, String> managed = new LinkedHashMap<>();
        Map<String, String> formulas = new LinkedHashMap<>();
        Map<String, String> extras = new LinkedHashMap<>();
        List<UnresolvedFormula> unresolved = new ArrayList<>();
        List<ValidationIssue> issues = new ArrayList<>();

        for (ColumnAssignment assignment : context.layout().assignments()) {
            String header = assignment.header();
            RawCell cell = raw.cell(assignment.column());
            if (assignment.kind() == ColumnKind.TRADE) {
                FieldDef field = schema.requireField(assignment.fieldKey());
                collectFormula(context, cell, field.key(), assignment.column(), formulas, unresolved, issues);
                Object value = parseCell(field, cell, assignment, issues);
                if (value != null) {
                    data.put(field.key(), value);
                }
            } else if (assignment.kind() == ColumnKind.MANAGED) {
                collectFormula(context, cell, assignment.managedKey(), assignment.column(), formulas,
                        unresolved, issues);
                String text = cell.text().trim();
                if (!text.isEmpty()) {
                    managed.put(assignment.managedKey(), text);
                }
            } else if (assignment.kind() == ColumnKind.UNMAPPED) {
                String text = cell.text().trim();
                if (!text.isEmpty()) {
                    extras.put(extrasKey(header, assignment.column()), text);
                }
            }
        }

        boolean empty = data.isEmpty() && managed.isEmpty() && extras.isEmpty() && formulas.isEmpty();

        for (FieldDef required : schema.requiredFields()) {
            if (data.get(required.key()) == null) {
                issues.add(ValidationIssue.error("MISSING_REQUIRED_FIELD",
                                "Required field '" + required.header() + "' is empty")
                        .atRow(sourceRow)
                        .forField(required.key()));
            }
        }

        String originalRecordId = RecordIdGenerator.originalId(context.fileName(), context.sheetName(),
                sourceRow, context.hashLength());
        String businessRecordId = RecordIdGenerator.businessId(schema, data, formulas, context.hashLength());
        if (businessRecordId.isEmpty() || !hasBusinessValue(schema, data, formulas)) {
            issues.add(ValidationIssue.warn("BUSINESS_KEY_EMPTY",
                    "Business fields are empty, falling back to the original record id").atRow(sourceRow));
            businessRecordId = originalRecordId;
        }

        String testCaseId = managed.get(testCaseIdKey(context.layout()));
        TestCaseIdSource idSource = TestCaseIdSource.FILE;
        if (testCaseId == null || testCaseId.isBlank()) {
            testCaseId = RecordIdGenerator.generatedTestCaseId(businessRecordId);
            idSource = TestCaseIdSource.GENERATED;
        }

        String keyValue = null;
        if (schema.uniqueField() != null) {
            Object key = data.get(schema.uniqueField().key());
            keyValue = key == null ? null : FieldCodec.toCanonical(key);
        }

        return new CaseRow(sourceRow, rowNo, data, managed, formulas, unresolved, extras, testCaseId,
                idSource, originalRecordId, businessRecordId, keyValue, issues, empty);
    }

    private static void collectFormula(ConversionContext context, RawCell cell, String key, int column,
            Map<String, String> formulas, List<UnresolvedFormula> unresolved, List<ValidationIssue> issues) {
        if (!cell.hasFormula()) {
            return;
        }
        String formula = cell.formula();
        if (formula == null) {
            String reason = cell.formulaError() == null ? "SHARED_FORMULA_NO_TEXT" : cell.formulaError();
            unresolved.add(new UnresolvedFormula(key, column + 1, reason));
            issues.add(ValidationIssue.warn(reason, "Formula text is not available for column " + (column + 1))
                    .atColumn(column + 1, null)
                    .forField(key));
            return;
        }
        formulas.put(key, context.normalizeFormulaRefs() ? normalizeFormula(formula) : formula);
    }

    private static Object parseCell(FieldDef field, RawCell cell, ColumnAssignment assignment,
            List<ValidationIssue> issues) {
        if (cell.hasFormulaError()) {
            issues.add(ValidationIssue.warn("FORMULA_ERROR",
                            "Formula error '" + cell.text() + "' in column " + assignment.columnNumber())
                    .atColumn(assignment.columnNumber(), assignment.header())
                    .forField(field.key()));
            return null;
        }
        try {
            return FieldCodec.parse(field, cell.text());
        } catch (FieldFormatException e) {
            issues.add(ValidationIssue.error("TYPE_MISMATCH", e.getMessage())
                    .atColumn(assignment.columnNumber(), assignment.header())
                    .forField(field.key()));
            return null;
        }
    }

    private static String extrasKey(String header, int column) {
        if (header == null || header.isBlank()) {
            return "Column" + (column + 1);
        }
        return header.trim();
    }

    private static String testCaseIdKey(ac.domain.layout.SheetLayout layout) {
        return layout.assignments().stream()
                .filter(item -> item.kind() == ColumnKind.MANAGED && item.managedKind() == ManagedKind.TESTCASE_ID)
                .map(ColumnAssignment::managedKey)
                .findFirst()
                .orElse(ac.domain.layout.ManagedColumnClassifier.DEFAULT_TESTCASE_ID_KEY);
    }

    private static boolean hasBusinessValue(ProductSchema schema, Map<String, Object> data,
            Map<String, String> formulas) {
        for (FieldDef field : schema.businessFields()) {
            if (formulas.containsKey(field.key())) {
                return true;
            }
            Object value = data.get(field.key());
            if (value != null && !String.valueOf(value).isBlank()) {
                return true;
            }
        }
        return false;
    }

    /** Masks row numbers inside cell references so the same relative formula matches across rows. */
    public static String normalizeFormula(String formula) {
        if (formula == null) {
            return null;
        }
        return RELATIVE_ROW_REFERENCE.matcher(formula).replaceAll("$1#");
    }
}
