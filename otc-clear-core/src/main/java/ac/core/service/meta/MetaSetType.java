package ac.core.service.meta;

import java.util.Locale;

/**
 * Kind of a meta set.
 *
 * <p>{@link #VALUE} sets hold plain values (an item's content is the value text), {@link #FORMULA}
 * sets hold formula templates used to fill a field in bulk, {@link #TAG} sets hold predefined tags
 * offered when a batch is edited and {@link #VALIDATION} sets hold field value validation rules.
 */
public enum MetaSetType {
    VALUE,
    FORMULA,
    TAG,
    VALIDATION;

    /** Parses a stored or requested type, defaulting to {@link #VALUE}. */
    public static MetaSetType of(String raw) {
        if (raw == null || raw.isBlank()) {
            return VALUE;
        }
        try {
            return valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unknown meta set type '" + raw + "'");
        }
    }
}
