package ac.domain.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import ac.domain.TestSchemas;
import ac.domain.TestTables;
import ac.domain.casefile.IssueLevel;
import ac.domain.casefile.ValidationIssue;
import ac.domain.layout.ColumnKind;
import ac.domain.product.FieldDef;
import ac.domain.product.ProductSchema;
import ac.domain.table.RawTable;
import org.junit.jupiter.api.Test;

class SheetAnalyzerTest {

    private static final ProductSchema SCHEMA = TestSchemas.analyzed();

    private static RawTable table() {
        return TestTables.table("Sheet1",
                new String[] {"Report generated 2026-11-04"},
                new String[] {"_TestCaseId", "Trade ID", "Trade Date", "Notional"},
                new String[] {"TC-1", "T-1", "2026-01-01", "100"},
                new String[] {"TC-2", "T-2", "2026-01-02", "200"});
    }

    @Test
    void detectsHeaderRowAndTradeStart() {
        RawTable table = table();

        assertThat(SheetDetector.detectHeaderRow(SCHEMA, table)).isEqualTo(2);
        assertThat(SheetDetector.detectTradeStartColumn(SCHEMA, table, 2)).isEqualTo(2);
    }

    @Test
    void previewsWithAutoDetectedLayout() {
        RawTable table = table();

        SheetPreview preview = SheetAnalyzer.preview(SCHEMA, table, PreviewOptions.AUTO,
                SheetAnalyzer.DEFAULT_SAMPLE_LIMIT);

        assertThat(preview.valid()).isTrue();
        assertThat(preview.layout()).isNotNull();
        assertThat(preview.layout().headerRow()).isEqualTo(2);
        assertThat(preview.layout().tradeStartColumn()).isEqualTo(2);
        assertThat(preview.layout().assignmentsOf(ColumnKind.MANAGED)).hasSize(1);
        assertThat(preview.sampleRows()).hasSize(2);
        assertThat(preview.sampleRows().get(0).values()).containsEntry("tradeId", "T-1");
        assertThat(preview.stats().dataRows()).isEqualTo(2);
        assertThat(preview.stats().mappedTradeColumns()).isEqualTo(3);
    }

    @Test
    void honoursExplicitParameters() {
        RawTable table = table();

        SheetPreview preview = SheetAnalyzer.preview(SCHEMA, table, new PreviewOptions(2, 2, 3), 20);

        assertThat(preview.valid()).isTrue();
        assertThat(preview.layout().dataStartRow()).isEqualTo(3);
        assertThat(preview.sampleRows()).hasSize(2);
    }

    @Test
    void reportsBlockingIssuesWhenLayoutCannotBeResolved() {
        RawTable table = TestTables.table("Sheet1", new String[] {"Nothing", "Useful"},
                new String[] {"1", "2"});

        SheetPreview preview = SheetAnalyzer.preview(SCHEMA, table, PreviewOptions.AUTO, 20);

        assertThat(preview.valid()).isFalse();
        assertThat(preview.issues()).extracting(ValidationIssue::code).contains("TRADE_BLOCK_NOT_FOUND");
        assertThat(preview.layout()).isNull();
    }

    @Test
    void fullAnalysisCountsValidAndInvalidRows() {
        RawTable table = TestTables.table("Sheet1",
                new String[] {"Trade ID", "Trade Date", "Notional"},
                new String[] {"T-1", "2026-01-01", "100"},
                new String[] {"", "", ""},
                new String[] {"T-2", "bad-date", "200"});

        SheetAnalysis analysis = SheetAnalyzer.analyze(SCHEMA, table, PreviewOptions.AUTO, "file.xlsx", 32,
                false);

        assertThat(analysis.dataRows()).isEqualTo(2);
        assertThat(analysis.validRows()).isEqualTo(1);
        assertThat(analysis.invalidRows()).isEqualTo(1);
        assertThat(analysis.emptyRows()).isEqualTo(1);
        assertThat(analysis.rows()).allSatisfy(row -> assertThat(row.originalRecordId()).hasSize(32));
        assertThat(analysis.rows().get(1).issues()).extracting(ValidationIssue::level)
                .contains(IssueLevel.ERROR);
    }

    @Test
    void missingRequiredFieldIsBlocking() {
        RawTable table = TestTables.table("Sheet1",
                new String[] {"Trade Date", "Notional"},
                new String[] {"2026-01-01", "100"});

        SheetPreview preview = SheetAnalyzer.preview(SCHEMA, table, new PreviewOptions(1, 1, 2), 20);

        assertThat(preview.valid()).isFalse();
        assertThat(preview.issues()).filteredOn(issue -> issue.level() == IssueLevel.ERROR)
                .extracting(ValidationIssue::fieldKey).contains("tradeId");
    }
}
