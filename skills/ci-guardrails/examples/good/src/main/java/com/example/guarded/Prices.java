package com.example.guarded;

import java.util.List;

/** Compiles with -Xlint:all and failOnWarning: no raw types, no unchecked casts, no deprecated API. */
public final class Prices {

    private Prices() {}

    public static long total(List<Long> cents) {
        long sum = 0;
        for (long c : cents) {
            sum = Math.addExact(sum, c);
        }
        return sum;
    }
}
