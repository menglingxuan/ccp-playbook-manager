package ac.domain.layout;

/** Raised when a sheet cannot be laid out with the requested parameters. */
public class LayoutException extends RuntimeException {

    private final String code;

    public LayoutException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
