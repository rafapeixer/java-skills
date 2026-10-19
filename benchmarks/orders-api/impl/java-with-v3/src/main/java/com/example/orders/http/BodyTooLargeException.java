package com.example.orders.http;

class BodyTooLargeException extends RuntimeException {

    BodyTooLargeException(int limit) {
        super("request body larger than " + limit + " bytes");
    }
}
