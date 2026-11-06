package ac.core.service.importflow;

import ac.domain.casefile.ValidationIssue;
import java.util.List;

/**
 * Import submission result.
 *
 * @param taskId      created import task
 * @param status      task status
 * @param totalRows   valid rows stored
 * @param invalidRows rows skipped because of blocking issues
 * @param batchCount  number of created batches
 * @param batches     per sheet results
 * @param issues      collected issues, capped by configuration
 */
public record ImportResult(
        String taskId,
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
