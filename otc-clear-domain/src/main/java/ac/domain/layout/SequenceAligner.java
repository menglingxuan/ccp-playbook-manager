package ac.domain.layout;

import ac.domain.product.FieldDef;
import ac.domain.product.HeaderNormalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Order preserving sequence alignment between source header columns and product fields.
 *
 * <p>Uses Needleman-Wunsch global alignment, which keeps the declared field order and therefore
 * handles duplicated header names and missing columns without a name based map.
 */
public final class SequenceAligner {

    public static final double GAP_PENALTY = -1.2;
    public static final int SCORE_EXACT = 3;
    public static final int SCORE_ALIAS = 2;
    public static final int SCORE_FUZZY = 1;
    public static final int SCORE_NONE = -1;
    public static final double FUZZY_THRESHOLD = 0.90;

    private SequenceAligner() {
    }

    /** One matched column/field pair. */
    public record Match(int columnIndex, String fieldKey, int score) {
    }

    /**
     * Result of an alignment.
     *
     * @param score         total alignment score
     * @param matches       matched pairs ordered by column index
     * @param missingFields fields that found no column
     * @param unusedColumns header indexes inside the input list without a field
     */
    public record Alignment(double score, List<Match> matches, List<FieldDef> missingFields,
            List<Integer> unusedColumns) {

        public Alignment {
            matches = List.copyOf(matches);
            missingFields = List.copyOf(missingFields);
            unusedColumns = List.copyOf(unusedColumns);
        }

        public int matchCount() {
            return matches.size();
        }
    }

    public static Alignment align(List<String> headers, List<FieldDef> fields) {
        int rows = headers.size();
        int columns = fields.size();
        double[][] best = new double[rows + 1][columns + 1];
        byte[][] trace = new byte[rows + 1][columns + 1];

        for (int row = 1; row <= rows; row++) {
            best[row][0] = best[row - 1][0] + GAP_PENALTY;
            trace[row][0] = 1;
        }
        for (int column = 1; column <= columns; column++) {
            best[0][column] = best[0][column - 1] + GAP_PENALTY;
            trace[0][column] = 2;
        }

        for (int row = 1; row <= rows; row++) {
            for (int column = 1; column <= columns; column++) {
                double diagonal = best[row - 1][column - 1]
                        + matchScore(headers.get(row - 1), fields.get(column - 1));
                double up = best[row - 1][column] + GAP_PENALTY;
                double left = best[row][column - 1] + GAP_PENALTY;

                if (diagonal >= up && diagonal >= left) {
                    best[row][column] = diagonal;
                    trace[row][column] = 0;
                } else if (up >= left) {
                    best[row][column] = up;
                    trace[row][column] = 1;
                } else {
                    best[row][column] = left;
                    trace[row][column] = 2;
                }
            }
        }

        List<Match> matches = new ArrayList<>();
        List<Integer> unusedColumns = new ArrayList<>();
        boolean[] fieldUsed = new boolean[columns];
        int row = rows;
        int column = columns;
        while (row > 0 || column > 0) {
            byte step = trace[row][column];
            if (row > 0 && column > 0 && step == 0) {
                int score = matchScore(headers.get(row - 1), fields.get(column - 1));
                if (score > 0) {
                    matches.add(0, new Match(row - 1, fields.get(column - 1).key(), score));
                    fieldUsed[column - 1] = true;
                } else {
                    unusedColumns.add(0, row - 1);
                }
                row--;
                column--;
            } else if (row > 0 && (column == 0 || step == 1)) {
                unusedColumns.add(0, row - 1);
                row--;
            } else {
                column--;
            }
        }

        List<FieldDef> missing = new ArrayList<>();
        for (int index = 0; index < columns; index++) {
            if (!fieldUsed[index]) {
                missing.add(fields.get(index));
            }
        }
        return new Alignment(best[rows][columns], matches, missing, unusedColumns);
    }

    /** Scores how well a source header matches one product field. */
    public static int matchScore(String header, FieldDef field) {
        String normalized = HeaderNormalizer.normalize(header);
        if (normalized.isEmpty()) {
            return SCORE_NONE;
        }
        String primary = HeaderNormalizer.normalize(field.header());
        if (normalized.equals(primary)) {
            return SCORE_EXACT;
        }
        for (String alias : field.normalizedNames()) {
            if (!alias.equals(primary) && alias.equals(normalized)) {
                return SCORE_ALIAS;
            }
        }
        double best = similarity(normalized, primary);
        for (String alias : field.normalizedNames()) {
            best = Math.max(best, similarity(normalized, alias));
        }
        return best >= FUZZY_THRESHOLD ? SCORE_FUZZY : SCORE_NONE;
    }

    /** Normalized Levenshtein similarity in the range [0, 1]. */
    public static double similarity(String left, String right) {
        String a = left == null ? "" : left.toLowerCase(Locale.ROOT);
        String b = right == null ? "" : right.toLowerCase(Locale.ROOT);
        if (a.equals(b)) {
            return 1.0;
        }
        int maxLength = Math.max(a.length(), b.length());
        if (maxLength == 0) {
            return 1.0;
        }
        int distance = levenshtein(a, b);
        return 1.0 - (double) distance / maxLength;
    }

    private static int levenshtein(String a, String b) {
        int[] previous = new int[b.length() + 1];
        int[] current = new int[b.length() + 1];
        for (int index = 0; index <= b.length(); index++) {
            previous[index] = index;
        }
        for (int i = 1; i <= a.length(); i++) {
            current[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                current[j] = Math.min(Math.min(current[j - 1] + 1, previous[j] + 1), previous[j - 1] + cost);
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return previous[b.length()];
    }
}
