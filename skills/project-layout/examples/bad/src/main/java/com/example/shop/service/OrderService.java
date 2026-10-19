package com.example.shop.service;

import com.example.shop.model.Order;

// MISTAKE: an interface with exactly one implementation, written "for testability";
// every change is made twice and navigation lands on the interface, not the code.
public interface OrderService {
    Order place(String customerId, long cents);
}
