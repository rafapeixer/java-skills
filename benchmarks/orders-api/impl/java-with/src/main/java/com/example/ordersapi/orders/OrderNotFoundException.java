package com.example.ordersapi.orders;

public class OrderNotFoundException extends RuntimeException {

    public OrderNotFoundException(long orderId) {
        super("order " + orderId + " not found");
    }
}
