package com.example.orders;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

// No try/catch here: failures go to ApiExceptionHandler, the one place that maps them.
@RestController
public class OrdersController {
    private final OrderService orders;

    public OrdersController(OrderService orders) {
        this.orders = orders;
    }

    @GetMapping("/orders/{id}")
    public Order get(@PathVariable String id) {
        return orders.get(id);
    }

    @PostMapping("/orders")
    @ResponseStatus(HttpStatus.CREATED)
    public Order place(@RequestParam String sku, @RequestParam int quantity) {
        return orders.place(sku, quantity);
    }
}
