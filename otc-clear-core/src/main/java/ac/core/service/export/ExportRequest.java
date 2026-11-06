package ac.core.service.export;

import ac.domain.export.CsvFormatOptions;
import java.util.List;

/**
 * Export request.
 *
 * @param batchId batch scope; mutually exclusive with {@code caseIds}
 * @param caseIds selection scope
 * @param options CSV options, {@code null} means use the tenant defaults
 */
public record ExportRequest(String batchId, List<String> caseIds, CsvFormatOptions options) {

    public ExportRequest {
        caseIds = caseIds == null ? List.of() : List.copyOf(caseIds);
    }
}
