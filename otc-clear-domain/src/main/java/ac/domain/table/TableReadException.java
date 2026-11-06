package ac.domain.table;

/** Raised when a source file cannot be read or violates the configured limits. */
public class TableReadException extends RuntimeException {

    public TableReadException(String message) {
        super(message);
    }

    public TableReadException(String message, Throwable cause) {
        super(message, cause);
    }
}
