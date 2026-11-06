package ac.domain.product;

/**
 * Role of a trade field.
 *
 * <p>{@link #KEY} marks the single product field used as the human readable unique key
 * (for example a trade id). It is still a trade column, so it participates in the trade block.
 */
public enum FieldRole {
    TRADE,
    KEY
}
