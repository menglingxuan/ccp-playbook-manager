package ac.domain.layout;

import ac.domain.product.ColumnNames;
import ac.domain.product.FieldDef;
import ac.domain.product.ProductSchema;
import ac.domain.table.RawTable;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Classifies every column of a sheet against the product definition.
 *
 * <p>Only the definition decides what a column is: a column is a trade column or a managed column
 * when it matches a declared field, everything else is unmapped. Column names must be equal after
 * trimming and ignoring case; aliases never match.
 *
 * <ul>
 *   <li><b>Strict products</b> compare the definition region position by position. The region starts
 *       at the trade start column and spans one column per declared field, business and managed
 *       fields in their declared order. Columns beyond the region are unmapped.</li>
 *   <li><b>Non strict products</b> match business fields by name from the trade start column on, and
 *       managed fields by name anywhere in the row. Columns matching neither are unmapped.</li>
 * </ul>
 */
public final class ColumnLayoutResolver {

    private ColumnLayoutResolver() {
    }

    public static SheetLayout resolve(ProductSchema schema, RawTable table, int headerRow,
            int tradeStartColumn) {
        if (table.rowCount() == 0) {
            throw new LayoutException("NO_DATA_ROWS", "Sheet '" + table.sheetName() + "' is empty");
        }
        int headerIndex = headerRow - 1;
        if (headerIndex < 0 || headerIndex >= table.rowCount()) {
            throw new LayoutException("INVALID_HEADER_ROW",
                    "Header row " + headerRow + " is outside the sheet range");
        }
        List<String> headers = table.headerTexts(headerIndex);
        int lastColumnIndex = table.lastNonBlankColumn(headerIndex);
        if (lastColumnIndex < 0) {
            throw new LayoutException("INVALID_HEADER_ROW", "Header row " + headerRow + " is empty");
        }
        int startIndex = tradeStartColumn - 1;
        if (startIndex < 0 || startIndex > lastColumnIndex) {
            throw new LayoutException("TRADE_START_OUT_OF_RANGE",
                    "Trade start column " + tradeStartColumn + " is outside the header range");
        }
        List<FieldDef> fields = schema.fields();
        if (fields.isEmpty()) {
            throw new LayoutException("TRADE_BLOCK_NOT_FOUND",
                    "The product defines no field to match against");
        }

        return schema.strictMatch()
                ? resolveStrict(schema, headers, fields, headerRow, startIndex, lastColumnIndex)
                : resolveByName(schema, headers, fields, headerRow, startIndex, lastColumnIndex);
    }

    private static SheetLayout resolveStrict(ProductSchema schema, List<String> headers,
            List<FieldDef> fields, int headerRow, int startIndex, int lastColumnIndex) {
        int endIndex = Math.min(lastColumnIndex, startIndex + fields.size() - 1);
        Map<Integer, FieldDef> matched = new LinkedHashMap<>();
        int width = endIndex - startIndex + 1;
        for (int offset = 0; offset < width; offset++) {
            FieldDef field = fields.get(offset);
            int column = startIndex + offset;
            if (ColumnNames.same(headers.get(column), field.header())) {
                matched.put(column, field);
            }
        }

        Set<String> usedManagedKeys = new HashSet<>();
        List<ColumnAssignment> assignments = new ArrayList<>(lastColumnIndex + 1);
        for (int column = 0; column <= lastColumnIndex; column++) {
            String header = headers.get(column);
            if (header.isBlank()) {
                continue;
            }
            FieldDef field = matched.get(column);
            if (field == null) {
                assignments.add(ColumnAssignment.unmapped(column, header));
            } else {
                assignments.add(assignment(column, header, field, usedManagedKeys));
            }
        }
        boolean contiguous = width == fields.size() && matched.size() == fields.size();
        return new SheetLayout(headerRow, headerRow + 1, startIndex + 1, endIndex + 1,
                lastColumnIndex + 1, assignments, missingFields(fields, matched), contiguous);
    }

    private static SheetLayout resolveByName(ProductSchema schema, List<String> headers,
            List<FieldDef> fields, int headerRow, int startIndex, int lastColumnIndex) {
        Map<String, Deque<FieldDef>> businessByName = queueByName(schema.tradeFields());
        Map<String, Deque<FieldDef>> managedByName = queueByName(schema.managedFields());
        Set<String> usedManagedKeys = new HashSet<>();
        List<ColumnAssignment> assignments = new ArrayList<>(lastColumnIndex + 1);
        Map<Integer, FieldDef> matched = new LinkedHashMap<>();
        int firstTradeColumn = -1;
        int lastTradeColumn = -1;

        for (int column = 0; column <= lastColumnIndex; column++) {
            String header = headers.get(column);
            if (header.isBlank()) {
                continue;
            }
            String name = ColumnNames.key(header);
            // Business fields only start at the configured trade start column.
            FieldDef businessField = column >= startIndex ? poll(businessByName, name) : null;
            if (businessField != null) {
                assignments.add(ColumnAssignment.trade(column, header, businessField.key()));
                matched.put(column, businessField);
                if (firstTradeColumn < 0) {
                    firstTradeColumn = column;
                }
                lastTradeColumn = column;
                continue;
            }
            FieldDef managedField = poll(managedByName, name);
            if (managedField != null) {
                assignments.add(assignment(column, header, managedField, usedManagedKeys));
                matched.put(column, managedField);
                continue;
            }
            assignments.add(ColumnAssignment.unmapped(column, header));
        }

        if (firstTradeColumn < 0) {
            throw new LayoutException("TRADE_BLOCK_NOT_FOUND",
                    "No trade column matched the product definition from column " + (startIndex + 1));
        }
        int regionStart = firstTradeColumn;
        int regionEnd = lastTradeColumn;
        boolean contiguous = assignments.stream()
                .noneMatch(item -> item.kind() == ColumnKind.UNMAPPED
                        && item.column() >= regionStart && item.column() <= regionEnd);
        return new SheetLayout(headerRow, headerRow + 1, firstTradeColumn + 1, lastTradeColumn + 1,
                lastColumnIndex + 1, assignments, missingFields(fields, matched), contiguous);
    }

    private static ColumnAssignment assignment(int column, String header, FieldDef field,
            Set<String> usedManagedKeys) {
        if (!field.isManaged()) {
            return ColumnAssignment.trade(column, header, field.key());
        }
        ManagedKind kind = ManagedColumnClassifier.classify(field.header());
        String storageKey = ManagedColumnClassifier.storageKey(kind, field.header(), usedManagedKeys);
        usedManagedKeys.add(storageKey);
        return ColumnAssignment.managed(column, header, kind, storageKey);
    }

    private static Map<String, Deque<FieldDef>> queueByName(List<FieldDef> fields) {
        Map<String, Deque<FieldDef>> index = new LinkedHashMap<>();
        for (FieldDef field : fields) {
            index.computeIfAbsent(ColumnNames.key(field.header()), key -> new ArrayDeque<>()).add(field);
        }
        return index;
    }

    private static FieldDef poll(Map<String, Deque<FieldDef>> index, String name) {
        Deque<FieldDef> candidates = index.get(name);
        return candidates == null || candidates.isEmpty() ? null : candidates.poll();
    }

    private static List<String> missingFields(List<FieldDef> fields, Map<Integer, FieldDef> matched) {
        Set<String> matchedKeys = new LinkedHashSet<>();
        matched.values().forEach(field -> matchedKeys.add(field.key()));
        return fields.stream()
                .filter(field -> !matchedKeys.contains(field.key()))
                .map(FieldDef::key)
                .toList();
    }
}
