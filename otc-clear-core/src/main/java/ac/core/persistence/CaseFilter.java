package ac.core.persistence;

import ac.core.common.PageQuery;

/**
 * Test case search filter.
 *
 * @param batchId    batch scope, {@code null} for all batches
 * @param query      free text matched against the stored search text
 * @param hasFormula only cases with captured formulas when true
 * @param sort       {@code row|testcase|key|formulas|updated}, optionally {@code _asc}/{@code _desc}
 * @param page       page request
 */
public record CaseFilter(String batchId, String query, Boolean hasFormula, String sort, PageQuery page) {

    public CaseFilter {
        sort = sort == null || sort.isBlank() ? "row" : sort.trim();
    }

    /** Filter on the natural row order, keeping the older call sites unchanged. */
    public CaseFilter(String batchId, String query, Boolean hasFormula, PageQuery page) {
        this(batchId, query, hasFormula, "row", page);
    }

    /** Same filter restricted to one batch. */
    public CaseFilter forBatch(String newBatchId) {
        return new CaseFilter(newBatchId, query, hasFormula, sort, page);
    }
}
