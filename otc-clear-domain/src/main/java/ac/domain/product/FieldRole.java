package ac.domain.product;

/**
 * Role of a product field.
 *
 * <p>{@link #BUSINESS} marks a business column: it participates in the trade block, contributes to
 * the business record id and is always required in a derived product.
 *
 * <p>{@link #MANAGED} marks a managed column declared by the product (for example the test case id
 * or a review note). Managed fields sit outside the trade block, are never aligned against trade
 * headers and may be either optional or required.
 */
public enum FieldRole {
    BUSINESS,
    MANAGED
}
