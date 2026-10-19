package com.example.shop.orders;

public record Order(String id, String customerId, long cents, String chargeId) {}
