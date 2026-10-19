package com.example.orders;

/** The pricing service does not know a SKU (maps to 422). */
public class UnknownSkuException extends RuntimeException {

    public UnknownSkuException(String sku) {
        super("unknown sku: " + sku, null, false, false);
    }
}
