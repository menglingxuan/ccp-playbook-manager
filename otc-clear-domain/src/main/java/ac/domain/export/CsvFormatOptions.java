package ac.domain.export;

/**
 * CSV output options. The charset is always UTF-8 and is intentionally not configurable.
 *
 * @param delimiter      field separator
 * @param quoteAll       quote every field instead of only when required
 * @param includeHeader  write the header row
 * @param lineEnding     record separator, {@code \r\n} by default
 * @param bom            write a UTF-8 byte order mark
 * @param managedColumns which management columns to include
 * @param formulaAsValue export the cached value instead of the formula text
 */
public record CsvFormatOptions(
        char delimiter,
        boolean quoteAll,
        boolean includeHeader,
        String lineEnding,
        boolean bom,
        ManagedColumnScope managedColumns,
        boolean formulaAsValue) {

    public static final CsvFormatOptions DEFAULT = new CsvFormatOptions(
            ',', false, true, "\r\n", false, ManagedColumnScope.KNOWN, true);

    public CsvFormatOptions {
        if (delimiter == '"' || delimiter == '\r' || delimiter == '\n') {
            throw new IllegalArgumentException("Unsupported delimiter: " + delimiter);
        }
        lineEnding = lineEnding == null || lineEnding.isEmpty() ? "\r\n" : lineEnding;
    }

    public static CsvFormatOptions of(String delimiter, boolean quoteAll, boolean includeHeader,
            String lineEnding, boolean bom, ManagedColumnScope scope, boolean formulaAsValue) {
        char parsed = delimiter == null || delimiter.isEmpty() ? ',' : parseDelimiter(delimiter);
        return new CsvFormatOptions(parsed, quoteAll, includeHeader, lineEnding, bom, scope, formulaAsValue);
    }

    /** Accepts {@code ","}, {@code ";"}, {@code "\t"} and {@code "|"}. */
    public static char parseDelimiter(String value) {
        String normalized = value == null ? "," : value;
        return switch (normalized) {
            case "\\t", "\t", "TAB", "tab" -> '\t';
            case ",", "COMMA" -> ',';
            case ";" -> ';';
            case "|" -> '|';
            default -> {
                if (normalized.length() == 1) {
                    yield normalized.charAt(0);
                }
                throw new IllegalArgumentException("Unsupported delimiter: " + value);
            }
        };
    }

    public String delimiterLabel() {
        return switch (delimiter) {
            case '\t' -> "TAB";
            case ',' -> "COMMA";
            default -> String.valueOf(delimiter);
        };
    }
}
