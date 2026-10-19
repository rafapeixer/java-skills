package com.example.orders;

public final class OrderNotFoundException extends OrderException {
    public OrderNotFoundException(String orderId) {
        super("order " + orderId + " not found");
    }
}
