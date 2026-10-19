package com.lurielabs.ordersapi.orders;

import java.time.Instant;
import java.util.List;

/** An order with its items, as the API returns it; created_at serializes as RFC 3339 in UTC. */
record Order(long id, long customerId, String status, Instant createdAt, List<Item> items) {

    record Item(String sku, int qty) {
    }
}
