package com.example.unguarded;

import java.util.ArrayList;
import java.util.List;

/** What a pipeline without -Xlint:all and failOnWarning lets through. */
public final class Prices {

    private Prices() {}

    // MISTAKE: raw List; without -Xlint:all javac says nothing, and a String in the
    // list fails at run time with ClassCastException instead of at compile time.
    public static long total(List cents) {
        long sum = 0;
        for (Object c : cents) {
            sum += (Long) c; // MISTAKE: silent overflow; Math.addExact would throw.
        }
        return sum;
    }

    public static List<Long> parse(List<String> raw) {
        List<Long> out = new ArrayList<>();
        for (String s : raw) {
            out.add(new Long(s)); // MISTAKE: deprecated for removal; a warning today, a compile error on a later JDK.
        }
        return out;
    }
}
