package ac.domain.validation;

/**
 * Switches of one validation run.
 *
 * <p>The validation screens let the user re-run with different switches, so every entry point has to
 * state what it wants instead of relying on a hidden default.
 *
 * @param ignoreCase match values case-insensitively
 * @param skipBlank  treat a blank value as "not checked" instead of letting the rules reject it
 */
public record ValidationOptions(boolean ignoreCase, boolean skipBlank) {

    /**
     * Default behaviour: case-sensitive and blank values skipped, because completeness is the job of
     * the required flag and a rule should never turn an empty cell into a failure.
     */
    public static final ValidationOptions DEFAULT = new ValidationOptions(false, true);

    /** Import preview and import commit use the same switches as {@link #DEFAULT}. */
    public static final ValidationOptions SKIP_BLANK = new ValidationOptions(false, true);

    /** Options with the case sensitivity replaced. */
    public ValidationOptions withIgnoreCase(boolean value) {
        return new ValidationOptions(value, skipBlank);
    }

    /** Options with the blank handling replaced. */
    public ValidationOptions withSkipBlank(boolean value) {
        return new ValidationOptions(ignoreCase, value);
    }
}
