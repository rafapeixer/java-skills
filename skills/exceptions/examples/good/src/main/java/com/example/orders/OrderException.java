package com.example.orders;

/**
 * Base of the domain failures. Unchecked: no caller between the controller and the
 * service can do anything about them, so they travel to the one handler that maps them.
 * The domain knows nothing about HTTP; ApiExceptionHandler owns the status codes.
 */
public abstract class OrderException extends RuntimeException {
    protected OrderException(String message) {
        super(message);
    }

    protected OrderException(String message, Throwable cause) {
        super(message, cause);
    }
}
