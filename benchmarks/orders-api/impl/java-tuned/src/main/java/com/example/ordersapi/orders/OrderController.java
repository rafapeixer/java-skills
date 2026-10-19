package com.example.ordersapi.orders;

import java.net.URI;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
class OrderController {

    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 100;

    private final OrderStore store;

    OrderController(OrderStore store) {
        this.store = store;
    }

    @PostMapping(path = "/orders", consumes = "application/json")
    ResponseEntity<Map<String, Long>> create(@RequestBody(required = false) @Nullable CreateOrderRequest body) {
        if (body == null) {
            throw new InvalidRequestException("request body is required");
        }
        long customerId = body.validCustomerId();
        List<Item> items = body.validItems();
        long id = store.create(customerId, items);
        return ResponseEntity.created(URI.create("/orders/" + id)).body(Map.of("id", id));
    }

    @GetMapping("/orders/{id}")
    OrderView get(@PathVariable("id") long id) {
        return store.find(id).orElseThrow(() -> new OrderNotFoundException(id));
    }

    @GetMapping("/customers/{id}/orders")
    OrderPage list(@PathVariable("id") long customerId,
                   @RequestParam(name = "limit", required = false) @Nullable Integer limit,
                   @RequestParam(name = "cursor", required = false) @Nullable String cursor) {
        int size = limit == null ? DEFAULT_LIMIT : limit;
        if (size < 1) {
            throw new InvalidRequestException("limit must be at least 1");
        }
        size = Math.min(size, MAX_LIMIT);
        Cursor after = cursor == null || cursor.isEmpty() ? null : Cursor.decode(cursor);
        return store.page(customerId, after, size);
    }
}
