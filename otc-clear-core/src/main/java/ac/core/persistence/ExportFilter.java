package ac.core.persistence;

import ac.core.common.PageQuery;
import java.time.LocalDate;

/**
 * Export history filter used by the export management panel.
 *
 * @param query   free text matched against file name, product, batch id and export id
 * @param product product code filter
 * @param batchId restrict to one batch
 * @param from    earliest creation date (inclusive, display zone)
 * @param to      latest creation date (inclusive, display zone)
 * @param sort    {@code created|rows|size}, optionally {@code _asc}/{@code _desc} (default newest first)
 * @param page    page request
 */
public record ExportFilter(String query, String product, String batchId, LocalDate from, LocalDate to,
        String sort, PageQuery page) {

    public ExportFilter {
        sort = sort == null || sort.isBlank() ? "created" : sort.trim();
    }

    /** Filter with the default order, used by simple call sites. */
    public ExportFilter(PageQuery page) {
        this(null, null, null, null, null, "created", page);
    }
}
