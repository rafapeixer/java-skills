package com.example.orders.order;

import java.time.Duration;

/** A point in time a piece of work must finish by; calls take min(their cap, what is left). */
record Deadline(long atNanos) {

    static Deadline after(Duration budget) {
        return new Deadline(System.nanoTime() + budget.toNanos());
    }

    long remainingNanos() {
        return Math.max(0, atNanos - System.nanoTime());
    }
}
