package ac.domain.validation;

/** Raised when a rule pattern or a rule expression cannot be compiled. */
public class RuleSyntaxException extends RuntimeException {

    public RuleSyntaxException(String message) {
        super(message);
    }

    public RuleSyntaxException(String message, Throwable cause) {
        super(message, cause);
    }
}
