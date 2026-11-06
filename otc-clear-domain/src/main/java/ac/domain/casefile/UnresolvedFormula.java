package ac.domain.casefile;

/**
 * A formula cell whose text could not be captured, kept for traceability.
 *
 * @param key      field key or managed key
 * @param column   1-based column
 * @param reason   reason code, for example {@code SHARED_FORMULA_NO_TEXT}
 */
public record UnresolvedFormula(String key, int column, String reason) {
}
