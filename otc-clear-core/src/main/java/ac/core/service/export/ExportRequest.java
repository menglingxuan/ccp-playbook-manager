package ac.core.service.export;

import ac.domain.export.CsvFormatOptions;
import java.util.List;

/**
 * Export request.
 *
 * @param batchId             batch scope; mutually exclusive with {@code caseIds}
 * @param caseIds             selection scope
 * @param options             CSV options, {@code null} means use the tenant defaults
 * @param recalculateFormulas recalculate the workbook formulas before writing; {@code null} keeps
 *                            the default, which is enabled
 */
public record ExportRequest(String batchId, List<String> caseIds, CsvFormatOptions options,
        Boolean recalculateFormulas) {

    public ExportRequest {
        caseIds = caseIds == null ? List.of() : List.copyOf(caseIds);
    }

    public ExportRequest(String batchId, List<String> caseIds, CsvFormatOptions options) {
        this(batchId, caseIds, options, null);
    }

    /** Recalculation is on unless the caller switched it off explicitly. */
    public boolean recalculateEnabled() {
        return recalculateFormulas == null || recalculateFormulas;
    }
}
