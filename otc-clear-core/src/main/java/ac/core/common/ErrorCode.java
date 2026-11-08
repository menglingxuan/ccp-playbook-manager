package ac.core.common;

import org.springframework.http.HttpStatus;

/** Stable error codes mapped to HTTP status codes and problem detail {@code code} values. */
public enum ErrorCode {
    INVALID_REQUEST(HttpStatus.BAD_REQUEST),
    NOT_FOUND(HttpStatus.NOT_FOUND),
    CONFLICT(HttpStatus.CONFLICT),
    DUPLICATE_IMPORT(HttpStatus.CONFLICT),
    INVALID_STATE(HttpStatus.CONFLICT),
    PRODUCT_DEFINITION_INVALID(HttpStatus.CONFLICT),
    LIMIT_EXCEEDED(HttpStatus.PAYLOAD_TOO_LARGE),
    UNSUPPORTED_MEDIA_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE),
    EXTERNAL_UNAVAILABLE(HttpStatus.BAD_GATEWAY),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR);

    private final HttpStatus status;

    ErrorCode(HttpStatus status) {
        this.status = status;
    }

    public HttpStatus status() {
        return status;
    }
}
