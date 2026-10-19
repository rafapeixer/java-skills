package com.lurielabs.ordersapi.api;

/** The request is malformed or breaks a validation rule; the message is safe to show the client. */
public class InvalidRequestException extends RuntimeException {

    public InvalidRequestException(String message) {
        super(message);
    }
}
