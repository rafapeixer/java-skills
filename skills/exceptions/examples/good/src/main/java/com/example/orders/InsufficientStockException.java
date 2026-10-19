package com.example.orders;

public final class InsufficientStockException extends OrderException {
    public InsufficientStockException(String sku, int requested, int available) {
        super("sku " + sku + ": requested " + requested + ", available " + available);
    }
}
