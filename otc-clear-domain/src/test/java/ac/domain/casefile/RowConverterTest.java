package ac.domain.casefile;

import static org.assertj.core.api.Assertions.assertThat;

import ac.domain.TestTables;
import ac.domain.layout.ColumnLayoutResolver;
import ac.domain.layout.SheetLayout;
import ac.domain.TestSchemas;
import ac.domain.product.ProductSchema;
import ac.domain.table.RawRow;
import ac.domain.table.RawTable;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RowConverterTest {

    private static final ProductSchema SCHEMA = TestSchemas.irs();
    private static final String[] HEADER = {"_TestCaseId", "_Remarks", "Trade ID", "Trade Date", "Bogus",
            "Maturity Date", "Notional", "Notional Currency", "Rate", "Rate", "Direction", "Trailing"};

    private static final int NOTIONAL_COLUMN = 6;

    private static ConversionContext context(RawTable table) {
        SheetLayout layout = ColumnLayoutResolver.resolve(SCHEMA, table, 1, 3);
        return new ConversionContext(SCHEMA, layout, "cases.xlsx", table.sheetName(), 32, false);
    }

    private static String[] dataRow(String testCaseId, String remarks, String tradeId, String tradeDate,
            String bogus, String maturityDate, String notional, String currency, String rate1, String rate2,
            String direction, String trailing) {
        return new String[] {testCaseId, remarks, tradeId, tradeDate, bogus, maturityDate, notional, currency,
                rate1, rate2, direction, trailing};
    }

    @Test
    void convertsValuesManagedColumnsAndExtras() {
        RawTable table = TestTables.table("IRS", HEADER,
                dataRow("TC-0001", "first", "IRS-0001", "2026-01-02", "unmapped", "2031-01-02", "1,500,000.00",
                        "USD", "0.0325", "0.0425", "BUY", "tail"));

        CaseRow row = RowConverter.convert(context(table), table, 2, 1);

        assertThat(row.valid()).isTrue();
        assertThat(row.testCaseId()).isEqualTo("TC-0001");
        assertThat(row.testCaseIdSource()).isEqualTo(TestCaseIdSource.FILE);
        assertThat(row.data()).containsEntry("notional", new BigDecimal("1500000.00"))
                .containsEntry("tradeDate", LocalDate.of(2026, 1, 2))
                .containsEntry("fixedRate", new BigDecimal("0.0325"))
                .containsEntry("floatingRate", new BigDecimal("0.0425"))
                .doesNotContainKey("bogus");
        assertThat(row.managed()).containsEntry("_Remarks", "first")
                .doesNotContainKey("Trailing");
        assertThat(row.extras()).containsEntry("Bogus", "unmapped")
                .containsEntry("Trailing", "tail");
        assertThat(row.keyValue()).isEqualTo("IRS-0001");
        assertThat(row.originalRecordId()).hasSize(32);
        assertThat(row.businessRecordId()).hasSize(32);
    }

    @Test
    void capturesFormulaTextAndKeepsCachedValue() {
        RawTable table = TestTables.withFormulas("IRS", List.of(HEADER,
                        dataRow("", "", "IRS-0002", "2026-01-02", "", "2031-01-02", "100", "USD", "0.03",
                                "0.04", "BUY", "")),
                Map.of(1, Map.of(NOTIONAL_COLUMN, "=D2*E2")));

        CaseRow row = RowConverter.convert(context(table), table, 2, 1);

        assertThat(row.data()).containsEntry("notional", new BigDecimal("100"));
        assertThat(row.formulas()).containsEntry("notional", "=D2*E2");
        assertThat(row.hasFormula()).isTrue();
    }

    @Test
    void businessIdIgnoresCachedValueWhenFormulaIsPresent() {
        RawTable first = TestTables.withFormulas("IRS", List.of(HEADER,
                        dataRow("", "", "IRS-0003", "2026-01-02", "", "2031-01-02", "100", "USD", "0.03",
                                "0.04", "BUY", "")),
                Map.of(1, Map.of(NOTIONAL_COLUMN, "=D2*E2")));
        RawTable second = TestTables.withFormulas("IRS", List.of(HEADER,
                        dataRow("", "", "IRS-0003", "2026-01-02", "", "2031-01-02", "250", "USD", "0.03",
                                "0.04", "BUY", "")),
                Map.of(1, Map.of(NOTIONAL_COLUMN, "=D2*E2")));

        String firstId = RowConverter.convert(context(first), first, 2, 1).businessRecordId();
        String secondId = RowConverter.convert(context(second), second, 2, 1).businessRecordId();

        assertThat(firstId).isEqualTo(secondId);
    }

    @Test
    void invalidValuesProduceBlockingIssues() {
        RawTable table = TestTables.table("IRS", HEADER,
                dataRow("", "", "", "not-a-date", "", "", "abc", "", "", "", "", ""));

        CaseRow row = RowConverter.convert(context(table), table, 2, 1);

        assertThat(row.valid()).isFalse();
        assertThat(row.issues()).extracting(ValidationIssue::code)
                .contains("TYPE_MISMATCH", "MISSING_REQUIRED_FIELD");
    }

    @Test
    void generatesTestCaseIdWhenColumnIsEmpty() {
        RawTable table = TestTables.table("IRS", HEADER,
                dataRow("", "", "IRS-0009", "2026-01-02", "", "2031-01-02", "100", "USD", "0.03", "0.04",
                        "SELL", ""));

        CaseRow row = RowConverter.convert(context(table), table, 2, 1);

        assertThat(row.testCaseIdSource()).isEqualTo(TestCaseIdSource.GENERATED);
        assertThat(row.testCaseId()).startsWith("TC-").hasSize(15);
    }

    @Test
    void rowNumbersFeedTheOriginalId() {
        RawTable table = TestTables.table("IRS", HEADER,
                dataRow("", "", "IRS-0010", "2026-01-02", "", "2031-01-02", "100", "USD", "0.03", "0.04",
                        "BUY", ""),
                dataRow("", "", "IRS-0011", "2026-01-02", "", "2031-01-02", "100", "USD", "0.03", "0.04",
                        "BUY", ""));

        ConversionContext context = context(table);
        String first = RowConverter.convert(context, table, 2, 1).originalRecordId();
        String second = RowConverter.convert(context, table, 3, 2).originalRecordId();

        assertThat(first).isNotEqualTo(second);
        assertThat(RecordIdGenerator.originalId("cases.xlsx", "IRS", 2, 32)).isEqualTo(first);
    }

    @Test
    void blankRowsAreFlaggedAsEmpty() {
        RawTable table = TestTables.table("IRS", HEADER,
                dataRow("", "", "", "", "", "", "", "", "", "", "", ""));

        assertThat(RowConverter.convert(context(table), table, 2, 1).empty()).isTrue();
    }

    @Test
    void formulaWithoutTextIsRecordedAsUnresolved() {
        String[] texts = dataRow("", "", "IRS-0012", "2026-01-02", "", "2031-01-02", "100", "USD", "0.03",
                "0.04", "BUY", "");
        RawTable.Builder builder = RawTable.builder("IRS");
        builder.addRow(RawRow.plain(HEADER));
        String[] errors = new String[texts.length];
        errors[NOTIONAL_COLUMN] = "SHARED_FORMULA_NO_TEXT";
        builder.addRow(new RawRow(texts, new String[texts.length], errors));
        RawTable table = builder.build();

        CaseRow row = RowConverter.convert(context(table), table, 2, 1);

        assertThat(row.issues()).extracting(ValidationIssue::code).contains("SHARED_FORMULA_NO_TEXT");
        assertThat(row.unresolvedFormulas()).hasSize(1);
    }

    @Test
    void normalizesFormulaReferencesWhenRequested() {
        RawTable table = TestTables.withFormulas("IRS", List.of(HEADER,
                        dataRow("", "", "IRS-0013", "2026-01-02", "", "2031-01-02", "100", "USD", "0.03",
                                "0.04", "BUY", "")),
                Map.of(1, Map.of(NOTIONAL_COLUMN, "=D2*E2")));
        SheetLayout layout = ColumnLayoutResolver.resolve(SCHEMA, table, 1, 3);
        ConversionContext context = new ConversionContext(SCHEMA, layout, "cases.xlsx", "IRS", 32, true);

        CaseRow row = RowConverter.convert(context, table, 2, 1);

        assertThat(row.formulas()).containsEntry("notional", "=D#*E#");
    }
}
