package ac.domain.validation;

import java.util.Locale;

/**
 * How a failed rule is reported.
 *
 * <p>{@link #WARN} only informs the analyst, {@link #ERROR} blocks the row the same way a value
 * type mismatch does. A field expression that fails several rules takes the strongest severity.
 */
public enum ValidationSeverity {

    WARN,
    ERROR;

    /** Parses a stored severity, defaulting to {@link #WARN} for blank or unknown values. */
    public static ValidationSeverity of(String value) {
        if (value == null || value.isBlank()) {
            return WARN;
        }
        return "ERROR".equals(value.trim().toUpperCase(Locale.ROOT)) ? ERROR : WARN;
    }
}
