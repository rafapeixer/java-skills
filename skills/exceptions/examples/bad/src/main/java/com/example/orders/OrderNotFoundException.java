package com.example.orders;

// MISTAKE: checked, though no caller between the service and the controller can recover;
// every layer must now declare or catch it, and most end up wrapping it in RuntimeException.
public class OrderNotFoundException extends Exception {
    public OrderNotFoundException(String message) {
        super(message);
    }
}
