package com.example.ordersapi.pricing;

import org.jspecify.annotations.Nullable;

/** The pricing service failed, was too slow, answered garbage, or is at its concurrency limit. */
public class PricingUnavailableException extends RuntimeException {

    public PricingUnavailableException(String message) {
        super(message);
    }

    public PricingUnavailableException(String message, @Nullable Throwable cause) {
        super(message, cause);
    }
}
