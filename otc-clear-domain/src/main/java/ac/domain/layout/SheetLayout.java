package ac.domain.layout;

import java.util.List;
import java.util.Optional;

/**
 * Resolved layout of one sheet.
 *
 * <p>Row and column numbers are 1-based because they are shown to users; use
 * {@link #tradeStartIndex()} and {@link #tradeEndIndex()} inside the reader code.
 *
 * @param headerRow        1-based row that holds column names
 * @param dataStartRow     1-based first data row
 * @param tradeStartColumn 1-based first column of the contiguous trade block
 * @param tradeEndColumn   1-based last column of the trade block (inclusive)
 * @param lastColumn       1-based last column that carries content
 * @param assignments      one assignment per column, ordered by column index
 * @param missingFields    product field keys that found no column
 * @param ambiguous        true when two trade block candidates scored equally
 */
public record SheetLayout(
        int headerRow,
        int dataStartRow,
        int tradeStartColumn,
        int tradeEndColumn,
        int lastColumn,
        List<ColumnAssignment> assignments,
        List<String> missingFields,
        boolean ambiguous) {

    public SheetLayout {
        assignments = List.copyOf(assignments);
        missingFields = List.copyOf(missingFields);
    }

    public int tradeStartIndex() {
        return tradeStartColumn - 1;
    }

    public int tradeEndIndex() {
        return tradeEndColumn - 1;
    }

    public Optional<ColumnAssignment> assignmentFor(int column) {
        if (column < 0 || column >= assignments.size()) {
            return Optional.empty();
        }
        return Optional.ofNullable(assignments.get(column));
    }

    public List<ColumnAssignment> assignmentsOf(ColumnKind kind) {
        return assignments.stream().filter(item -> item.kind() == kind).toList();
    }

    public int mappedTradeColumnCount() {
        return (int) assignments.stream()
                .filter(item -> item.kind() == ColumnKind.TRADE && item.fieldKey() != null)
                .count();
    }

    public int managedColumnCount() {
        return (int) assignments.stream().filter(item -> item.kind() == ColumnKind.MANAGED).count();
    }
}
