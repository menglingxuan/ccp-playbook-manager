package ac.domain.casefile;

import java.util.List;
import java.util.Map;

/**
 * A converted source row ready to be persisted as a test case.
 *
 * @param sourceRow          1-based row number inside the sheet
 * @param rowNo              1-based position inside the batch
 * @param data               product field key to typed value
 * @param managed            managed key to text value
 * @param rawTexts           original cell texts exactly as read from the sheet
 * @param formulas           field or managed key to formula text
 * @param unresolvedFormulas formula cells without capturable text
 * @param extras             unmapped column header to text value
 * @param testCaseId         explicit or generated test case id
 * @param testCaseIdSource   origin of {@code testCaseId}
 * @param originalRecordId   deterministic id built from file, sheet and row
 * @param businessRecordId   deterministic id built from business fields
 * @param coreBusinessRecordId deterministic id built from the business (trade) fields only
 * @param keyValue           canonical value of the product unique field
 * @param issues             problems found while converting
 * @param empty              true when the row carries no data at all
 */
public record CaseRow(
        int sourceRow,
        int rowNo,
        Map<String, Object> data,
        Map<String, String> managed,
        CellTexts rawTexts,
        Map<String, String> formulas,
        List<UnresolvedFormula> unresolvedFormulas,
        Map<String, String> extras,
        String testCaseId,
        TestCaseIdSource testCaseIdSource,
        String originalRecordId,
        String businessRecordId,
        String coreBusinessRecordId,
        String keyValue,
        List<ValidationIssue> issues,
        boolean empty) {

    public CaseRow {
        data = Map.copyOf(data);
        managed = Map.copyOf(managed);
        rawTexts = rawTexts == null ? CellTexts.EMPTY : rawTexts;
        formulas = Map.copyOf(formulas);
        unresolvedFormulas = List.copyOf(unresolvedFormulas);
        extras = Map.copyOf(extras);
        issues = List.copyOf(issues);
    }

    /** Same row with a replaced issue list, used to attach rule warnings. */
    public CaseRow withIssues(List<ValidationIssue> replacement) {
        return new CaseRow(sourceRow, rowNo, data, managed, rawTexts, formulas, unresolvedFormulas, extras,
                testCaseId, testCaseIdSource, originalRecordId, businessRecordId, coreBusinessRecordId,
                keyValue, replacement, empty);
    }

    /** Texts used by every business computation of this row. */
    public CellTexts texts() {
        return CellTexts.of(data, managed, rawTexts);
    }

    public boolean valid() {
        return issues.stream().noneMatch(issue -> issue.level() == IssueLevel.ERROR);
    }

    public boolean hasFormula() {
        return !formulas.isEmpty();
    }

    /** Text used by the free text search column: the original cell texts, never normalized values. */
    public String searchText() {
        return searchText(texts(), extras);
    }

    /** Same text built from the texts and unmapped columns actually kept for storage. */
    public String searchText(CellTexts storedTexts, Map<String, String> storedExtras) {
        StringBuilder sb = new StringBuilder();
        appendIfPresent(sb, testCaseId);
        appendIfPresent(sb, keyValue);
        storedTexts.data().values().forEach(value -> appendIfPresent(sb, value));
        storedTexts.managed().values().forEach(value -> appendIfPresent(sb, value));
        storedExtras.values().forEach(value -> appendIfPresent(sb, value));
        return sb.toString();
    }

    private static void appendIfPresent(StringBuilder sb, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        if (sb.length() > 0) {
            sb.append(' ');
        }
        sb.append(value);
    }
}
