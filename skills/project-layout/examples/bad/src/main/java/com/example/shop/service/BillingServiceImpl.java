package com.example.shop.service;

import com.example.shop.repository.Store;

public class BillingServiceImpl implements BillingService {
    private final Store store;

    public BillingServiceImpl(Store store) {
        this.store = store;
    }

    @Override
    public String charge(String customerId, long cents) {
        String id = "ch_" + (store.charges.size() + 1);
        store.charges.put(id, cents);
        return id;
    }

    @Override
    public void refund(String chargeId) {
        store.charges.remove(chargeId);
    }

    @Override
    public long totalCharged(String customerId) {
        return store.charges.values().stream().mapToLong(Long::longValue).sum();
    }
}
