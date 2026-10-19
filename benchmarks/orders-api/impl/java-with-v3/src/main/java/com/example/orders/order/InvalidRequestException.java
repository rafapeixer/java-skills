package com.example.orders.order;

public class InvalidRequestException extends RuntimeException {

    InvalidRequestException(String message) {
        super(message);
    }
}
