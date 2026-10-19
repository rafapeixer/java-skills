package com.example.orders;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** JSON request and response bodies. */
final class Dtos {

    private Dtos() {
    }

    static final int MAX_ITEMS = 100;
    static final int MAX_SKU_LENGTH = 255;

    record CreateOrderRequest(@JsonProperty("customer_id") Long customerId, List<ItemRequest> items) {

        /** Validates the request and returns its items in normalized form; throws 400 on invalid input. */
        List<OrderItem> validatedItems() {
            if (customerId == null || customerId <= 0) {
                throw ApiException.badRequest("customer_id must be a positive integer");
            }
            if (items == null || items.isEmpty() || items.size() > MAX_ITEMS) {
                throw ApiException.badRequest("items must contain between 1 and " + MAX_ITEMS + " entries");
            }
            Set<String> seen = new HashSet<>();
            for (ItemRequest item : items) {
                if (item == null) {
                    throw ApiException.badRequest("items must not contain null");
                }
                if (item.sku() == null || item.sku().isBlank() || item.sku().length() > MAX_SKU_LENGTH
                        || item.sku().indexOf('\0') >= 0) {
                    throw ApiException.badRequest("sku must be a non-empty string of at most " + MAX_SKU_LENGTH + " characters");
                }
                if (item.qty() == null || item.qty() < 1) {
                    throw ApiException.badRequest("qty must be an integer >= 1");
                }
                if (!seen.add(item.sku())) {
                    throw ApiException.badRequest("duplicate sku: " + item.sku());
                }
            }
            return items.stream().map(i -> new OrderItem(i.sku(), i.qty())).toList();
        }
    }

    record ItemRequest(String sku, Integer qty) {
    }

    record OrderItem(String sku, int qty) {
    }

    record CreatedOrder(long id) {
    }

    record OrderView(
            long id,
            @JsonProperty("customer_id") long customerId,
            String status,
            @JsonProperty("created_at") String createdAt,
            List<OrderItem> items) {
    }

    record OrderPage(List<OrderView> orders, @JsonProperty("next_cursor") String nextCursor) {
    }

    record Quote(@JsonProperty("order_id") long orderId, @JsonProperty("total_cents") long totalCents) {
    }

    record ErrorBody(String error) {
    }
}
