package com.lurielabs.ordersapi.pricing;

/** The pricing service answered 404 for a SKU. */
public class UnknownSkuException extends RuntimeException {

    public UnknownSkuException(String sku) {
        super("unknown sku: " + sku);
    }
}
