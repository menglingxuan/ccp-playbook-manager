package ac.domain.product;

/** Raised when a raw cell text cannot be converted to the declared field type. */
public class FieldFormatException extends RuntimeException {

    private final String fieldKey;
    private final String rawValue;

    public FieldFormatException(String fieldKey, String rawValue, String message) {
        super(message);
        this.fieldKey = fieldKey;
        this.rawValue = rawValue;
    }

    public String fieldKey() {
        return fieldKey;
    }

    public String rawValue() {
        return rawValue;
    }
}
