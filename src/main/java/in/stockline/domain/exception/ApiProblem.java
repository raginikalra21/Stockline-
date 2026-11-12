package in.stockline.domain.exception;

import org.springframework.http.HttpStatus;

public final class ApiProblem extends RuntimeException {
    public final HttpStatus status;
    public final String code;

    public ApiProblem(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public static ApiProblem notFound(String message) {
        return new ApiProblem(HttpStatus.NOT_FOUND, "NOT_FOUND", message);
    }

    public static ApiProblem forbidden(String message) {
        return new ApiProblem(HttpStatus.FORBIDDEN, "FORBIDDEN", message);
    }

    public static ApiProblem conflict(String code, String message) {
        return new ApiProblem(HttpStatus.CONFLICT, code, message);
    }

    public static ApiProblem invalid(String message) {
        return new ApiProblem(HttpStatus.BAD_REQUEST, "INVALID_INPUT", message);
    }

    public static ApiProblem busy(String message) {
        return new ApiProblem(HttpStatus.SERVICE_UNAVAILABLE, "BUSY", message);
    }

    public static ApiProblem databaseUnavailable(String message) {
        return new ApiProblem(HttpStatus.SERVICE_UNAVAILABLE, "DATABASE_UNAVAILABLE", message);
    }
}
