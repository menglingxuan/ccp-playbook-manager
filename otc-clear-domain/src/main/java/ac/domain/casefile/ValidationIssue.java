package ac.domain.casefile;

/**
 * One problem found while analysing or validating data.
 *
 * @param level    severity
 * @param code     stable machine readable code, for example {@code TYPE_MISMATCH}
 * @param message  human readable message shown in the UI
 * @param row      1-based sheet row, may be {@code null}
 * @param column   1-based sheet column, may be {@code null}
 * @param header   source header involved, may be {@code null}
 * @param fieldKey product field key involved, may be {@code null}
 */
public record ValidationIssue(
        IssueLevel level,
        String code,
        String message,
        Integer row,
        Integer column,
        String header,
        String fieldKey) {

    public static ValidationIssue error(String code, String message) {
        return new ValidationIssue(IssueLevel.ERROR, code, message, null, null, null, null);
    }

    public static ValidationIssue warn(String code, String message) {
        return new ValidationIssue(IssueLevel.WARN, code, message, null, null, null, null);
    }

    public static ValidationIssue info(String code, String message) {
        return new ValidationIssue(IssueLevel.INFO, code, message, null, null, null, null);
    }

    public ValidationIssue atRow(int rowNumber) {
        return new ValidationIssue(level, code, message, rowNumber, column, header, fieldKey);
    }

    public ValidationIssue atColumn(int columnNumber, String headerText) {
        return new ValidationIssue(level, code, message, row, columnNumber, headerText, fieldKey);
    }

    public ValidationIssue forField(String key) {
        return new ValidationIssue(level, code, message, row, column, header, key);
    }
}
