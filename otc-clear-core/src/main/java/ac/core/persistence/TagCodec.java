package ac.core.persistence;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Converts the comma separated tag column into a list and back. */
public final class TagCodec {

    private TagCodec() {
    }

    public static List<String> decode(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        List<String> tags = new ArrayList<>();
        for (String part : value.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty() && !tags.contains(trimmed)) {
                tags.add(trimmed);
            }
        }
        return tags;
    }

    public static String encode(List<String> tags) {
        if (tags == null || tags.isEmpty()) {
            return null;
        }
        return String.join(",", tags.stream().distinct().toList());
    }

    public static List<String> splitAliases(String value) {
        return decode(value);
    }

    public static String encodeAliases(List<String> aliases) {
        return encode(aliases);
    }

    /** Normalizes a list, dropping blanks and duplicates while keeping order. */
    public static List<String> normalize(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        return Arrays.stream(values.toArray(new String[0]))
                .map(value -> value == null ? "" : value.trim())
                .filter(value -> !value.isEmpty())
                .distinct()
                .toList();
    }
}
