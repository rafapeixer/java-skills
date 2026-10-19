package com.example.ordersapi.orders;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/** An order as returned by the API; {@code createdAt} is RFC 3339 in UTC. */
record OrderView(
        long id,
        @JsonProperty("customer_id") long customerId,
        String status,
        @JsonProperty("created_at") String createdAt,
        List<Item> items) {}
