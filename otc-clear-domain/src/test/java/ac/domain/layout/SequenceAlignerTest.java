package ac.domain.layout;

import static org.assertj.core.api.Assertions.assertThat;

import ac.domain.product.FieldDef;
import ac.domain.product.FieldRole;
import ac.domain.product.FieldType;
import ac.domain.TestSchemas;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SequenceAlignerTest {

    private static FieldDef field(String key, String header) {
        return FieldDef.of(key, 1, header, List.of(), FieldType.TEXT, FieldRole.BUSINESS, false, true, "", "", 1,
                Set.of(ac.domain.product.HeaderNormalizer.normalize(header)));
    }

    @Test
    void matchesIdenticalSequence() {
        List<FieldDef> fields = List.of(field("a", "A"), field("b", "B"), field("c", "C"));

        SequenceAligner.Alignment alignment = SequenceAligner.align(List.of("A", "B", "C"), fields);

        assertThat(alignment.matches()).extracting(SequenceAligner.Match::fieldKey)
                .containsExactly("a", "b", "c");
        assertThat(alignment.missingFields()).isEmpty();
    }

    @Test
    void keepsOrderWhenAColumnIsMissing() {
        List<FieldDef> fields = List.of(field("a", "A"), field("b", "B"), field("c", "C"));

        SequenceAligner.Alignment alignment = SequenceAligner.align(List.of("A", "C"), fields);

        assertThat(alignment.matches()).extracting(SequenceAligner.Match::fieldKey)
                .containsExactly("a", "c");
        assertThat(alignment.missingFields()).extracting(FieldDef::key).containsExactly("b");
    }

    @Test
    void assignsDuplicateHeadersByPosition() {
        List<FieldDef> fields = List.of(field("first", "Rate"), field("second", "Rate"));

        SequenceAligner.Alignment alignment = SequenceAligner.align(List.of("Rate", "Rate"), fields);

        assertThat(alignment.matches()).extracting(SequenceAligner.Match::fieldKey)
                .containsExactly("first", "second");
    }

    @Test
    void reportsUnusedColumns() {
        List<FieldDef> fields = List.of(field("a", "A"));

        SequenceAligner.Alignment alignment = SequenceAligner.align(List.of("A", "Something"), fields);

        assertThat(alignment.unusedColumns()).containsExactly(1);
    }

    @Test
    void noMatchProducesMissingFields() {
        SequenceAligner.Alignment alignment = SequenceAligner.align(List.of("Zzz"), List.of(field("a", "A")));

        assertThat(alignment.matches()).isEmpty();
        assertThat(alignment.missingFields()).hasSize(1);
    }

    @Test
    void realIrsHeadersAlignInDeclarationOrder() {
        var schema = TestSchemas.irs();
        List<String> headers = List.of("Trade ID", "Trade Date", "Maturity Date", "Notional",
                "Notional Currency", "Rate", "Rate");

        SequenceAligner.Alignment alignment = SequenceAligner.align(headers, schema.fields());

        assertThat(alignment.matches()).extracting(SequenceAligner.Match::fieldKey)
                .containsExactly("tradeId", "tradeDate", "maturityDate", "notional", "notionalCurrency",
                        "fixedRate", "floatingRate");
    }
}
