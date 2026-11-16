package ac.core.service.importflow;

import ac.domain.casefile.ValidationIssue;
import java.util.List;

/**
 * Import submission result.
 *
 * @param taskId       import task that owns the new batches
 * @param taskName     name of that task; the requested one when just created
 * @param joined       true when the batches were appended to an existing task
 * @param status       status of this import run
 * @param totalRows    rows written to the case store; a row with cell errors is written too, so this
 *                     is not the count of clean rows
 * @param invalidRows  rows that carry an error level issue (they are counted in {@code totalRows} when
 *                     "stop before the first error row" is switched off, and rejected otherwise)
 * @param batchCount   number of created batches
 * @param batches      per sheet results
 * @param issues       collected issues, capped by configuration
 */
public record ImportResult(
        String taskId,
        String taskName,
        boolean joined,
        String status,
        int totalRows,
        int invalidRows,
        int batchCount,
        List<BatchImportResult> batches,
        List<ValidationIssue> issues) {

    public ImportResult {
        batches = List.copyOf(batches);
        issues = List.copyOf(issues);
    }
}
