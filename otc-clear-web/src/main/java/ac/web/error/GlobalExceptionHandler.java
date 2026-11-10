package ac.web.error;

import ac.core.common.ApiException;
import ac.core.common.ErrorCode;
import ac.domain.layout.LayoutException;
import ac.domain.product.FieldFormatException;
import ac.domain.table.TableReadException;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/** Maps exceptions to RFC 7807 problem details with a stable {@code code} property. */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ProblemDetail> handleApi(ApiException exception) {
        ProblemDetail problem = ProblemDetail.forStatus(exception.code().status());
        problem.setTitle(exception.code().name());
        problem.setDetail(exception.getMessage());
        problem.setProperty("code", exception.code().name());
        if (!exception.details().isEmpty()) {
            problem.setProperty("details", exception.details());
        }
        return ResponseEntity.status(exception.code().status()).body(problem);
    }

    @ExceptionHandler({LayoutException.class, TableReadException.class})
    public ResponseEntity<ProblemDetail> handleDomain(RuntimeException exception) {
        return problem(ErrorCode.INVALID_REQUEST, exception.getMessage());
    }

    @ExceptionHandler(FieldFormatException.class)
    public ResponseEntity<ProblemDetail> handleFormat(FieldFormatException exception) {
        return problem(ErrorCode.INVALID_REQUEST, exception.getMessage());
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ProblemDetail> handleUploadSize(MaxUploadSizeExceededException exception) {
        return problem(ErrorCode.LIMIT_EXCEEDED, "Uploaded file exceeds the configured limit");
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, MissingServletRequestParameterException.class,
            IllegalArgumentException.class})
    public ResponseEntity<ProblemDetail> handleBadRequest(Exception exception) {
        return problem(ErrorCode.INVALID_REQUEST, exception.getMessage());
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ProblemDetail> handleMissingResource(NoResourceFoundException exception) {
        return problem(ErrorCode.NOT_FOUND, "Resource not found: " + exception.getResourcePath());
    }

    /** Keeps the status of {@code ResponseStatusException} (used to hide switched-off pages). */
    @ExceptionHandler(ErrorResponseException.class)
    public ResponseEntity<ProblemDetail> handleStatus(ErrorResponseException exception) {
        HttpStatus status = HttpStatus.resolve(exception.getStatusCode().value());
        ErrorCode code = status == HttpStatus.NOT_FOUND ? ErrorCode.NOT_FOUND : ErrorCode.INTERNAL_ERROR;
        ProblemDetail problem = ProblemDetail.forStatus(exception.getStatusCode());
        problem.setTitle(code.name());
        problem.setDetail(exception.getBody().getDetail() == null ? code.name() : exception.getBody().getDetail());
        problem.setProperty("code", code.name());
        problem.setProperty("details", Map.of());
        return ResponseEntity.status(exception.getStatusCode()).body(problem);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> handleUnexpected(Exception exception) {
        log.error("Unhandled error", exception);
        return problem(ErrorCode.INTERNAL_ERROR, "Internal error, see server logs");
    }

    private ResponseEntity<ProblemDetail> problem(ErrorCode code, String message) {
        ProblemDetail problem = ProblemDetail.forStatus(code.status());
        problem.setTitle(code.name());
        problem.setDetail(message == null ? code.name() : message);
        problem.setProperty("code", code.name());
        problem.setProperty("details", Map.of());
        return ResponseEntity.status(code.status()).body(problem);
    }
}
