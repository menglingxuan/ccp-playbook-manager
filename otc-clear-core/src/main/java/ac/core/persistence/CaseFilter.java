package ac.core.persistence;

import ac.core.common.PageQuery;

/**
 * Test case search filter.
 *
 * @param batchId     batch scope, {@code null} for all batches
 * @param query       free text matched against the stored search text
 * @param hasFormula  only cases with captured formulas when true
 * @param page        page request
 */
public record CaseFilter(String batchId, String query, Boolean hasFormula, PageQuery page) {
}
