package ac.domain.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class FieldCodecTest {

    private static FieldDef field(FieldType type) {
        return new FieldDef("value", 1, "Value", java.util.List.of(), type, FieldRole.TRADE, false, true,
                "", "", 1, java.util.Set.of("value"));
    }

    @Test
    void blankBecomesNull() {
        assertThat(FieldCodec.parse(field(FieldType.TEXT), "   ")).isNull();
        assertThat(FieldCodec.parse(field(FieldType.DECIMAL), "")).isNull();
    }

    @Test
    void parsesGroupedNumbers() {
        assertThat(FieldCodec.parse(field(FieldType.DECIMAL), "1,500,000.25"))
                .isEqualTo(new BigDecimal("1500000.25"));
        assertThat(FieldCodec.parse(field(FieldType.INTEGER), "１２３")).isEqualTo(123L);
    }

    @Test
    void parsesDatesInSeveralFormats() {
        assertThat(FieldCodec.parse(field(FieldType.DATE), "2026-11-03")).isEqualTo(LocalDate.of(2026, 11, 3));
        assertThat(FieldCodec.parse(field(FieldType.DATE), "03/11/2026")).isEqualTo(LocalDate.of(2026, 11, 3));
        assertThat(FieldCodec.parse(field(FieldType.DATE), "20261103")).isEqualTo(LocalDate.of(2026, 11, 3));
    }

    @Test
    void rejectsInvalidValues() {
        assertThatThrownBy(() -> FieldCodec.parse(field(FieldType.DECIMAL), "abc"))
                .isInstanceOf(FieldFormatException.class)
                .hasMessageContaining("not a number");
        assertThatThrownBy(() -> FieldCodec.parse(field(FieldType.INTEGER), "1.5"))
                .isInstanceOf(FieldFormatException.class);
        assertThatThrownBy(() -> FieldCodec.parse(field(FieldType.DATE), "31/31/2026"))
                .isInstanceOf(FieldFormatException.class);
    }

    @Test
    void canonicalFormIgnoresFormatting() {
        assertThat(FieldCodec.toCanonical(new BigDecimal("1.5000"))).isEqualTo("1.5");
        assertThat(FieldCodec.toCanonical(new BigDecimal("2.000"))).isEqualTo("2");
        assertThat(FieldCodec.toCanonical(new BigDecimal("0.00"))).isEqualTo("0");
        assertThat(FieldCodec.toCanonical(null)).isEmpty();
    }

    @Test
    void booleanAcceptsCommonSpellings() {
        assertThat(FieldCodec.parse(field(FieldType.BOOLEAN), "Y")).isEqualTo(Boolean.TRUE);
        assertThat(FieldCodec.parse(field(FieldType.BOOLEAN), "no")).isEqualTo(Boolean.FALSE);
    }
}
