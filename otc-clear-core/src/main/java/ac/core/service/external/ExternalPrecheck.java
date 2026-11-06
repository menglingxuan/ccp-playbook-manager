package ac.core.service.external;

import ac.domain.casefile.ValidationIssue;
import java.util.List;

/**
 * Result of the dispatch precheck.
 *
 * @param valid            true when the job can be created
 * @param serviceEnabled   whether the external tool is configured
 * @param rowCount         number of selected rows
 * @param issues           findings
 */
public record ExternalPrecheck(boolean valid, boolean serviceEnabled, int rowCount,
        List<ValidationIssue> issues) {

    public ExternalPrecheck {
        issues = List.copyOf(issues);
    }
}
