package com.example.ordersapi.orders;

/** The client sent input that breaks the API contract; the message is safe to return. */
public class InvalidRequestException extends RuntimeException {

    public InvalidRequestException(String message) {
        super(message);
    }
}
