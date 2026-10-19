package com.example.ordersapi.orders;

/** One line of an order: a SKU and a quantity of at least 1. */
public record Item(String sku, int qty) {}
