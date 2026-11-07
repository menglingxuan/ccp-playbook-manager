package ac.domain.layout;

import static org.assertj.core.api.Assertions.assertThat;

import ac.domain.TestSchemas;
import ac.domain.TestTables;
import ac.domain.product.FieldDef;
import ac.domain.product.FieldRole;
import ac.domain.product.FieldType;
import ac.domain.product.ProductSchema;
import ac.domain.table.RawTable;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ColumnLayoutResolverTest {

    private static final ProductSchema SCHEMA = TestSchemas.small();

    @Test
    void splitsManagedColumnsOnBothSides() {
        RawTable table = TestTables.table("Sheet1",
                new String[] {"_TestCaseId", "_Remarks", "A", "B", "C", "After"},
                new String[] {"TC-1", "note", "a1", "b1", "c1", "x"});

        SheetLayout layout = ColumnLayoutResolver.resolve(SCHEMA, table, 1, 3);

        assertThat(layout.tradeStartColumn()).isEqualTo(3);
        assertThat(layout.tradeEndColumn()).isEqualTo(5);
        assertThat(layout.assignmentsOf(ColumnKind.MANAGED)).extracting(ColumnAssignment::managedKey)
                .containsExactly("_TestCaseId", "_Remarks", "After");
        assertThat(layout.assignmentsOf(ColumnKind.TRADE)).extracting(ColumnAssignment::fieldKey)
                .containsExactly("a", "b", "c");
        assertThat(layout.missingFields()).isEmpty();
    }

    @Test
    void missingMiddleColumnDoesNotShiftFollowingFields() {
        RawTable table = TestTables.table("Sheet1",
                new String[] {"A", "C"},
                new String[] {"a1", "c1"});

        SheetLayout layout = ColumnLayoutResolver.resolve(SCHEMA, table, 1, 1);

        assertThat(layout.assignmentsOf(ColumnKind.TRADE)).extracting(ColumnAssignment::fieldKey)
                .containsExactly("a", "c");
        assertThat(layout.missingFields()).containsExactly("b");
    }

    @Test
    void duplicateHeadersMapInDeclarationOrder() {
        ProductSchema schema = TestSchemas.duplicateRate();
        RawTable table = TestTables.table("Sheet1",
                new String[] {"Trade ID", "Rate", "Rate"},
                new String[] {"T-1", "0.01", "0.02"});

        SheetLayout layout = ColumnLayoutResolver.resolve(schema, table, 1, 1);

        assertThat(layout.assignmentsOf(ColumnKind.TRADE)).extracting(ColumnAssignment::fieldKey)
                .containsExactly("tradeId", "fixedRate", "floatingRate");
    }

    @Test
    void unmappedColumnInsideBlockIsReported() {
        RawTable table = TestTables.table("Sheet1",
                new String[] {"A", "Unknown Extra", "C"},
                new String[] {"a1", "x", "c1"});

        SheetLayout layout = ColumnLayoutResolver.resolve(SCHEMA, table, 1, 1);

        assertThat(layout.assignmentsOf(ColumnKind.UNMAPPED)).extracting(ColumnAssignment::header)
                .containsExactly("Unknown Extra");
        assertThat(layout.mappedTradeColumnCount()).isEqualTo(2);
    }

    @Test
    void normalizesHeaderSpellingVariants() {
        RawTable table = TestTables.table("Sheet1",
                new String[] {"Ａ", " b ", "C."},
                new String[] {"1", "2", "3"});

        SheetLayout layout = ColumnLayoutResolver.resolve(SCHEMA, table, 1, 1);

        assertThat(layout.missingFields()).isEmpty();
        assertThat(layout.mappedTradeColumnCount()).isEqualTo(3);
    }

    @Test
    void detectsTradeBlockEndAtLastMatchingColumn() {
        RawTable table = TestTables.table("Sheet1",
                new String[] {"A", "B", "C", "Basis", "Note"},
                new String[] {"1", "2", "3", "4", "5"});

        SheetLayout layout = ColumnLayoutResolver.resolve(SCHEMA, table, 1, 1);

        assertThat(layout.lastColumn()).isEqualTo(5);
        assertThat(layout.tradeEndColumn()).isEqualTo(3);
        assertThat(layout.assignmentsOf(ColumnKind.MANAGED)).extracting(ColumnAssignment::header)
                .containsExactly("Basis", "Note");
    }

    @Test
    void alignsFieldsAfterFuzzyHeaderMatch() {
        FieldDef field = FieldDef.of("notionalAmount", 1, "Notional Amount", List.of(), FieldType.DECIMAL,
                FieldRole.BUSINESS, false, true, "", "", 1, Set.of("notionalamount"));
        SequenceAligner.Match match = SequenceAligner.align(List.of("Notionl Amount"), List.of(field))
                .matches().get(0);

        assertThat(match.fieldKey()).isEqualTo("notionalAmount");
        assertThat(match.score()).isEqualTo(SequenceAligner.SCORE_FUZZY);
    }
}
