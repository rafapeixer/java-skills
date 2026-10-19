package com.example.orders;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class OrdersController {
    private final OrderService orders;

    public OrdersController(OrderService orders) {
        this.orders = orders;
    }

    @GetMapping("/orders/{id}")
    public ResponseEntity<?> get(@PathVariable String id) {
        // MISTAKE: status mapping inside each controller method; every endpoint maps the
        // same exception differently, and the error body is a bare string, not RFC 9457.
        try {
            return ResponseEntity.ok(orders.get(id));
        } catch (OrderNotFoundException e) {
            return ResponseEntity.status(404).body(e.getMessage());
        }
    }

    @PostMapping("/orders")
    public ResponseEntity<?> place(@RequestParam String sku, @RequestParam int quantity) {
        try {
            return ResponseEntity.ok(orders.place(sku, quantity));
        } catch (Exception e) {
            // MISTAKE: picks the status by matching the message text, which breaks the day
            // someone rewords it; and returns e.getMessage() for a 500, leaking internals
            // (SQL, hostnames, file paths) to the client.
            if (e.getMessage() != null && e.getMessage().contains("stock")) {
                return ResponseEntity.status(409).body(e.getMessage());
            }
            return ResponseEntity.status(500).body(e.getMessage());
        }
    }
}
