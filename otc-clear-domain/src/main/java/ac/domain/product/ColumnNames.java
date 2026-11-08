package ac.domain.product;

import java.util.Locale;

/**
 * Column identity used by import matching.
 *
 * <p>Two headers identify the same column when they are equal after trimming surrounding
 * whitespace and ignoring case. This is the only tolerated difference: inner whitespace,
 * punctuation, full width characters and aliases never make two headers equivalent, so a column
 * either equals a product field name exactly or it does not match at all.
 */
public final class ColumnNames {

    private ColumnNames() {
    }

    /** Comparison key of a header, empty for {@code null} or blank input. */
    public static String key(String header) {
        return header == null ? "" : header.strip().toLowerCase(Locale.ROOT);
    }

    public static boolean same(String left, String right) {
        return key(left).equals(key(right));
    }
}
