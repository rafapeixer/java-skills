package com.example.shop.service;

import com.example.shop.model.Order;
import com.example.shop.repository.Store;

// MISTAKE: public class in a layer package; it has to be public because the store and
// the model live in other packages, so package-private cannot hide anything.
public class OrderServiceImpl implements OrderService {
    private final BillingService billing;
    private final Store store;

    public OrderServiceImpl(BillingService billing, Store store) {
        this.billing = billing;
        this.store = store;
    }

    @Override
    public Order place(String customerId, long cents) {
        String chargeId = billing.charge(customerId, cents);
        // MISTAKE: orders reads billing's data directly through the shared store;
        // billing can no longer change how it keeps charges.
        if (!store.charges.containsKey(chargeId)) {
            throw new IllegalStateException("place order for " + customerId + ": charge " + chargeId + " missing");
        }
        Order order = new Order("ord_" + (store.orders.size() + 1), customerId, cents, chargeId);
        store.orders.put(order.id(), order);
        return order;
    }
}
