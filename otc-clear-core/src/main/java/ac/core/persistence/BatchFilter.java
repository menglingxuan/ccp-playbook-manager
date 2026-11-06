package ac.core.persistence;

import ac.core.common.PageQuery;

/**
 * Import batch search filter.
 *
 * @param product         product code filter
 * @param query           free text matched against source file and sheet name
 * @param includeExternal whether external output batches are included
 * @param page            page request
 */
public record BatchFilter(String product, String query, boolean includeExternal, PageQuery page) {
}
