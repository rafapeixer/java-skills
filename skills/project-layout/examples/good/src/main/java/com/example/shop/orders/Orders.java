package com.example.shop.orders;

import java.util.Optional;

/** The orders feature. Its API is this class and Order; everything else stays package-private. */
public final class Orders {

    /** What orders needs from billing, declared here by the consumer, not by billing. */
    @FunctionalInterface
    public interface Charger {
        String charge(String customerId, long cents);
    }

    private final Charger charger;
    private final OrderStore store = new OrderStore();

    public Orders(Charger charger) {
        this.charger = charger;
    }

    public Order place(String customerId, long cents) {
        if (cents <= 0) {
            throw new IllegalArgumentException("place order for " + customerId + ": amount must be positive");
        }
        String chargeId = charger.charge(customerId, cents);
        return store.save(customerId, cents, chargeId);
    }

    public Optional<Order> find(String id) {
        return store.find(id);
    }
}
