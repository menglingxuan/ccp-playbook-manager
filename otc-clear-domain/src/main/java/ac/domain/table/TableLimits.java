package ac.domain.table;

/**
 * Size guards applied while a sheet is read.
 *
 * <p>They bound the memory one file can claim and keep the rule engine away from oversized values:
 * a CSV with a million columns or a cell of a few megabytes is rejected with a clear message instead
 * of exhausting the heap.
 *
 * @param maxColumns    largest accepted column count of one row
 * @param maxCellLength largest accepted text length of one cell
 */
public record TableLimits(int maxColumns, int maxCellLength) {

    public static final TableLimits DEFAULT = new TableLimits(1_000, 32_768);

    public TableLimits {
        maxColumns = maxColumns < 1 ? 1_000 : maxColumns;
        maxCellLength = maxCellLength < 1 ? 32_768 : maxCellLength;
    }

    /** Fails when a row has too many columns. */
    public void checkColumns(String sheetName, int rowNumber, int columnCount) {
        if (columnCount > maxColumns) {
            throw new TableReadException("Row " + rowNumber + " of '" + sheetName + "' has " + columnCount
                    + " columns, limit is " + maxColumns);
        }
    }

    /** Fails when one cell text is longer than the configured limit. */
    public void checkCell(String sheetName, int rowNumber, int columnNumber, String text) {
        if (text != null && text.length() > maxCellLength) {
            throw new TableReadException("Cell (" + rowNumber + ", " + columnNumber + ") of '" + sheetName
                    + "' is " + text.length() + " characters long, limit is " + maxCellLength);
        }
    }
}
