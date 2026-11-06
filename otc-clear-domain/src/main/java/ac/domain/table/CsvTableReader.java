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
        if (file == null || !Files.isRegularFile(file)) {
            throw new TableReadException("File does not exist: " + file);
        }
        Charset charset = CharsetDetector.detect(file);
        try {
            String content = Files.readString(file, charset);
            if (!content.isEmpty() && content.charAt(0) == '\uFEFF') {
                content = content.substring(1);
            }
            char delimiter = detectDelimiter(content);
            String name = sheetName == null || sheetName.isBlank() ? sheetName(file) : sheetName;
            return parse(content, delimiter, name, maxRows);
        } catch (IOException e) {
            throw new TableReadException("Cannot read CSV " + file.getFileName() + ": " + e.getMessage(), e);
        }
    }

    public static RawTable parse(String content, char delimiter, String sheetName, int maxRows) {
        CSVFormat format = CSVFormat.Builder.create()
                .setDelimiter(delimiter)
                .setQuote('"')
                .setIgnoreSurroundingSpaces(false)
                .setAllowMissingColumnNames(true)
                .setRecordSeparator('\n')
                .get();
        try (CSVParser parser = CSVParser.parse(new StringReader(content), format)) {
            RawTable.Builder builder = RawTable.builder(sheetName);
            int rowCount = 0;
            for (CSVRecord record : parser) {
                rowCount++;
                if (rowCount > maxRows) {
                    throw new TableReadException(
                            "File has more than " + maxRows + " rows");
                }
                String[] texts = new String[record.size()];
                for (int column = 0; column < record.size(); column++) {
                    texts[column] = record.get(column);
                }
                builder.addRow(RawRow.plain(texts));
            }
            return builder.build();
        } catch (IOException e) {
            throw new TableReadException("Cannot parse CSV content: " + e.getMessage(), e);
        }
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
