package ac.core.service.clone;

import java.util.List;

/**
 * Clone request.
 *
 * @param caseIds  source cases, all from the same product
 * @param copies   number of copies per source case
 * @param template naming template applied to the unique field and the test case id
 * @param note     note stored on the created batch
 */
public record CloneRequest(List<String> caseIds, int copies, String template, String note) {

    public CloneRequest {
        caseIds = caseIds == null ? List.of() : List.copyOf(caseIds);
    }
}
