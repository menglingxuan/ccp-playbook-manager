package ac.core.persistence;

import ac.core.common.PageQuery;
import java.time.LocalDate;

/**
 * Import batch search filter used by the batch console.
 *
 * @param product         product code filter
 * @param query           free text matched against file name, sheet name, note, tags, product and id
 * @param kind            batch kind filter ({@code IMPORT}, {@code CLONE}, {@code EXTERNAL_OUTPUT})
 * @param status          explicit status filter ({@code ACTIVE}, {@code DELETED}); {@code null} hides
 *                        deleted batches unless {@code includeDeleted} is set
 * @param tag             one exact user tag
 * @param favorite        {@code true} only favorites, {@code false} only non favorites
 * @param pinned          {@code true} only pinned batches, {@code false} only unpinned ones
 * @param from            earliest creation date (inclusive, display zone)
 * @param to              latest creation date (inclusive, display zone)
 * @param sort            {@code created|name|rows|invalid|product}, optionally {@code _asc}/{@code _desc}
 * @param includeDeleted  whether soft deleted batches are listed as well
 * @param includeExternal whether external output batches are included
 * @param page            page request
 */
public record BatchFilter(String product, String query, String kind, String status, String tag,
        Boolean favorite, Boolean pinned, LocalDate from, LocalDate to, String sort,
        boolean includeDeleted, boolean includeExternal, PageQuery page) {

    public BatchFilter {
        sort = sort == null || sort.isBlank() ? "created" : sort.trim();
    }

    /** Filter without a kind constraint, keeping the older call sites unchanged. */
    public BatchFilter(String product, String query, boolean includeExternal, PageQuery page) {
        this(product, query, null, null, null, null, null, null, null, "created", false, includeExternal, page);
    }

    /** Filter with a kind but no console specific constraint. */
    public BatchFilter(String product, String query, String kind, boolean includeExternal, PageQuery page) {
        this(product, query, kind, null, null, null, null, null, null, "created", false, includeExternal, page);
    }

    public BatchFilter withFavorite(Boolean value) {
        return new BatchFilter(product, query, kind, status, tag, value, pinned, from, to, sort,
                includeDeleted, includeExternal, page);
    }

    public BatchFilter withPinned(Boolean value) {
        return new BatchFilter(product, query, kind, status, tag, favorite, value, from, to, sort,
                includeDeleted, includeExternal, page);
    }

    public BatchFilter withPage(PageQuery value) {
        return new BatchFilter(product, query, kind, status, tag, favorite, pinned, from, to, sort,
                includeDeleted, includeExternal, value);
    }
}
