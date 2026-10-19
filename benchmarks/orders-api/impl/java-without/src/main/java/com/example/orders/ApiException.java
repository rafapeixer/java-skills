package com.example.orders;

import org.springframework.http.HttpStatus;

/** An expected client-facing error, rendered as {@code {"error": message}} with the given status. */
public class ApiException extends RuntimeException {

    private final HttpStatus status;

    public ApiException(HttpStatus status, String message) {
        super(message, null, false, false);
        this.status = status;
    }

    public static ApiException badRequest(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, message);
    }

    public static ApiException notFound(String message) {
        return new ApiException(HttpStatus.NOT_FOUND, message);
    }

    public HttpStatus status() {
        return status;
    }
}
