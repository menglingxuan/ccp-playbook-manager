package ac.domain.analysis;

import ac.domain.casefile.ValidationIssue;
import ac.domain.layout.SheetLayout;
import java.util.List;

/**
 * Wizard preview payload.
 *
 * @param productCode resolved product code
 * @param valid       true when no blocking issue was found
 * @param issues      layout level issues
 * @param layout      resolved layout, {@code null} when resolution failed
 * @param sampleRows  converted sample rows
 * @param stats       preview statistics
 */
public record SheetPreview(
        String productCode,
        boolean valid,
        List<ValidationIssue> issues,
        SheetLayout layout,
        List<SampleRow> sampleRows,
        PreviewStats stats) {

    public SheetPreview {
        issues = List.copyOf(issues);
        sampleRows = List.copyOf(sampleRows);
    }
}
