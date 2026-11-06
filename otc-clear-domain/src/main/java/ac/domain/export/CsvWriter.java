package ac.domain.export;

import java.io.IOException;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.util.List;

/**
 * Minimal RFC 4180 CSV writer. Written by hand so quoting rules stay under our control regardless
 * of the external tool expectations.
 */
public final class CsvWriter {

    private static final char BOM = '\uFEFF';

    private CsvWriter() {
    }

    public static String toText(CsvFormatOptions options, List<String> headers, List<List<String>> rows) {
        StringWriter writer = new StringWriter();
        write(writer, options, headers, rows);
        return writer.toString();
    }

    public static void write(Writer writer, CsvFormatOptions options, List<String> headers,
            List<List<String>> rows) {
        try {
            if (options.bom()) {
                writer.write(BOM);
            }
            if (options.includeHeader() && headers != null && !headers.isEmpty()) {
                writeRecord(writer, options, headers);
            }
            for (List<String> row : rows) {
                writeRecord(writer, options, row);
            }
            writer.flush();
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot write CSV content", e);
        }
    }

    private static void writeRecord(Writer writer, CsvFormatOptions options, List<String> values)
            throws IOException {
        for (int index = 0; index < values.size(); index++) {
            if (index > 0) {
                writer.write(options.delimiter());
            }
            writer.write(escape(values.get(index), options));
        }
        writer.write(options.lineEnding());
    }

    static String escape(String value, CsvFormatOptions options) {
        String text = value == null ? "" : value;
        boolean needsQuotes = options.quoteAll()
                || text.indexOf(options.delimiter()) >= 0
                || text.indexOf('"') >= 0
                || text.indexOf('\n') >= 0
                || text.indexOf('\r') >= 0;
        if (!needsQuotes) {
            return text;
        }
        return '"' + text.replace("\"", "\"\"") + '"';
    }
}
