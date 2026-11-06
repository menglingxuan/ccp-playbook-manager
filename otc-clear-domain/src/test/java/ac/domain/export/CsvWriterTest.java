package ac.domain.export;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class CsvWriterTest {

    @Test
    void writesHeaderAndRowsWithDefaultOptions() {
        String text = CsvWriter.toText(CsvFormatOptions.DEFAULT, List.of("A", "B"),
                List.of(List.of("1", "2")));

        assertThat(text).isEqualTo("A,B\r\n1,2\r\n");
    }

    @Test
    void quotesOnlyWhenRequired() {
        CsvFormatOptions options = new CsvFormatOptions(',', false, true, "\n", false,
                ManagedColumnScope.KNOWN, true);

        String text = CsvWriter.toText(options, List.of("A", "B"), List.of(List.of("x,y", "say \"hi\"")));

        assertThat(text).isEqualTo("A,B\n\"x,y\",\"say \"\"hi\"\"\"\n");
    }

    @Test
    void supportsSemicolonTabAndPipe() {
        for (String delimiter : List.of(";", "\\t", "|")) {
            CsvFormatOptions options = CsvFormatOptions.of(delimiter, false, false, "\n", false,
                    ManagedColumnScope.NONE, true);
            String text = CsvWriter.toText(options, List.of(), List.of(List.of("a", "b")));
            assertThat(text).endsWith("\n").contains("a").contains("b");
            assertThat(text.charAt(1)).isEqualTo(options.delimiter());
        }
    }

    @Test
    void quoteAllWrapsEveryValue() {
        CsvFormatOptions options = new CsvFormatOptions(',', true, true, "\r\n", false,
                ManagedColumnScope.KNOWN, true);

        String text = CsvWriter.toText(options, List.of("A"), List.of(List.of("1")));

        assertThat(text).isEqualTo("\"A\"\r\n\"1\"\r\n");
    }

    @Test
    void bomIsWrittenAtTheStart() {
        CsvFormatOptions options = new CsvFormatOptions(',', false, true, "\n", true,
                ManagedColumnScope.KNOWN, true);

        assertThat(CsvWriter.toText(options, List.of("A"), List.of())).startsWith("\uFEFFA\n");
    }

    @Test
    void parsesSingleCharacterDelimitersAndRejectsUnknownOnes() {
        assertThat(CsvFormatOptions.parseDelimiter("<")).isEqualTo('<');
        assertThat(CsvFormatOptions.parseDelimiter("TAB")).isEqualTo('\t');
        assertThat(org.assertj.core.api.Assertions.catchThrowable(
                        () -> CsvFormatOptions.parseDelimiter("::")))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
