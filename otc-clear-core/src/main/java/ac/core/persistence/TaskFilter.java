package ac.core.persistence;

import ac.core.common.PageQuery;

/**
 * Import task search filter.
 *
 * @param kind    {@code FILE_UPLOAD} or {@code EXTERNAL_OUTPUT}, {@code null} for all
 * @param status  lifecycle status, {@code null} for all
 * @param product product code, {@code null} for all
 * @param query   free text matched against file name, id and product
 * @param sort    {@code created}, {@code rows}, {@code invalid} or {@code duration}
 * @param page    page request
 */
public record TaskFilter(String kind, String status, String product, String query, String sort,
        PageQuery page) {

    public TaskFilter {
        sort = sort == null || sort.isBlank() ? "created" : sort;
    }
}
