package ac.domain.casefile;

import static org.assertj.core.api.Assertions.assertThat;

import ac.domain.TestSchemas;
import ac.domain.product.FieldCodec;
import ac.domain.product.ProductSchema;
import java.math.BigDecimal;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RecordIdGeneratorTest {

    private static final ProductSchema SCHEMA = TestSchemas.irs();

    /** Texts of a row built from typed values only, as rows stored before raw texts look like. */
    private static CellTexts texts(Map<String, Object> data) {
        return CellTexts.of(data, Map.of(), CellTexts.EMPTY);
    }

    private static CellTexts texts(Map<String, Object> data, Map<String, Object> managed) {
        Map<String, String> columns = new java.util.LinkedHashMap<>();
        managed.forEach((key, value) -> columns.put(key, String.valueOf(value)));
        return CellTexts.of(data, columns, CellTexts.EMPTY);
    }

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
        CellTexts texts = texts(Map.of("tradeId", "T-1", "notional", new BigDecimal("100")));
        Map<String, String> formulas = Map.of("notional", "=D2*E2");

        String withFormula = RecordIdGenerator.businessId(SCHEMA, texts, formulas, 32);
        String otherCache = RecordIdGenerator.businessId(SCHEMA,
                texts(Map.of("tradeId", "T-1", "notional", new BigDecimal("999"))), formulas, 32);

        assertThat(withFormula).isEqualTo(otherCache);
    }

    @Test
    void businessIdChangesWithBusinessValues() {
        assertThat(RecordIdGenerator.businessId(SCHEMA, texts(Map.of("tradeId", "T-1")), Map.of(), 32))
                .isNotEqualTo(RecordIdGenerator.businessId(SCHEMA, texts(Map.of("tradeId", "T-2")),
                        Map.of(), 32));
    }

    @Test
    void businessRecordIdUsesExactText() {
        // the same logical number with a different scale differs: nothing is normalized
        String plain = RecordIdGenerator.businessId(SCHEMA,
                texts(Map.of("notional", new BigDecimal("1000"))), Map.of(), 32);
        String scaled = RecordIdGenerator.businessId(SCHEMA,
                texts(Map.of("notional", new BigDecimal("1000.00"))), Map.of(), 32);

        assertThat(plain).isNotEqualTo(scaled);
        // a text value and the same text typed as a number also differ
        assertThat(RecordIdGenerator.businessId(SCHEMA, texts(Map.of("notional", "1000")), Map.of(), 32))
                .isEqualTo(plain);
    }

    @Test
    void originalTextWinsOverTheTypedValue() {
        // the cell text "1,500,000.00" was parsed into 1500000.00; the id must follow the cell text
        CellTexts typedOnly = texts(Map.of("notional", new BigDecimal("1500000.00")));
        CellTexts withRaw = CellTexts.of(Map.of("notional", new BigDecimal("1500000.00")), Map.of(),
                new CellTexts(Map.of("notional", "1,500,000.00"), Map.of()));

        assertThat(RecordIdGenerator.businessId(SCHEMA, typedOnly, Map.of(), 32))
                .isNotEqualTo(RecordIdGenerator.businessId(SCHEMA, withRaw, Map.of(), 32));
        // surrounding spaces are part of the original text as well
        CellTexts padded = CellTexts.of(Map.of("tradeId", "T-1"), Map.of(),
                new CellTexts(Map.of("tradeId", "  T-1  "), Map.of()));
        assertThat(RecordIdGenerator.businessId(SCHEMA, texts(Map.of("tradeId", "T-1")), Map.of(), 32))
                .isNotEqualTo(RecordIdGenerator.businessId(SCHEMA, padded, Map.of(), 32));
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

    /**
     * Products seeded by the platform flag their managed columns as business keys, so the business
     * record id follows remarks edits while the core id does not.
     */
    @Test
    void coreBusinessIdIgnoresManagedColumns() {
        ProductSchema schema = ProductSchema.assemble("IRS", "Interest Rate Swap", "", java.util.List.of(
                new ac.domain.product.FieldDef("testCaseId", 1, "_TestCaseId", java.util.List.of(),
                        ac.domain.product.FieldType.TEXT, ac.domain.product.FieldRole.MANAGED, false, true,
                        false, "", "", 1, java.util.List.of(), java.util.List.of()),
                new ac.domain.product.FieldDef("remarks", 2, "_Remarks", java.util.List.of(),
                        ac.domain.product.FieldType.TEXT, ac.domain.product.FieldRole.MANAGED, false, true,
                        false, "", "", 1, java.util.List.of(), java.util.List.of()),
                new ac.domain.product.FieldDef("tradeId", 3, "Trade ID", java.util.List.of(),
                        ac.domain.product.FieldType.TEXT, ac.domain.product.FieldRole.BUSINESS, true, true,
                        true, "", "", 1, java.util.List.of(), java.util.List.of())));

        CellTexts trade = texts(Map.of("tradeId", "T-1"));
        // managed values are stored under the storage key, not the field key
        CellTexts first = texts(Map.of("tradeId", "T-1"),
                Map.of("_TestCaseId", "TC-1", "_Remarks", "first"));
        CellTexts second = texts(Map.of("tradeId", "T-1"),
                Map.of("_TestCaseId", "TC-9", "_Remarks", "second"));

        assertThat(RecordIdGenerator.businessId(schema, first, Map.of(), 32))
                .isNotEqualTo(RecordIdGenerator.businessId(schema, second, Map.of(), 32));
        assertThat(RecordIdGenerator.coreBusinessId(schema, trade, Map.of(), 32))
                .isNotEqualTo(RecordIdGenerator.businessId(schema, first, Map.of(), 32))
                .isNotEqualTo(RecordIdGenerator.coreBusinessId(schema, texts(Map.of("tradeId", "T-2")),
                        Map.of(), 32))
                .hasSize(32);
    }
}
