package ac.domain.validation;

import java.util.Locale;

/** How the expression of a validation rule is interpreted. */
public enum ValidationRuleType {
    /** Java regular expression, matched against the whole value. */
    REGEX,
    /** Wildcard pattern: {@code *} for any run of characters, {@code ?} for exactly one. */
    WILDCARD;

    /** Parses a stored or requested type, defaulting to {@link #WILDCARD}. */
    public static ValidationRuleType of(String raw) {
        if (raw == null || raw.isBlank()) {
            return WILDCARD;
        }
        try {
            return valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new RuleSyntaxException("Unknown rule type '" + raw + "', use REGEX or WILDCARD");
        }
    }
}
