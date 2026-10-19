package com.example.orders.order;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** The API's request and response shapes. */
final class Json {

    private Json() {
    }

    static final int MAX_ITEMS = 100;

    record CreateOrder(@JsonProperty("customer_id") Long customerId, @JsonProperty("items") List<NewItem> items) {

        /** Checks the whole request and returns the items to insert. */
        List<Order.Item> validItems() {
            if (customerId == null || customerId <= 0) {
                throw new InvalidRequestException("customer_id must be a positive integer");
            }
            if (items == null || items.isEmpty() || items.size() > MAX_ITEMS) {
                throw new InvalidRequestException("items must contain 1 to " + MAX_ITEMS + " entries");
            }
            Set<String> seen = new HashSet<>();
            for (NewItem item : items) {
                if (item == null || item.sku() == null || item.sku().isEmpty()) {
                    throw new InvalidRequestException("every item needs a non-empty sku");
                }
                if (item.qty() == null || item.qty() < 1) {
                    throw new InvalidRequestException("qty must be at least 1");
                }
                if (!seen.add(item.sku())) {
                    throw new InvalidRequestException("duplicate sku: " + item.sku());
                }
            }
            return items.stream().map(i -> new Order.Item(i.sku(), i.qty())).toList();
        }
    }

    record NewItem(@JsonProperty("sku") String sku, @JsonProperty("qty") Integer qty) {
    }

    record Created(@JsonProperty("id") long id) {
    }

    record OrderView(
            @JsonProperty("id") long id,
            @JsonProperty("customer_id") long customerId,
            @JsonProperty("status") String status,
            @JsonProperty("created_at") String createdAt,
            @JsonProperty("items") List<ItemView> items) {

        static OrderView of(Order order) {
            return new OrderView(order.id(), order.customerId(), order.status(),
                    DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(order.createdAt()), // RFC 3339
                    order.items().stream().map(i -> new ItemView(i.sku(), i.qty())).toList());
        }
    }

    record ItemView(@JsonProperty("sku") String sku, @JsonProperty("qty") int qty) {
    }

    record OrderPage(@JsonProperty("orders") List<OrderView> orders, @JsonProperty("next_cursor") String nextCursor) {
    }

    record Quote(@JsonProperty("order_id") long orderId, @JsonProperty("total_cents") long totalCents) {
    }
}
