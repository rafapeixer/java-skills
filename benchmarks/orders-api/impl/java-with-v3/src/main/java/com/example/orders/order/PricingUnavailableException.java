package com.example.orders.order;

/** The pricing service failed, was too slow, or more lookups are in flight than allowed. */
public class PricingUnavailableException extends RuntimeException {

    PricingUnavailableException(String message) {
        super(message);
    }

    PricingUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
