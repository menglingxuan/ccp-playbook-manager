package ac.core.service.cases;

import java.util.List;

/**
 * Outcome of a bulk field update.
 *
 * @param fieldKey target field key
 * @param mode     applied mode
 * @param dryRun   true when nothing was written
 * @param matched  cases selected by the scope
 * @param updated  cases actually rewritten
 * @param samples  first few before/after pairs, capped
 * @param issues   rows that could not be updated, capped
 */
public record BulkFieldUpdateResult(
        String fieldKey,
        String mode,
        boolean dryRun,
        int matched,
        int updated,
        List<Sample> samples,
        List<Issue> issues) {

    public BulkFieldUpdateResult {
        samples = List.copyOf(samples);
        issues = List.copyOf(issues);
    }

    /**
     * One before/after pair shown in the confirmation preview.
     *
     * @param caseId     case identifier
     * @param testCaseId test case id, may be {@code null}
     * @param rowNo      row number inside the batch
     * @param before     current display text (a formula shows with a leading {@code ƒ})
     * @param after      display text after the update
     */
    public record Sample(String caseId, String testCaseId, int rowNo, String before, String after) {
    }

    /**
     * One row that could not be updated.
     *
     * @param caseId case identifier
     * @param rowNo  row number inside the batch
     * @param code   stable reason code
     * @param message human readable reason
     */
    public record Issue(String caseId, int rowNo, String code, String message) {
    }
}
