package ac.domain.layout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ac.domain.TestSchemas;
import ac.domain.TestTables;
import ac.domain.table.RawTable;
import org.junit.jupiter.api.Test;

class ColumnLayoutResolverTest {

    @Test
    void declaredManagedColumnsAreManagedAndTheRestIsUnmapped() {
        RawTable table = TestTables.table("Sheet1",
                new String[] {"_TestCaseId", "_Remarks", "A", "B", "C", "Extra Ref", "After"},
                new String[] {"TC-1", "note", "a1", "b1", "c1", "ref", "x"});

        SheetLayout layout = ColumnLayoutResolver.resolve(TestSchemas.smallWithManaged(), table, 1, 3);

        assertThat(layout.assignmentsOf(ColumnKind.MANAGED)).extracting(ColumnAssignment::managedKey)
                .containsExactly("_TestCaseId", "_Remarks");
        assertThat(layout.assignmentsOf(ColumnKind.TRADE)).extracting(ColumnAssignment::fieldKey)
                .containsExactly("a", "b", "c");
        assertThat(layout.assignmentsOf(ColumnKind.UNMAPPED)).extracting(ColumnAssignment::header)
                .containsExactly("Extra Ref", "After");
        assertThat(layout.blockContiguous()).isTrue();
        assertThat(layout.tradeStartColumn()).isEqualTo(3);
        assertThat(layout.tradeEndColumn()).isEqualTo(5);
    }

    @Test
    void undeclaredSystemColumnsStayUnmapped() {
        RawTable table = TestTables.table("Sheet1",
                new String[] {"_TestCaseId", "_Remarks", "A", "B", "C"},
                new String[] {"TC-1", "note", "a1", "b1", "c1"});

        SheetLayout layout = ColumnLayoutResolver.resolve(TestSchemas.small(), table, 1, 3);

        assertThat(layout.assignmentsOf(ColumnKind.MANAGED)).isEmpty();
        assertThat(layout.assignmentsOf(ColumnKind.UNMAPPED)).extracting(ColumnAssignment::header)
                .containsExactly("_TestCaseId", "_Remarks");
    }

    @Test
    void businessFieldsOnlyStartAtTheGivenColumn() {
        RawTable table = TestTables.table("Sheet1",
                new String[] {"C", "A", "B"},
                new String[] {"c1", "a1", "b1"});

        SheetLayout layout = ColumnLayoutResolver.resolve(TestSchemas.small(), table, 1, 2);

        assertThat(layout.assignmentsOf(ColumnKind.TRADE)).extracting(ColumnAssignment::fieldKey)
                .containsExactly("a", "b");
        assertThat(layout.assignmentsOf(ColumnKind.UNMAPPED)).extracting(ColumnAssignment::header)
                .containsExactly("C");
        assertThat(layout.missingFields()).containsExactly("c");
    }

    @Test
    void missingMiddleColumnDoesNotShiftFollowingFields() {
        RawTable table = TestTables.table("Sheet1",
                new String[] {"A", "C"},
                new String[] {"a1", "c1"});

        SheetLayout layout = ColumnLayoutResolver.resolve(TestSchemas.small(), table, 1, 1);

        assertThat(layout.assignmentsOf(ColumnKind.TRADE)).extracting(ColumnAssignment::fieldKey)
                .containsExactly("a", "c");
        assertThat(layout.missingFields()).containsExactly("b");
        assertThat(layout.blockContiguous()).isTrue();
    }

    @Test
    void unmappedColumnInsideTheRegionBreaksContiguity() {
        RawTable table = TestTables.table("Sheet1",
                new String[] {"A", "Bogus", "C"},
                new String[] {"a1", "x", "c1"});

        SheetLayout layout = ColumnLayoutResolver.resolve(TestSchemas.small(), table, 1, 1);

        assertThat(layout.assignmentsOf(ColumnKind.UNMAPPED)).extracting(ColumnAssignment::header)
                .containsExactly("Bogus");
        assertThat(layout.blockContiguous()).isFalse();
        assertThat(layout.tradeEndColumn()).isEqualTo(3);
    }

    @Test
    void duplicateNamesArePairedInFileOrder() {
        RawTable table = TestTables.table("Sheet1",
                new String[] {"Trade ID", "Rate", "Rate"},
                new String[] {"T-1", "0.03", "0.04"});

        SheetLayout layout = ColumnLayoutResolver.resolve(TestSchemas.duplicateRate(), table, 1, 1);

        assertThat(layout.assignmentsOf(ColumnKind.TRADE)).extracting(ColumnAssignment::fieldKey)
                .containsExactly("tradeId", "fixedRate", "floatingRate");
        assertThat(layout.missingFields()).isEmpty();
    }

    @Test
    void surplusColumnsMatchNoFieldOnceTheQueueIsDrained() {
        RawTable table = TestTables.table("Sheet1",
                new String[] {"Trade ID", "Rate", "Rate", "Rate"},
                new String[] {"T-1", "0.03", "0.04", "0.05"});

        SheetLayout layout = ColumnLayoutResolver.resolve(TestSchemas.duplicateRate(), table, 1, 1);

        assertThat(layout.assignmentsOf(ColumnKind.TRADE)).extracting(ColumnAssignment::fieldKey)
                .containsExactly("tradeId", "fixedRate", "floatingRate");
        assertThat(layout.assignmentsOf(ColumnKind.UNMAPPED)).hasSize(1);
    }

    @Test
    void nameMatchingIgnoresCaseAndSurroundingSpaces() {
        RawTable table = TestTables.table("Sheet1",
                new String[] {"  a ", "B"},
                new String[] {"a1", "b1"});

        SheetLayout layout = ColumnLayoutResolver.resolve(TestSchemas.small(), table, 1, 1);

        assertThat(layout.assignmentsOf(ColumnKind.TRADE)).extracting(ColumnAssignment::fieldKey)
                .containsExactly("a", "b");
    }

    @Test
    void punctuationDifferencesDoNotMatch() {
        RawTable table = TestTables.table("Sheet1",
                new String[] {"Trade_Date", "Notional"},
                new String[] {"2026-01-02", "1000"});

        SheetLayout layout = ColumnLayoutResolver.resolve(TestSchemas.analyzed(), table, 1, 1);

        assertThat(layout.assignmentsOf(ColumnKind.TRADE)).extracting(ColumnAssignment::fieldKey)
                .containsExactly("notional");
        assertThat(layout.missingFields()).contains("tradeId", "tradeDate");
        assertThat(layout.assignmentsOf(ColumnKind.UNMAPPED)).extracting(ColumnAssignment::header)
                .contains("Trade_Date");
    }

    @Test
    void aliasesAreNotUsedForMatching() {
        RawTable table = TestTables.table("Sheet1",
                new String[] {"Trade ID", "CCY"},
                new String[] {"T-1", "USD"});

        SheetLayout layout = ColumnLayoutResolver.resolve(TestSchemas.irs(), table, 1, 1);

        assertThat(layout.assignmentsOf(ColumnKind.TRADE)).extracting(ColumnAssignment::fieldKey)
                .containsExactly("tradeId");
        assertThat(layout.missingFields()).contains("notionalCurrency");
    }

    @Test
    void crossedFieldOrderStillMatchesByName() {
        // product fields were reordered to Trade Date, Trade ID while the file keeps the base order
        RawTable table = TestTables.table("Sheet1",
                new String[] {"Trade ID", "Trade Date", "Notional"},
                new String[] {"T-1", "2026-01-02", "1000"});

        SheetLayout layout = ColumnLayoutResolver.resolve(TestSchemas.analyzedSwappedKeyOrder(), table, 1, 1);

        assertThat(layout.missingFields()).isEmpty();
        assertThat(layout.assignmentsOf(ColumnKind.UNMAPPED)).isEmpty();
        assertThat(layout.assignmentsOf(ColumnKind.TRADE)).extracting(ColumnAssignment::fieldKey)
                .containsExactly("tradeId", "tradeDate", "notional");
        assertThat(layout.mappedTradeColumnCount()).isEqualTo(3);
    }

    @Test
    void strictRegionCoversBusinessAndManagedFieldsInOrder() {
        RawTable table = TestTables.table("Sheet1",
                new String[] {"_TestCaseId", "_Remarks", "Trade ID", "Rate", "Rate", "Extra Ref"},
                new String[] {"TC-1", "note", "T-1", "0.03", "0.04", "ref"});

        SheetLayout layout = ColumnLayoutResolver.resolve(TestSchemas.strictIrs(), table, 1, 1);

        assertThat(layout.tradeStartColumn()).isEqualTo(1);
        assertThat(layout.tradeEndColumn()).isEqualTo(5);
        assertThat(layout.assignmentsOf(ColumnKind.MANAGED)).extracting(ColumnAssignment::managedKey)
                .containsExactly("_TestCaseId", "_Remarks");
        assertThat(layout.assignmentsOf(ColumnKind.TRADE)).extracting(ColumnAssignment::fieldKey)
                .containsExactly("tradeId", "fixedRate", "floatingRate");
        assertThat(layout.assignmentsOf(ColumnKind.UNMAPPED)).extracting(ColumnAssignment::header)
                .containsExactly("Extra Ref");
        assertThat(layout.missingFields()).isEmpty();
        assertThat(layout.blockContiguous()).isTrue();
    }

    @Test
    void strictRegionTurnsUnknownColumnsIntoUnmappedColumns() {
        RawTable table = TestTables.table("Sheet1",
                new String[] {"_TestCaseId", "_Remarks", "Trade ID", "Bogus", "Rate"},
                new String[] {"TC-1", "note", "T-1", "x", "0.03"});

        SheetLayout layout = ColumnLayoutResolver.resolve(TestSchemas.strictIrs(), table, 1, 1);

        assertThat(layout.assignmentsOf(ColumnKind.UNMAPPED)).extracting(ColumnAssignment::header)
                .containsExactly("Bogus");
        assertThat(layout.blockContiguous()).isFalse();
        assertThat(layout.missingFields()).containsExactly("fixedRate");
    }

    @Test
    void strictModeMapsByPositionEvenWhenTheFileIsShort() {
        RawTable table = TestTables.table("Sheet1",
                new String[] {"Trade ID", "Rate"},
                new String[] {"T-1", "0.03"});

        SheetLayout layout = ColumnLayoutResolver.resolve(TestSchemas.duplicateRateStrict(), table, 1, 1);

        assertThat(layout.assignmentsOf(ColumnKind.TRADE)).extracting(ColumnAssignment::fieldKey)
                .containsExactly("tradeId", "fixedRate");
        assertThat(layout.missingFields()).containsExactly("floatingRate");
        assertThat(layout.blockContiguous()).isFalse();
    }

    @Test
    void strictModeLeavesColumnsUnmappedWhenTheOrderDiffers() {
        RawTable table = TestTables.table("Sheet1",
                new String[] {"Rate", "Trade ID"},
                new String[] {"0.03", "T-1"});

        SheetLayout layout = ColumnLayoutResolver.resolve(TestSchemas.duplicateRateStrict(), table, 1, 1);

        assertThat(layout.assignmentsOf(ColumnKind.TRADE)).isEmpty();
        assertThat(layout.blockContiguous()).isFalse();
        assertThat(layout.missingFields()).containsExactly("tradeId", "fixedRate", "floatingRate");
    }

    @Test
    void failsWhenNothingMatches() {
        RawTable table = TestTables.table("Sheet1",
                new String[] {"Nothing", "Useful"},
                new String[] {"1", "2"});

        assertThatThrownBy(() -> ColumnLayoutResolver.resolve(TestSchemas.small(), table, 1, 1))
                .isInstanceOf(LayoutException.class)
                .hasMessageContaining("No trade column matched");
    }
}
