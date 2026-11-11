package in.stockline;

import org.springframework.http.HttpStatus;

final class ApiProblem extends RuntimeException {
    final HttpStatus status;
    final String code;

    ApiProblem(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    static ApiProblem notFound(String message) {
        return new ApiProblem(HttpStatus.NOT_FOUND, "NOT_FOUND", message);
    }

    static ApiProblem conflict(String code, String message) {
        return new ApiProblem(HttpStatus.CONFLICT, code, message);
    }

    static ApiProblem invalid(String message) {
        return new ApiProblem(HttpStatus.BAD_REQUEST, "INVALID_INPUT", message);
    }

    static ApiProblem busy(String message) {
        return new ApiProblem(HttpStatus.SERVICE_UNAVAILABLE, "BUSY", message);
    }

    static ApiProblem databaseUnavailable(String message) {
        return new ApiProblem(HttpStatus.SERVICE_UNAVAILABLE, "DATABASE_UNAVAILABLE", message);
    }
}
