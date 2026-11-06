package ac.core.service.export;

import ac.domain.casefile.ValidationIssue;
import java.util.List;

/**
 * Export precheck result.
 *
 * @param valid      true when the export can run
 * @param rowCount   number of rows that will be written
 * @param headers    column headers in output order
 * @param sampleRow  first data row for the live preview
 * @param issues     blocking and non blocking findings
 */
public record ExportPrecheck(
        boolean valid,
        int rowCount,
        List<String> headers,
        List<String> sampleRow,
        List<ValidationIssue> issues) {

    public ExportPrecheck {
        headers = List.copyOf(headers);
        sampleRow = List.copyOf(sampleRow);
        issues = List.copyOf(issues);
    }
}
