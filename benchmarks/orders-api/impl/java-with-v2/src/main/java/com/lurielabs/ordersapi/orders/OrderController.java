package com.lurielabs.ordersapi.orders;

import java.net.URI;
import java.util.List;

import com.lurielabs.ordersapi.api.InvalidRequestException;
import com.lurielabs.ordersapi.api.NotFoundException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
class OrderController {

    private static final int MAX_LIMIT = 100;

    private final OrderRepository orders;
    private final QuoteService quotes;

    OrderController(OrderRepository orders, QuoteService quotes) {
        this.orders = orders;
        this.quotes = quotes;
    }

    record Created(long id) {
    }

    record CustomerOrders(List<Order> orders, String nextCursor) {
    }

    @PostMapping("/orders")
    ResponseEntity<Created> create(@RequestBody CreateOrderRequest request) {
        List<Order.Item> items = request.validatedItems();
        long id = orders.insert(request.customerId(), items);
        return ResponseEntity.created(URI.create("/orders/" + id)).body(new Created(id));
    }

    @GetMapping("/orders/{id}")
    Order get(@PathVariable long id) {
        return orders.find(id).orElseThrow(() -> new NotFoundException("order not found"));
    }

    @GetMapping("/customers/{id}/orders")
    CustomerOrders customerOrders(
            @PathVariable long id,
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(defaultValue = "") String cursor) {
        if (id <= 0) {
            throw new InvalidRequestException("customer id must be a positive integer");
        }
        if (limit < 1) {
            throw new InvalidRequestException("limit must be at least 1");
        }
        int pageSize = Math.min(limit, MAX_LIMIT);
        Cursor after = cursor.isEmpty() ? null : Cursor.decode(cursor);
        List<Order> rows = orders.pageWithLookahead(id, after, pageSize);
        if (rows.size() <= pageSize) {
            return new CustomerOrders(rows, "");
        }
        List<Order> page = rows.subList(0, pageSize);
        return new CustomerOrders(page, Cursor.of(page.getLast()).encode());
    }

    @GetMapping("/orders/{id}/quote")
    QuoteService.Quote quote(@PathVariable long id) {
        return quotes.quote(id);
    }
}
