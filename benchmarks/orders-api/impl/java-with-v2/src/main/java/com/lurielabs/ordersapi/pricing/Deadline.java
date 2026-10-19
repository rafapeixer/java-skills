package com.lurielabs.ordersapi.pricing;

import java.time.Duration;

/** A point in time on the monotonic clock, created once per request and passed down. */
public record Deadline(long expiresAtNanos) {

    private static final Duration MIN_USEFUL = Duration.ofMillis(10);

    public static Deadline after(Duration budget) {
        return new Deadline(System.nanoTime() + budget.toNanos());
    }

    long remainingNanos() {
        return expiresAtNanos - System.nanoTime();
    }

    /** min(cap, time left); throws before the call is sent when nothing useful is left. */
    Duration timeoutFor(String operation, Duration cap) {
        long left = remainingNanos();
        if (left < MIN_USEFUL.toNanos()) {
            throw new PricingUnavailableException("deadline exceeded before " + operation);
        }
        return Duration.ofNanos(Math.min(left, cap.toNanos()));
    }
}
