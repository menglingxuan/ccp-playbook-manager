package ac.domain.layout;

/**
 * Assignment of one source column.
 *
 * @param column      0-based column index inside the sheet
 * @param header      header text as found in the source file
 * @param kind        trade, managed or unmapped
 * @param fieldKey    product field key for {@link ColumnKind#TRADE} columns
 * @param score       aligner score for trade columns, 0 otherwise
 * @param managedKind semantic meaning for managed columns
 * @param managedKey  storage key inside {@code managed_json}
 */
public record ColumnAssignment(
        int column,
        String header,
        ColumnKind kind,
        String fieldKey,
        int score,
        ManagedKind managedKind,
        String managedKey) {

    public static ColumnAssignment trade(int column, String header, String fieldKey, int score) {
        return new ColumnAssignment(column, header, ColumnKind.TRADE, fieldKey, score, null, null);
    }

    public static ColumnAssignment unmapped(int column, String header) {
        return new ColumnAssignment(column, header, ColumnKind.UNMAPPED, null, 0, null, null);
    }

    public static ColumnAssignment managed(int column, String header, ManagedKind managedKind,
            String managedKey) {
        return new ColumnAssignment(column, header, ColumnKind.MANAGED, null, 0, managedKind, managedKey);
    }

    public int columnNumber() {
        return column + 1;
    }
}
