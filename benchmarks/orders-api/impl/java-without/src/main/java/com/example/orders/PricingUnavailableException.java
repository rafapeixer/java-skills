package com.example.orders;

/** The pricing service failed, was too slow, or we are shedding load to it (maps to 503). */
public class PricingUnavailableException extends RuntimeException {

    private final boolean retryable;

    public PricingUnavailableException(String message) {
        this(message, false);
    }

    public PricingUnavailableException(String message, boolean retryable) {
        super(message, null, false, false);
        this.retryable = retryable;
    }

    public boolean retryable() {
        return retryable;
    }
}
