package com.example.shop.orders;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

// Package-private: only orders reads or writes its own storage.
final class OrderStore {
    private final Map<String, Order> orders = new ConcurrentHashMap<>();
    private final AtomicLong next = new AtomicLong();

    Order save(String customerId, long cents, String chargeId) {
        Order order = new Order("ord_" + next.incrementAndGet(), customerId, cents, chargeId);
        orders.put(order.id(), order);
        return order;
    }

    Optional<Order> find(String id) {
        return Optional.ofNullable(orders.get(id));
    }
}
