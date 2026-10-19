package com.example.shop.orders;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class OrdersTest {

    @Test
    void placesAnOrderWithAOneLineFake() {
        Orders orders = new Orders((customer, cents) -> "ch_fake"); // no billing, no mocking library
        Order order = orders.place("cus_1", 500);
        assertEquals("ch_fake", order.chargeId());
        assertEquals(order, orders.find(order.id()).orElseThrow());
    }

    @Test
    void rejectsANonPositiveAmountBeforeCharging() {
        Orders orders = new Orders((customer, cents) -> {
            throw new AssertionError("must not charge");
        });
        assertThrows(IllegalArgumentException.class, () -> orders.place("cus_1", 0));
    }
}
