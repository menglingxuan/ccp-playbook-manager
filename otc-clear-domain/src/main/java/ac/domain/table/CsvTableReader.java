package ac.domain.table;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;

/**
 * Reads a delimited text file into a {@link RawTable}. The delimiter is detected from the first
 * records so that both comma and semicolon exports work without extra configuration.
 */
public final class CsvTableReader {

    private static final char[] CANDIDATES = {',', ';', '\t', '|'};

    private CsvTableReader() {
    }

    public static RawTable read(Path file, int maxRows) {
        return read(file, maxRows, null);
    }

    /**
     * Reads a delimited file.
     *
     * @param file      source file
     * @param maxRows   maximum number of rows
     * @param sheetName logical sheet name; when {@code null} the file name is used
     */
    public static RawTable read(Path file, int maxRows, String sheetName) {
        return read(file, maxRows, sheetName, TableLimits.DEFAULT);
    }

    /** Reads a delimited file with explicit size guards. */
    public static RawTable read(Path file, int maxRows, String sheetName, TableLimits limits) {
        return read(file, maxRows, sheetName, limits, true);
    }

    /**
     * Reads at most {@code maxRows} rows of a delimited file and silently drops the rest.
     *
     * <p>Used for previews, where a truncated result is expected instead of an error.
     *
     * @param file    source file
     * @param maxRows maximum number of rows to keep
     * @return table with at most {@code maxRows} rows
     */
    public static RawTable readLimited(Path file, int maxRows) {
        return read(file, maxRows, null, TableLimits.DEFAULT, false);
    }

    /** Truncating read with explicit size guards. */
    public static RawTable readLimited(Path file, int maxRows, TableLimits limits) {
        return read(file, maxRows, null, limits, false);
    }

    /**
     * Reads a preview of a delimited file: only the first {@code keepRows} rows are kept, the record
     * count of the whole file is reported through {@link RawTable#declaredRowCount()}.
     *
     * <p>The file is streamed, so a large export never has to fit into memory for a preview.
     */
    public static RawTable readPreview(Path file, int keepRows, String sheetName, TableLimits limits) {
        return read(file, keepRows, sheetName, limits, false);
    }

    private static RawTable read(Path file, int maxRows, String sheetName, TableLimits limits,
            boolean failOnOverflow) {
        return readWindow(file, 0, maxRows, sheetName, limits, failOnOverflow);
    }

    /**
     * Reads one window of a delimited file: the first {@code skipRows} records are consumed and
     * dropped, the next {@code keepRows} are returned.
     *
     * <p>Used by the wizard pager: it can walk the whole file while only one page is in memory. The
     * record count of the whole file is still reported through {@link RawTable#declaredRowCount()}.
     */
    public static RawTable readPage(Path file, int skipRows, int keepRows, String sheetName,
            TableLimits limits) {
        return readWindow(file, Math.max(skipRows, 0), keepRows, sheetName, limits, false);
    }

    private static RawTable readWindow(Path file, int skipRows, int keepRows, String sheetName,
            TableLimits limits, boolean failOnOverflow) {
        if (file == null || !Files.isRegularFile(file)) {
            throw new TableReadException("The source file is no longer available");
        }
        TableLimits guards = limits == null ? TableLimits.DEFAULT : limits;
        Charset charset = CharsetDetector.detect(file);
        String name = sheetName == null || sheetName.isBlank() ? sheetName(file) : sheetName;
        try (BufferedReader reader = open(file, charset)) {
            char delimiter = detectDelimiter(readHead(file, charset));
            return parseReader(reader, delimiter, name, skipRows, keepRows, guards, failOnOverflow);
        } catch (IOException e) {
            throw new TableReadException("Cannot read the delimited file: " + e.getMessage(), e);
        }
    }

    public static RawTable parse(String content, char delimiter, String sheetName, int maxRows) {
        return parse(content, delimiter, sheetName, maxRows, TableLimits.DEFAULT, true);
    }

    private static RawTable parse(String content, char delimiter, String sheetName, int maxRows,
            TableLimits limits, boolean failOnOverflow) {
        String text = content != null && !content.isEmpty() && content.charAt(0) == '\uFEFF'
                ? content.substring(1) : content;
        try (BufferedReader reader = new BufferedReader(new StringReader(text == null ? "" : text))) {
            return parseReader(reader, delimiter, sheetName, 0, maxRows,
                    limits == null ? TableLimits.DEFAULT : limits, failOnOverflow);
        } catch (IOException e) {
            throw new TableReadException("Cannot parse the delimited content: " + e.getMessage(), e);
        }
    }

    private static RawTable parseReader(BufferedReader reader, char delimiter, String sheetName,
            int skipRows, int keepRows, TableLimits limits, boolean failOnOverflow) {
        CSVFormat format = CSVFormat.Builder.create()
                .setDelimiter(delimiter)
                .setQuote('"')
                .setIgnoreSurroundingSpaces(false)
                .setAllowMissingColumnNames(true)
                .setRecordSeparator('\n')
                .get();
        try (CSVParser parser = CSVParser.parse(reader, format)) {
            RawTable.Builder builder = RawTable.builder(sheetName);
            int records = 0;
            int kept = 0;
            for (CSVRecord record : parser) {
                records++;
                if (records <= skipRows) {
                    continue;
                }
                if (kept >= keepRows) {
                    if (failOnOverflow) {
                        throw new TableReadException(
                                "File has more than " + (skipRows + keepRows) + " rows");
                    }
                    // keep counting so the caller still sees the real row number
                    continue;
                }
                limits.checkColumns(sheetName, records, record.size());
                String[] texts = new String[record.size()];
                for (int column = 0; column < record.size(); column++) {
                    texts[column] = record.get(column);
                    limits.checkCell(sheetName, records, column + 1, texts[column]);
                }
                builder.addRow(RawRow.plain(texts));
                kept++;
            }
            return builder.declaredRowCount(records).build();
        } catch (IOException e) {
            throw new TableReadException("Cannot parse CSV content: " + e.getMessage(), e);
        } catch (java.io.UncheckedIOException e) {
            // iterating the parser rethrows malformed quoting as an unchecked wrapper; that is a
            // file problem, not a server failure, so it has to surface as an invalid request
            Throwable cause = e.getCause() == null ? e : e.getCause();
            throw new TableReadException("Cannot parse CSV content: " + cause.getMessage(), cause);
        }
    }

    /** Opens the file and skips a leading byte order mark. */
    private static BufferedReader open(Path file, Charset charset) throws IOException {
        BufferedReader reader = Files.newBufferedReader(file, charset);
        reader.mark(1);
        int first = reader.read();
        if (first >= 0 && first != '\uFEFF') {
            reader.reset();
        }
        return reader;
    }

    /** First characters of the file, used for delimiter detection only. */
    private static String readHead(Path file, Charset charset) {
        StringBuilder head = new StringBuilder(4096);
        try (BufferedReader reader = open(file, charset)) {
            char[] buffer = new char[4096];
            int read;
            while (head.length() < 8192 && (read = reader.read(buffer)) > 0) {
                head.append(buffer, 0, read);
            }
        } catch (IOException e) {
            return "";
        }
        return head.toString();
    }

    static char detectDelimiter(String content) {
        List<String> lines = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new StringReader(content))) {
            String line;
            while ((line = reader.readLine()) != null && lines.size() < 5) {
                if (!line.isBlank()) {
                    lines.add(line);
                }
            }
        } catch (IOException e) {
            return ',';
        }
        if (lines.isEmpty()) {
            return ',';
        }
        char best = ',';
        int bestScore = 0;
        for (char candidate : CANDIDATES) {
            int minimum = Integer.MAX_VALUE;
            int total = 0;
            for (String line : lines) {
                int count = countOutsideQuotes(line, candidate);
                minimum = Math.min(minimum, count);
                total += count;
            }
            int score = minimum > 0 ? minimum * 100 + total : total;
            if (score > bestScore) {
                bestScore = score;
                best = candidate;
            }
        }
        return best;
    }

    private static int countOutsideQuotes(String line, char candidate) {
        int count = 0;
        boolean quoted = false;
        for (int index = 0; index < line.length(); index++) {
            char c = line.charAt(index);
            if (c == '"') {
                quoted = !quoted;
            } else if (c == candidate && !quoted) {
                count++;
            }
        }
        return count;
    }

    private static String sheetName(Path file) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }
}
