package com.example.shop.billing;

/** The billing feature: a concrete class, no BillingService interface with a single Impl. */
public final class Billing {
    private final long limitCents;
    private final Ledger ledger = new Ledger();

    public Billing(long limitCents) {
        this.limitCents = limitCents;
    }

    public String charge(String customerId, long cents) {
        if (cents > limitCents) {
            throw new IllegalStateException("charge " + customerId + ": " + cents + " over limit " + limitCents);
        }
        return ledger.record(customerId, cents);
    }
}
