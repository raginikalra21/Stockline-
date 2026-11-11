package in.stockline;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
class ApiErrors {
    record ErrorBody(String code, String message) {}

    @ExceptionHandler(ApiProblem.class)
    ResponseEntity<ErrorBody> problem(ApiProblem error) {
        return ResponseEntity.status(error.status).body(new ErrorBody(error.code, error.getMessage()));
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

    private ResponseEntity<ErrorBody> invalid(String message) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorBody("INVALID_INPUT", message));
    }
}
