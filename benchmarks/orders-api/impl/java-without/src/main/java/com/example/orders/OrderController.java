package com.example.orders;

import com.example.orders.Dtos.CreateOrderRequest;
import com.example.orders.Dtos.CreatedOrder;
import com.example.orders.Dtos.OrderPage;
import com.example.orders.Dtos.OrderView;
import com.example.orders.Dtos.Quote;
import java.net.URI;
import java.util.Map;
import org.springframework.http.HttpStatus;
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

    private final OrderService orders;
    private final QuoteService quotes;

    OrderController(OrderService orders, QuoteService quotes) {
        this.orders = orders;
        this.quotes = quotes;
    }

    @PostMapping("/orders")
    ResponseEntity<CreatedOrder> create(@RequestBody CreateOrderRequest request) {
        var items = request.validatedItems();
        long id = orders.create(request.customerId(), items);
        return ResponseEntity.status(HttpStatus.CREATED)
                .location(URI.create("/orders/" + id))
                .body(new CreatedOrder(id));
    }

    @GetMapping("/orders/{id}")
    OrderView get(@PathVariable("id") long id) {
        return orders.get(id);
    }

    @GetMapping("/customers/{id}/orders")
    OrderPage listForCustomer(
            @PathVariable("id") long customerId,
            @RequestParam(name = "limit", required = false) String limit,
            @RequestParam(name = "cursor", required = false) String cursor) {
        Cursor after = cursor == null || cursor.isEmpty() ? null : Cursor.decode(cursor);
        return orders.listForCustomer(customerId, after, parseLimit(limit));
    }

    @GetMapping("/orders/{id}/quote")
    Quote quote(@PathVariable("id") long id) {
        return quotes.quote(id);
    }

    /** Liveness only: does not touch the database, so a DB blip does not take every replica out of the load balancer. */
    @GetMapping("/healthz")
    Map<String, String> health() {
        return Map.of("status", "ok");
    }

    private static int parseLimit(String raw) {
        if (raw == null || raw.isEmpty()) {
            return DEFAULT_LIMIT;
        }
        int limit;
        try {
            limit = Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            throw ApiException.badRequest("limit must be an integer between 1 and " + MAX_LIMIT);
        }
        if (limit < 1) {
            throw ApiException.badRequest("limit must be an integer between 1 and " + MAX_LIMIT);
        }
        return Math.min(limit, MAX_LIMIT);
    }
}
