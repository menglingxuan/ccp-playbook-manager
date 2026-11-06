package ac.core.common;

import java.util.List;

/**
 * Page result.
 *
 * @param items page items
 * @param page 1-based page number
 * @param size page size
 * @param total total number of matching rows
 */
public record PageResult<T>(List<T> items, int page, int size, long total) {

    public PageResult {
        items = List.copyOf(items);
    }

    public int totalPages() {
        if (size <= 0) {
            return 0;
        }
        return (int) Math.ceil((double) total / size);
    }

    public static <T> PageResult<T> empty(PageQuery query) {
        return new PageResult<>(List.of(), query.page(), query.size(), 0);
    }
}
