package in.stockline.api.exception;

import in.stockline.observability.CorrelationContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import in.stockline.domain.exception.ApiProblem;

@RestControllerAdvice
class ApiErrors {
    private static final Logger log = LoggerFactory.getLogger(ApiErrors.class);
    record ErrorBody(String code, String message, String correlationId) {}

    @ExceptionHandler(ApiProblem.class)
    ResponseEntity<ErrorBody> problem(ApiProblem error) {
        return ResponseEntity.status(error.status).body(body(error.code, error.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ErrorBody> validation(MethodArgumentNotValidException error) {
        var first = error.getBindingResult().getFieldErrors().stream().findFirst();
        return invalid(first.map(field -> field.getField() + " " + field.getDefaultMessage())
            .orElse("Check the submitted values."));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ErrorBody> malformed() {
        return invalid("Check the JSON body and use whole numbers for quantities and priorities.");
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<ErrorBody> wrongType(MethodArgumentTypeMismatchException error) {
        return invalid("Invalid value for " + error.getName() + ".");
    }

    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<ErrorBody> forbidden() {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(body("FORBIDDEN", "You do not have access to this resource."));
    }

    @ExceptionHandler(AuthenticationException.class)
    ResponseEntity<ErrorBody> unauthenticated() {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(body("UNAUTHENTICATED", "Authentication failed."));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ErrorBody> internal(Exception error) {
        log.error("Unhandled request failure ({})", error.getClass().getSimpleName());
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(body("INTERNAL_ERROR", "The request could not be completed."));
    }

    private ResponseEntity<ErrorBody> invalid(String message) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body("INVALID_INPUT", message));
    }

    private ErrorBody body(String code, String message) {
        var id = CorrelationContext.current();
        return new ErrorBody(code, message, id == null ? null : id.toString());
    }
}
