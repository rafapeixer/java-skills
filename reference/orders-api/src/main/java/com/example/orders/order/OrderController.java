package com.example.orders.order;

import java.net.URI;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
class OrderController {

    static final int MAX_LIMIT = 100;

    private final OrderRepository orders;
    private final QuoteService quotes;

    OrderController(OrderRepository orders, QuoteService quotes) {
        this.orders = orders;
        this.quotes = quotes;
    }

    @PostMapping("/orders")
    public ResponseEntity<Json.Created> create(@RequestBody Json.CreateOrder request) {
        List<Order.Item> items = request.validItems();
        long id = orders.insert(request.customerId(), items);
        return ResponseEntity.created(URI.create("/orders/" + id)).body(new Json.Created(id));
    }

    @GetMapping("/orders/{id}")
    public Json.OrderView get(@PathVariable("id") long id) {
        return orders.find(id).map(Json.OrderView::of).orElseThrow(() -> new OrderNotFoundException(id));
    }

    @GetMapping("/customers/{id}/orders")
    public Json.OrderPage list(
            @PathVariable("id") long customerId,
            @RequestParam(name = "limit", defaultValue = "20") int limit,
            @RequestParam(name = "cursor", defaultValue = "") String cursor) {
        if (customerId <= 0) {
            throw new InvalidRequestException("customer id must be a positive integer");
        }
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new InvalidRequestException("limit must be between 1 and " + MAX_LIMIT);
        }
        Cursor after = cursor.isEmpty() ? null : Cursor.decode(cursor);
        // One extra row tells whether another page exists without a count query.
        List<Order> rows = orders.page(customerId, after, limit + 1);
        String next = "";
        if (rows.size() > limit) {
            rows = rows.subList(0, limit);
            next = Cursor.after(rows.getLast()).encode();
        }
        return new Json.OrderPage(rows.stream().map(Json.OrderView::of).toList(), next);
    }

    @GetMapping("/orders/{id}/quote")
    public Json.Quote quote(@PathVariable("id") long id) {
        return new Json.Quote(id, quotes.totalCents(id));
    }
}
