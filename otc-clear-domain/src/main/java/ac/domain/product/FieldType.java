package ac.domain.product;

/** Supported logical field types. Type conversion happens in {@link FieldCodec}. */
public enum FieldType {
    TEXT,
    DECIMAL,
    INTEGER,
    DATE,
    BOOLEAN,
    ENUM
}
