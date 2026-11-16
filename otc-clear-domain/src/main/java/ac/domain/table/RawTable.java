package ac.domain.table;

import java.util.ArrayList;
import java.util.List;

/** In-memory representation of one sheet with raw text and formula information per cell. */
public final class RawTable {

    private final String sheetName;
    private final List<RawRow> rows;
    private final int columnCount;
    private final int declaredRowCount;

    private RawTable(String sheetName, List<RawRow> rows, int columnCount, int declaredRowCount) {
        this.sheetName = sheetName;
        this.rows = List.copyOf(rows);
        this.columnCount = columnCount;
        this.declaredRowCount = Math.max(declaredRowCount, rows.size());
    }

    public static Builder builder(String sheetName) {
        return new Builder(sheetName);
    }

    public String sheetName() {
        return sheetName;
    }

    public int rowCount() {
        return rows.size();
    }

    /**
     * Row count of the whole source, which is larger than {@link #rowCount()} when a preview only kept
     * the first rows.
     */
    public int declaredRowCount() {
        return declaredRowCount;
    }

    /** True when only the first rows of the source are held in memory. */
    public boolean truncated() {
        return declaredRowCount > rows.size();
    }

    public int columnCount() {
        return columnCount;
    }

    public RawRow row(int rowIndex) {
        return rows.get(rowIndex);
    }

    public String text(int rowIndex, int column) {
        return rows.get(rowIndex).text(column);
    }

    public RawCell cell(int rowIndex, int column) {
        return rows.get(rowIndex).cell(column);
    }

    /** Returns all header texts of a row, preserving column positions. */
    public List<String> headerTexts(int rowIndex) {
        List<String> headers = new ArrayList<>(columnCount);
        RawRow row = rows.get(rowIndex);
        for (int column = 0; column < columnCount; column++) {
            headers.add(row.text(column).trim());
        }
        return headers;
    }

    /** Index of the last column that has any non blank value, or {@code -1} when the sheet is empty. */
    public int lastNonBlankColumn() {
        int last = -1;
        for (RawRow row : rows) {
            for (int column = row.texts().length - 1; column > last; column--) {
                String value = row.text(column);
                if (!value.isBlank()) {
                    last = column;
                    break;
                }
            }
        }
        return last;
    }

    /** Index of the last column with non blank content on a specific row. */
    public int lastNonBlankColumn(int rowIndex) {
        RawRow row = rows.get(rowIndex);
        for (int column = columnCount - 1; column >= 0; column--) {
            if (!row.text(column).isBlank()) {
                return column;
            }
        }
        return -1;
    }

    public static final class Builder {

        private final String sheetName;
        private final List<RawRow> rows = new ArrayList<>();
        private int columnCount;
        private int declaredRowCount = -1;

        private Builder(String sheetName) {
            this.sheetName = sheetName == null ? "" : sheetName;
        }

        public Builder addRow(RawRow row) {
            rows.add(row);
            columnCount = Math.max(columnCount, row.texts().length);
            return this;
        }

        public Builder columnCount(int columns) {
            this.columnCount = Math.max(this.columnCount, columns);
            return this;
        }

        /** Records how many rows the source has when only the first ones were kept. */
        public Builder declaredRowCount(int rows) {
            this.declaredRowCount = rows;
            return this;
        }

        public RawTable build() {
            return new RawTable(sheetName, rows, columnCount,
                    declaredRowCount < 0 ? rows.size() : declaredRowCount);
        }
    }
}
