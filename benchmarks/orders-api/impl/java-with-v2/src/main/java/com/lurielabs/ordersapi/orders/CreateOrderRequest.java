package com.lurielabs.ordersapi.orders;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.lurielabs.ordersapi.api.InvalidRequestException;

/** The POST /orders body as sent; boxed types so a missing field is caught here, not defaulted to 0. */
record CreateOrderRequest(Long customerId, List<NewItem> items) {

    static final int MAX_ITEMS = 100;

    record NewItem(String sku, Integer qty) {
    }

    List<Order.Item> validatedItems() {
        if (customerId == null || customerId <= 0) {
            throw new InvalidRequestException("customer_id must be a positive integer");
        }
        if (items == null || items.isEmpty() || items.size() > MAX_ITEMS) {
            throw new InvalidRequestException("items must contain 1 to " + MAX_ITEMS + " entries");
        }
        Set<String> seen = new HashSet<>();
        List<Order.Item> valid = new ArrayList<>(items.size());
        for (NewItem item : items) {
            if (item == null || item.sku() == null || item.sku().isEmpty()) {
                throw new InvalidRequestException("every item needs a non-empty sku");
            }
            if (item.qty() == null || item.qty() < 1) {
                throw new InvalidRequestException("qty must be at least 1 for sku " + item.sku());
            }
            if (!seen.add(item.sku())) {
                throw new InvalidRequestException("duplicate sku " + item.sku());
            }
            valid.add(new Order.Item(item.sku(), item.qty()));
        }
        return valid;
    }
}
