package ac.core.persistence;

import ac.core.common.PageQuery;

/**
 * Import batch search filter.
 *
 * @param product         product code filter
 * @param query           free text matched against source file, sheet name and note
 * @param kind            batch kind filter ({@code IMPORT}, {@code CLONE}, {@code EXTERNAL_OUTPUT})
 * @param includeExternal whether external output batches are included
 * @param page            page request
 */
public record BatchFilter(String product, String query, String kind, boolean includeExternal,
        PageQuery page) {

    /** Filter without a kind constraint, keeping the older call sites unchanged. */
    public BatchFilter(String product, String query, boolean includeExternal, PageQuery page) {
        this(product, query, null, includeExternal, page);
    }
}
