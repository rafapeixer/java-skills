package com.lurielabs.ordersapi.api;

/** A concurrency limit refused the work; answered with 503 and Retry-After. */
public class OverloadedException extends RuntimeException {

    public OverloadedException(String limit) {
        super("over capacity: " + limit);
    }
}
