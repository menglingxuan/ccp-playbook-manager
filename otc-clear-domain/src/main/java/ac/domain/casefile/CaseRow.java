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
 * @param formulas           field or managed key to formula text
 * @param unresolvedFormulas formula cells without capturable text
 * @param extras             unmapped column header to text value
 * @param testCaseId         explicit or generated test case id
 * @param testCaseIdSource   origin of {@code testCaseId}
 * @param originalRecordId   deterministic id built from file, sheet and row
 * @param businessRecordId   deterministic id built from business fields
 * @param keyValue           canonical value of the product unique field
 * @param issues             problems found while converting
 * @param empty              true when the row carries no data at all
 */
public record CaseRow(
        int sourceRow,
        int rowNo,
        Map<String, Object> data,
        Map<String, String> managed,
        Map<String, String> formulas,
        List<UnresolvedFormula> unresolvedFormulas,
        Map<String, String> extras,
        String testCaseId,
        TestCaseIdSource testCaseIdSource,
        String originalRecordId,
        String businessRecordId,
        String keyValue,
        List<ValidationIssue> issues,
        boolean empty) {

    public CaseRow {
        data = Map.copyOf(data);
        managed = Map.copyOf(managed);
        formulas = Map.copyOf(formulas);
        unresolvedFormulas = List.copyOf(unresolvedFormulas);
        extras = Map.copyOf(extras);
        issues = List.copyOf(issues);
    }

    public boolean valid() {
        return issues.stream().noneMatch(issue -> issue.level() == IssueLevel.ERROR);
    }

    public boolean hasFormula() {
        return !formulas.isEmpty();
    }

    /** Text used by the free text search column. */
    public String searchText() {
        StringBuilder sb = new StringBuilder();
        appendIfPresent(sb, testCaseId);
        appendIfPresent(sb, keyValue);
        data.values().forEach(value -> appendIfPresent(sb, value == null ? null : String.valueOf(value)));
        managed.values().forEach(value -> appendIfPresent(sb, value));
        extras.values().forEach(value -> appendIfPresent(sb, value));
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
