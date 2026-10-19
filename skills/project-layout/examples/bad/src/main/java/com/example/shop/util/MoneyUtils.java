package com.example.shop.util;

// MISTAKE: a util package of static helpers; the name says nothing about what belongs
// here, so unrelated code piles up and every package ends up importing it.
public final class MoneyUtils {
    private MoneyUtils() {}

    public static long applyDiscount(long cents, int percent) {
        return cents - cents * percent / 100;
    }
}
