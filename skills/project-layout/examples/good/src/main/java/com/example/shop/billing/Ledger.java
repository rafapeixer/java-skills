package com.example.shop.billing;

import java.util.ArrayList;
import java.util.List;

// Package-private: billing can change how it keeps charges without touching orders.
final class Ledger {
    private final List<String> entries = new ArrayList<>();

    synchronized String record(String customerId, long cents) {
        entries.add(customerId + ":" + cents);
        return "ch_" + entries.size();
    }
}
