package ac.core.service.export;

import ac.core.service.cases.FieldOverride;
import java.util.List;

/**
 * Export request.
 *
 * @param batchId             batch scope; mutually exclusive with {@code caseIds}
 * @param caseIds             selection scope
 * @param options             CSV options; {@code null} or a partial object means the tenant defaults
 *                            are used for every field left out
 * @param recalculateFormulas recalculate the workbook formulas before writing; {@code null} keeps
 *                            the default, which is enabled
 * @param overrides           transient field overrides applied while rendering, never persisted
 */
public record ExportRequest(String batchId, List<String> caseIds, CsvOptionsPatch options,
        Boolean recalculateFormulas, List<FieldOverride> overrides) {

    public ExportRequest {
        caseIds = caseIds == null ? List.of() : List.copyOf(caseIds);
        overrides = overrides == null ? List.of() : List.copyOf(overrides);
    }

    public ExportRequest(String batchId, List<String> caseIds, CsvOptionsPatch options,
            Boolean recalculateFormulas) {
        this(batchId, caseIds, options, recalculateFormulas, List.of());
    }

    public ExportRequest(String batchId, List<String> caseIds, CsvOptionsPatch options) {
        this(batchId, caseIds, options, null, List.of());
    }

    /** Recalculation is on unless the caller switched it off explicitly. */
    public boolean recalculateEnabled() {
        return recalculateFormulas == null || recalculateFormulas;
    }

    /** True when at least one field is overridden for this run only. */
    public boolean hasOverrides() {
        return !overrides.isEmpty();
    }

    /** Same request without the transient overrides, used by the stored-value precheck. */
    public ExportRequest withoutOverrides() {
        return new ExportRequest(batchId, caseIds, options, recalculateFormulas);
    }
}
