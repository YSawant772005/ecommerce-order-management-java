package com.ecommerce.ordermanagement.web;

import org.springframework.http.HttpStatus;

/** A domain error carrying the HTTP status the former FastAPI handlers raised. */
public class ApiException extends RuntimeException {

    private final HttpStatus status;

    public ApiException(HttpStatus status, String detail) {
        super(detail);
        this.status = status;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public static ApiException notFound(String detail) {
        return new ApiException(HttpStatus.NOT_FOUND, detail);
    }

    public static ApiException conflict(String detail) {
        return new ApiException(HttpStatus.CONFLICT, detail);
    }

    /** Validation that is not expressible as a field constraint (e.g. catalog shape). */
    public static ApiException unprocessable(String detail) {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, detail);
    }
}
