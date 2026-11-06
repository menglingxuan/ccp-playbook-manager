package ac.domain.layout;

/** Classification of a single source column. */
public enum ColumnKind {
    /** Column mapped to a product trade field. */
    TRADE,
    /** Column outside the trade block, kept as a managed value. */
    MANAGED,
    /** Column inside the trade block that matched no field; stored as extra data. */
    UNMAPPED
}
