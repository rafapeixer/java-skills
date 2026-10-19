package com.example.shop;

import com.example.shop.billing.Billing;
import com.example.shop.orders.Order;
import com.example.shop.orders.Orders;

/** The root package holds the entry point and the wiring between features, nothing else. */
public final class ShopApplication {

    private ShopApplication() {}

    public static void main(String[] args) {
        Order order = wire().place("cus_1", 2_500);
        System.out.println("order " + order.id() + " charged as " + order.chargeId());
    }

    static Orders wire() {
        Billing billing = new Billing(10_000);
        return new Orders(billing::charge); // the root is the only package that sees both features
    }
}
