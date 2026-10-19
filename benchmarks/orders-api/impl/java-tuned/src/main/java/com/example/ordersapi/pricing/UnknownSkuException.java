package com.example.ordersapi.pricing;

/** The pricing service answered 404 for a SKU of the order. */
public class UnknownSkuException extends RuntimeException {

    public UnknownSkuException(String sku) {
        super("pricing service does not know sku " + sku);
    }
}
