package com.example.ordersapi.orders;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Body of {@code POST /orders}. Components are nullable so that a missing field becomes a
 * precise 400 message from {@link #validItems()} instead of a generic parse error.
 */
record CreateOrderRequest(@JsonProperty("customer_id") @Nullable Long customerId,
                          @Nullable List<@Nullable ItemRequest> items) {

    static final int MAX_ITEMS = 100;
    static final int MAX_SKU_LENGTH = 200;

    long validCustomerId() {
        if (customerId == null) {
            throw new InvalidRequestException("customer_id is required");
        }
        if (customerId <= 0) {
            throw new InvalidRequestException("customer_id must be greater than 0");
        }
        return customerId;
    }

    List<Item> validItems() {
        if (items == null) {
            throw new InvalidRequestException("items is required");
        }
        if (items.isEmpty() || items.size() > MAX_ITEMS) {
            throw new InvalidRequestException("items must contain 1 to " + MAX_ITEMS + " entries");
        }
        List<Item> valid = new ArrayList<>(items.size());
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < items.size(); i++) {
            ItemRequest item = items.get(i);
            if (item == null) {
                throw new InvalidRequestException("items[" + i + "] must be an object");
            }
            String sku = item.sku();
            if (sku == null || sku.isBlank()) {
                throw new InvalidRequestException("items[" + i + "].sku is required");
            }
            if (sku.length() > MAX_SKU_LENGTH) {
                throw new InvalidRequestException(
                        "items[" + i + "].sku must be at most " + MAX_SKU_LENGTH + " characters");
            }
            if (item.qty() == null) {
                throw new InvalidRequestException("items[" + i + "].qty is required");
            }
            if (item.qty() < 1) {
                throw new InvalidRequestException("items[" + i + "].qty must be at least 1");
            }
            if (!seen.add(sku)) {
                throw new InvalidRequestException("duplicate sku in items: " + sku);
            }
            valid.add(new Item(sku, item.qty()));
        }
        return valid;
    }
}
