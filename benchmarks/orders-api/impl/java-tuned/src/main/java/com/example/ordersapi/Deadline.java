package com.example.ordersapi;

import java.time.Duration;

/** A point in time, set once at the request edge, after which work for the request is useless. */
public final class Deadline {

    /** Below this, no call can succeed; fail before sending it. */
    private static final Duration MIN_USEFUL = Duration.ofMillis(10);

    private final long deadlineNanos;

    private Deadline(long deadlineNanos) {
        this.deadlineNanos = deadlineNanos;
    }

    public static Deadline after(Duration budget) {
        return new Deadline(System.nanoTime() + budget.toNanos());
    }

    public Duration remaining() {
        return Duration.ofNanos(deadlineNanos - System.nanoTime());
    }

    /** The smaller of {@code cap} and the time left; throws when the budget is spent. */
    public Duration timeoutFor(String operation, Duration cap) {
        Duration left = remaining();
        if (left.compareTo(MIN_USEFUL) < 0) {
            throw new DeadlineExceededException(operation);
        }
        return left.compareTo(cap) < 0 ? left : cap;
    }
}
