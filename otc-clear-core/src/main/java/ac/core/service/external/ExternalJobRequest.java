package ac.core.service.external;

import ac.domain.casefile.ValidationIssue;
import ac.domain.export.CsvFormatOptions;
import java.util.List;

/**
 * Dispatch request.
 *
 * @param caseIds selected cases, all from the same product
 * @param options CSV options used for the generated input file
 */
public record ExternalJobRequest(List<String> caseIds, CsvFormatOptions options) {

    public ExternalJobRequest {
        caseIds = caseIds == null ? List.of() : List.copyOf(caseIds);
    }
}
