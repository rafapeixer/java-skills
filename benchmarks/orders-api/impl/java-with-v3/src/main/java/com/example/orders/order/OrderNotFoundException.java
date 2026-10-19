package com.example.orders.order;

public class OrderNotFoundException extends RuntimeException {

    OrderNotFoundException(long orderId) {
        super("order " + orderId + " not found");
    }
}
