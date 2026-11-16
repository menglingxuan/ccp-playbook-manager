package ac.core.service.definition;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Content of one export: the rows of every table of a scope, plus the tenant they belong to.
 *
 * <p>Rows keep the column order of {@link DefinitionTable#columns()}; values are plain text so the
 * same model feeds the Excel writer, the SQL writer and the import reader.
 */
public record DefinitionBundle(String tenantId, Map<DefinitionTable, List<Row>> tables) {

    public DefinitionBundle {
        Map<DefinitionTable, List<Row>> copy = new LinkedHashMap<>();
        tables.forEach((table, rows) -> copy.put(table, List.copyOf(rows)));
        tables = copy;
    }

    public static DefinitionBundle empty(String tenantId) {
        return new DefinitionBundle(tenantId, Map.of());
    }

    public List<Row> rows(DefinitionTable table) {
        return tables.getOrDefault(table, List.of());
    }

    /** True when no table of the bundle carries a row. */
    public boolean isEmpty() {
        return tables.values().stream().allMatch(List::isEmpty);
    }

    public long rowCount() {
        return tables.values().stream().mapToLong(List::size).sum();
    }

    /**
     * One row: column name to text.
     *
     * <p>Reading is lenient so a hand-edited file does not have to repeat every column.
     */
    public static final class Row {

        private final Map<String, String> values;

        public Row() {
            this.values = new LinkedHashMap<>();
        }

        private Row(Map<String, String> values) {
            this.values = new LinkedHashMap<>(values);
        }

        public static Row of(Map<String, String> values) {
            return new Row(values);
        }

        public Row put(String column, String value) {
            values.put(column, value);
            return this;
        }

        /** Value of one column, {@code null} when the column is absent or blank. */
        public String get(String column) {
            String value = values.get(column);
            return value == null || value.isBlank() ? null : value;
        }

        /** Value of one column, or {@code fallback} when it is absent or blank. */
        public String get(String column, String fallback) {
            String value = get(column);
            return value == null ? fallback : value;
        }

        public int getInt(String column, int fallback) {
            String value = get(column);
            if (value == null) {
                return fallback;
            }
            try {
                return Integer.parseInt(value.trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Column '" + column + "' must be a number, found '" + value + "'");
            }
        }

        /** Lenient boolean: TRUE/FALSE, 1/0, YES/NO and 是/否 are accepted. */
        public boolean getBoolean(String column, boolean fallback) {
            String value = get(column);
            if (value == null) {
                return fallback;
            }
            String text = value.trim().toLowerCase(java.util.Locale.ROOT);
            return switch (text) {
                case "true", "1", "yes", "y", "是" -> true;
                case "false", "0", "no", "n", "否" -> false;
                default -> throw new IllegalArgumentException(
                        "Column '" + column + "' must be a boolean, found '" + value + "'");
            };
        }

        /** Comma separated list value, empty when the column is blank. */
        public List<String> getList(String column) {
            String value = get(column);
            if (value == null) {
                return List.of();
            }
            List<String> items = new ArrayList<>();
            for (String part : value.split(",")) {
                String item = part.trim();
                if (!item.isEmpty()) {
                    items.add(item);
                }
            }
            return items;
        }

        public Map<String, String> values() {
            return Map.copyOf(values);
        }

        @Override
        public String toString() {
            return values.toString();
        }
    }
}
