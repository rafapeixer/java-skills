package com.example.orders.order;

public class UnknownSkuException extends RuntimeException {

    UnknownSkuException(String sku) {
        super("unknown sku: " + sku);
    }
}
