package ac.domain.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class FieldCodecTest {

    private static FieldDef field(FieldType type) {
        return FieldDef.of("value", 1, "Value", java.util.List.of(), type, FieldRole.BUSINESS, false, true,
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

    @Test
    void enumeratedValuesAreValidatedAgainstAllowedLabels() {
        FieldDef enumField = new FieldDef("convention", 1, "Conversion Method", java.util.List.of(),
                FieldType.ENUM, FieldRole.BUSINESS, false, true, false, "", "", 1, java.util.Set.of(),
                java.util.List.of("FIELD_TAG"), java.util.List.of("Modified Following", "Flowing", "Preceding"));

        assertThat(FieldCodec.parse(enumField, "flowing")).isEqualTo("Flowing");
        assertThatThrownBy(() -> FieldCodec.parse(enumField, "Unknown"))
                .isInstanceOf(FieldFormatException.class)
                .hasMessageContaining("Modified Following/Flowing/Preceding");
    }

    @Test
    void enumeratedFieldWithoutAllowedValuesAcceptsAnyText() {
        FieldDef enumField = FieldDef.of("convention", 1, "Conversion Method", java.util.List.of(),
                FieldType.ENUM, FieldRole.BUSINESS, false, true, "", "", 1, java.util.Set.of());

        assertThat(FieldCodec.parse(enumField, "Anything")).isEqualTo("Anything");
    }
}
