package com.example.shop.model;

// MISTAKE: a models package for every feature's types; every layer imports it, so it
// becomes the hub that any change ripples through.
public record Order(String id, String customerId, long cents, String chargeId) {}
