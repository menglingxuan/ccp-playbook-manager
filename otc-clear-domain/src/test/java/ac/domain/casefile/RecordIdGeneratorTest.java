package ac.domain.casefile;

import static org.assertj.core.api.Assertions.assertThat;

import ac.domain.product.ProductSchema;
import ac.domain.product.ProductSchemaFactory;
import ac.domain.product.products.IrsCase;
import ac.domain.product.FieldCodec;
import java.math.BigDecimal;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RecordIdGeneratorTest {

    private static final ProductSchema SCHEMA = ProductSchemaFactory.create(IrsCase.class);

    @Test
    void originalIdIsDeterministic() {
        String first = RecordIdGenerator.originalId("cases.xlsx", "IRS", 7, 32);
        String second = RecordIdGenerator.originalId("cases.xlsx", "IRS", 7, 32);

        assertThat(first).isEqualTo(second).hasSize(32);
        assertThat(RecordIdGenerator.originalId("cases.xlsx", "IRS", 8, 32)).isNotEqualTo(first);
        assertThat(RecordIdGenerator.originalId("other.xlsx", "IRS", 7, 32)).isNotEqualTo(first);
    }

    @Test
    void businessIdUsesFormulaTextOverCachedValue() {
        Map<String, Object> data = Map.of("tradeId", "T-1", "notional", new BigDecimal("100"));
        Map<String, String> formulas = Map.of("notional", "=D2*E2");

        String withFormula = RecordIdGenerator.businessId(SCHEMA, data, formulas, 32);
        String otherCache = RecordIdGenerator.businessId(SCHEMA,
                Map.of("tradeId", "T-1", "notional", new BigDecimal("999")), formulas, 32);

        assertThat(withFormula).isEqualTo(otherCache);
    }

    @Test
    void businessIdChangesWithBusinessValues() {
        Map<String, Object> data = Map.of("tradeId", "T-1");
        Map<String, Object> other = Map.of("tradeId", "T-2");

        assertThat(RecordIdGenerator.businessId(SCHEMA, data, Map.of(), 32))
                .isNotEqualTo(RecordIdGenerator.businessId(SCHEMA, other, Map.of(), 32));
    }

    @Test
    void generatedTestCaseIdIsReadable() {
        String id = RecordIdGenerator.generatedTestCaseId("abcdef1234567890");

        assertThat(id).isEqualTo("TC-ABCDEF123456");
    }

    @Test
    void cloneIdsAreUniquePerSequenceAndTraceable() {
        String base = RecordIdGenerator.originalId("cases.xlsx", "IRS", 2, 32);

        assertThat(RecordIdGenerator.cloneOriginalId(base, 1, 32))
                .isNotEqualTo(RecordIdGenerator.cloneOriginalId(base, 2, 32))
                .isNotEqualTo(base)
                .hasSize(32);
    }

    @Test
    void canonicalValuesMatchFieldCodec() {
        assertThat(FieldCodec.toCanonical(new BigDecimal("0.032500"))).isEqualTo("0.0325");
    }
}
