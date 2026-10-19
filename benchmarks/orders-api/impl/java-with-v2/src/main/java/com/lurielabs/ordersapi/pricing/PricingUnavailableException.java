package com.lurielabs.ordersapi.pricing;

/** The pricing service failed, answered with an unexpected status, or missed the deadline. */
public class PricingUnavailableException extends RuntimeException {

    public PricingUnavailableException(String message) {
        super(message);
    }

    public PricingUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
