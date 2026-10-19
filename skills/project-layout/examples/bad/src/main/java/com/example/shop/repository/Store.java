package com.example.shop.repository;

import com.example.shop.model.Order;
import java.util.HashMap;
import java.util.Map;

// MISTAKE: one public store with public fields shared by orders and billing; any
// package can read or rewrite another feature's data, so nothing can change safely.
public class Store {
    public final Map<String, Order> orders = new HashMap<>();
    public final Map<String, Long> charges = new HashMap<>();
}
