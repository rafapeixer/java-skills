package com.example.ordersapi.orders;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/** One page of a customer's orders; {@code nextCursor} is empty on the last page. */
record OrderPage(List<OrderView> orders, @JsonProperty("next_cursor") String nextCursor) {}
