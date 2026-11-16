package ac.domain.product;

/** Supported logical field types. Type conversion happens in {@link FieldCodec}. */
public enum FieldType {
    TEXT,
    DECIMAL,
    INTEGER,
    DATE,
    TIME,
    DATETIME,
    BOOLEAN,
    ENUM;

    /**
     * True when the type carries a normalized copy next to the original text.
     *
     * <p>Normalized values serve sorting, searching and filtering only; every screen shows the
     * original cell text. Text and enumerated values need no copy, which keeps the rows small.
     */
    public boolean normalized() {
        return this != TEXT && this != ENUM;
    }
}
