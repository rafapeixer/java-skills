package com.example.shop.service;

// MISTAKE: the provider declares every method it has; each consumer depends on all of
// them, and each fake in a test must implement all of them.
public interface BillingService {
    String charge(String customerId, long cents);

    void refund(String chargeId);

    long totalCharged(String customerId);
}
