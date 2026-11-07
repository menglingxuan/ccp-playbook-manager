package ac.domain.layout;

import ac.domain.product.FieldDef;
import ac.domain.product.ProductSchema;
import ac.domain.table.RawTable;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Resolves the contiguous trade block of a sheet and classifies the remaining columns as managed
 * or unmapped.
 */
public final class ColumnLayoutResolver {

    /** Penalty applied per trade block column that maps to no field. */
    private static final double UNMAPPED_PENALTY = 0.8;

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
            throw new LayoutException("INVALID_HEADER_ROW",
                    "Header row " + headerRow + " is empty");
        }
        int startIndex = tradeStartColumn - 1;
        if (startIndex < 0 || startIndex > lastColumnIndex) {
            throw new LayoutException("TRADE_START_OUT_OF_RANGE",
                    "Trade start column " + tradeStartColumn + " is outside the header range");
        }

        List<FieldDef> fields = schema.tradeFields();
        int maxEnd = Math.min(lastColumnIndex, startIndex + fields.size() - 1);
        SequenceAligner.Alignment best = null;
        int bestEnd = -1;
        double bestScore = Double.NEGATIVE_INFINITY;
        boolean ambiguous = false;

        for (int end = startIndex; end <= maxEnd; end++) {
            List<String> blockHeaders = headers.subList(startIndex, end + 1);
            SequenceAligner.Alignment alignment = SequenceAligner.align(blockHeaders, fields);
            int unmatched = blockHeaders.size() - alignment.matchCount();
            double score = alignment.score() - unmatched * UNMAPPED_PENALTY;
            if (score > bestScore + 1e-9) {
                bestScore = score;
                best = alignment;
                bestEnd = end;
                ambiguous = false;
            } else if (Math.abs(score - bestScore) <= 1e-9) {
                ambiguous = true;
                if (end > bestEnd) {
                    best = alignment;
                    bestEnd = end;
                }
            }
        }

        if (best == null || best.matchCount() == 0) {
            throw new LayoutException("TRADE_BLOCK_NOT_FOUND",
                    "No trade field matched starting at column " + tradeStartColumn);
        }

        Map<Integer, SequenceAligner.Match> matchedByColumn = new java.util.HashMap<>();
        for (SequenceAligner.Match match : best.matches()) {
            matchedByColumn.put(startIndex + match.columnIndex(), match);
        }

        Set<String> usedManagedKeys = new HashSet<>();
        List<ColumnAssignment> assignments = new ArrayList<>(lastColumnIndex + 1);
        for (int column = 0; column <= lastColumnIndex; column++) {
            String header = headers.get(column);
            if (column < startIndex || column > bestEnd) {
                if (header.isBlank()) {
                    continue;
                }
                ManagedKind kind = ManagedColumnClassifier.classify(header);
                String storageKey = ManagedColumnClassifier.storageKey(kind, header, usedManagedKeys);
                usedManagedKeys.add(storageKey);
                assignments.add(ColumnAssignment.managed(column, header, kind, storageKey));
                continue;
            }
            SequenceAligner.Match match = matchedByColumn.get(column);
            if (match == null) {
                assignments.add(ColumnAssignment.unmapped(column, header));
            } else {
                assignments.add(ColumnAssignment.trade(column, header, match.fieldKey(), match.score()));
            }
        }

        List<String> missing = best.missingFields().stream().map(FieldDef::key).toList();
        return new SheetLayout(headerRow, headerRow + 1, tradeStartColumn, bestEnd + 1, lastColumnIndex + 1,
                assignments, missing, ambiguous);
    }
}
