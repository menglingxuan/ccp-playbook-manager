package ac.core.common;

/**
 * Simple page request.
 *
 * @param page 1-based page number
 * @param size page size, clamped to 1..200
 */
public record PageQuery(int page, int size) {

    public static final int DEFAULT_SIZE = 50;
    public static final int MAX_SIZE = 200;

    public static PageQuery of(Integer page, Integer size) {
        int resolvedPage = page == null || page < 1 ? 1 : page;
        int resolvedSize = size == null || size < 1 ? DEFAULT_SIZE : Math.min(size, MAX_SIZE);
        return new PageQuery(resolvedPage, resolvedSize);
    }

    public int offset() {
        return (page - 1) * size;
    }
}
