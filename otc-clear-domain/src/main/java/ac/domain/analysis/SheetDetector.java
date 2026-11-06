package ac.domain.analysis;

import ac.domain.layout.SequenceAligner;
import ac.domain.product.FieldDef;
import ac.domain.product.ProductSchema;
import ac.domain.table.RawTable;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Heuristically detects the header row and the trade block start column. */
public final class SheetDetector {

    private static final int HEADER_SCAN_ROWS = 20;

    private SheetDetector() {
    }

    /** Returns a 1-based header row guess; falls back to 1 when nothing matches. */
    public static int detectHeaderRow(ProductSchema schema, RawTable table) {
        int limit = Math.min(table.rowCount(), HEADER_SCAN_ROWS);
        int bestRow = 1;
        int bestScore = 0;
        for (int rowIndex = 0; rowIndex < limit; rowIndex++) {
            List<String> headers = table.headerTexts(rowIndex);
            Set<String> matched = matchedFields(schema, headers);
            int score = matched.size() * 10 - countNumericHeaders(headers);
            if (score > bestScore) {
                bestScore = score;
                bestRow = rowIndex + 1;
            }
        }
        return bestRow;
    }

    /** Returns a 1-based column guess where the trade block starts. */
    public static int detectTradeStartColumn(ProductSchema schema, RawTable table, int headerRow) {
        int headerIndex = headerRow - 1;
        if (headerIndex < 0 || headerIndex >= table.rowCount()) {
            return 1;
        }
        List<String> headers = table.headerTexts(headerIndex);
        for (int column = 0; column < headers.size(); column++) {
            String header = headers.get(column);
            if (header.isBlank()) {
                continue;
            }
            for (FieldDef field : schema.fields()) {
                if (SequenceAligner.matchScore(header, field) >= SequenceAligner.SCORE_ALIAS) {
                    return column + 1;
                }
            }
        }
        return 1;
    }

    /** Counts distinct fields matched by the given header texts. */
    public static Set<String> matchedFields(ProductSchema schema, List<String> headers) {
        Set<String> matched = new HashSet<>();
        for (String header : headers) {
            if (header == null || header.isBlank()) {
                continue;
            }
            FieldDef bestField = null;
            int bestScore = 0;
            for (FieldDef field : schema.fields()) {
                int score = SequenceAligner.matchScore(header, field);
                if (score > bestScore) {
                    bestScore = score;
                    bestField = field;
                }
            }
            if (bestField != null) {
                matched.add(bestField.key());
            }
        }
        return matched;
    }

    private static int countNumericHeaders(List<String> headers) {
        int numeric = 0;
        for (String header : headers) {
            if (header == null || header.isBlank()) {
                continue;
            }
            try {
                Double.parseDouble(header.replace(",", ""));
                numeric++;
            } catch (NumberFormatException ignored) {
                // not numeric
            }
        }
        return numeric;
    }
}
