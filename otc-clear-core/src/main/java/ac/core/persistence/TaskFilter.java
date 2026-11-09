package ac.core.persistence;

import ac.core.common.PageQuery;
import java.time.LocalDate;

/**
 * Import task search filter.
 *
 * @param kind    {@code FILE_UPLOAD} or {@code EXTERNAL_OUTPUT}, {@code null} for all
 * @param status  lifecycle status, {@code null} for all
 * @param product base product code matched against the batches of the task, {@code null} for all
 * @param query   free text matched against file name, task id, task name and the base products of
 *                its batches
 * @param sort    {@code created} (default, newest first), {@code name}, {@code batches},
 *                {@code rows}, {@code invalid} or {@code duration}, each optionally suffixed with
 *                {@code _asc} / {@code _desc}
 * @param from    earliest creation date (inclusive), {@code null} for no lower bound
 * @param to      latest creation date (inclusive), {@code null} for no upper bound
 * @param page    page request
 */
public record TaskFilter(String kind, String status, String product, String query, String sort,
        LocalDate from, LocalDate to, PageQuery page) {

    public TaskFilter {
        sort = sort == null || sort.isBlank() ? "created" : sort;
    }

    /** Filter without a creation date range, keeping the older call sites unchanged. */
    public TaskFilter(String kind, String status, String product, String query, String sort,
            PageQuery page) {
        this(kind, status, product, query, sort, null, null, page);
    }
}
