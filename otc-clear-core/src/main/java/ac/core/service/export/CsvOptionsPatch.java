package ac.core.service.export;

import ac.domain.export.CsvFormatOptions;
import ac.domain.export.ManagedColumnScope;
import java.util.Locale;

/**
 * CSV options as sent by a client.
 *
 * <p>Every field is optional: absent fields fall back to the stored tenant defaults, so a partial
 * payload can never produce a file with a NUL delimiter, a missing header row or a broken record
 * separator.
 *
 * @param delimiter      field separator, {@code ","}, {@code ";"}, {@code "TAB"}, {@code "|"} or a
 *                       single character
 * @param quoteAll       quote every field
 * @param includeHeader  write the header row
 * @param lineEnding     {@code "\n"} / {@code "LF"} or {@code "\r\n"} / {@code "CRLF"}
 * @param bom            write a UTF-8 byte order mark
 * @param managedColumns {@code KNOWN}, {@code ALL} or {@code NONE}
 * @param formulaAsValue export the cached value instead of the formula text
 */
public record CsvOptionsPatch(String delimiter, Boolean quoteAll, Boolean includeHeader,
        String lineEnding, Boolean bom, String managedColumns, Boolean formulaAsValue) {

    /** Resolves this patch against the stored defaults. */
    public CsvFormatOptions merge(CsvFormatOptions defaults) {
        CsvFormatOptions base = defaults == null ? CsvFormatOptions.DEFAULT : defaults;
        return new CsvFormatOptions(
                delimiter == null || delimiter.isBlank()
                        ? base.delimiter() : CsvFormatOptions.parseDelimiter(delimiter),
                quoteAll == null ? base.quoteAll() : quoteAll,
                includeHeader == null ? base.includeHeader() : includeHeader,
                lineEnding(lineEnding, base.lineEnding()),
                bom == null ? base.bom() : bom,
                scope(managedColumns, base.managedColumns()),
                formulaAsValue == null ? base.formulaAsValue() : formulaAsValue);
    }

    /** Only LF and CRLF are real record separators; anything else would break the file layout. */
    private static String lineEnding(String requested, String fallback) {
        if (requested == null || requested.isBlank()) {
            return fallback;
        }
        if ("\n".equals(requested) || requested.equalsIgnoreCase("LF")) {
            return "\n";
        }
        if ("\r\n".equals(requested) || requested.equalsIgnoreCase("CRLF")) {
            return "\r\n";
        }
        throw new IllegalArgumentException("Unsupported line ending: " + requested.replace("\r", "\\r")
                .replace("\n", "\\n"));
    }

    private static ManagedColumnScope scope(String requested, ManagedColumnScope fallback) {
        if (requested == null || requested.isBlank()) {
            return fallback;
        }
        try {
            return ManagedColumnScope.valueOf(requested.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unsupported managed column scope: " + requested);
        }
    }
}
