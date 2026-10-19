package com.example.ordersapi.orders;

import org.jspecify.annotations.Nullable;

/** One entry of {@code items} in {@code POST /orders}, before validation. */
record ItemRequest(@Nullable String sku, @Nullable Integer qty) {}
