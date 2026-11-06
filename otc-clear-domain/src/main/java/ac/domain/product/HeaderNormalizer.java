package ac.domain.product;

import java.util.Locale;
import java.util.Set;

/**
 * Normalizes header text before comparison: removes blanks and punctuation, converts full width
 * characters to half width and lower cases the result.
 */
public final class HeaderNormalizer {

    private static final Set<Character> IGNORED =
            Set.of('_', '-', '.', '(', ')', '/', ':', ',', '\'', '"');

    private HeaderNormalizer() {
    }

    public static String normalize(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(value.length());
        for (char c : value.toCharArray()) {
            char half = toHalfWidth(c);
            if (Character.isWhitespace(half) || IGNORED.contains(half)) {
                continue;
            }
            sb.append(half);
        }
        return sb.toString().toLowerCase(Locale.ROOT);
    }

    private static char toHalfWidth(char c) {
        if (c == '\u3000') {
            return ' ';
        }
        if (c >= '\uFF01' && c <= '\uFF5E') {
            return (char) (c - 0xFEE0);
        }
        return c;
    }
}
