package ac.domain;

import ac.domain.table.RawRow;
import ac.domain.table.RawTable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Small helpers that build {@link RawTable} instances without touching files. */
public final class TestTables {

    private TestTables() {
    }

    public static RawTable table(String sheetName, String[]... rows) {
        RawTable.Builder builder = RawTable.builder(sheetName);
        for (String[] row : rows) {
            builder.addRow(RawRow.plain(row));
        }
        return builder.build();
    }

    public static RawTable table(String sheetName, List<String[]> rows) {
        RawTable.Builder builder = RawTable.builder(sheetName);
        for (String[] row : rows) {
            builder.addRow(RawRow.plain(row));
        }
        return builder.build();
    }

    /** Builds a table where selected columns carry formulas. */
    public static RawTable withFormulas(String sheetName, List<String[]> rows,
            Map<Integer, Map<Integer, String>> formulasByRow) {
        RawTable.Builder builder = RawTable.builder(sheetName);
        for (int rowIndex = 0; rowIndex < rows.size(); rowIndex++) {
            String[] texts = rows.get(rowIndex);
            Map<Integer, String> formulas = formulasByRow.get(rowIndex);
            if (formulas == null || formulas.isEmpty()) {
                builder.addRow(RawRow.plain(texts));
                continue;
            }
            String[] formulaArray = new String[texts.length];
            formulas.forEach((column, formula) -> formulaArray[column] = formula);
            builder.addRow(new RawRow(texts, formulaArray, null));
        }
        return builder.build();
    }

    public static List<String[]> rows() {
        return new ArrayList<>();
    }
}
