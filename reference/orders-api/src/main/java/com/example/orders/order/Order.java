package com.example.orders.order;

import java.time.OffsetDateTime;
import java.util.List;

record Order(long id, long customerId, String status, OffsetDateTime createdAt, List<Item> items) {

    record Item(String sku, int qty) {
    }
}
