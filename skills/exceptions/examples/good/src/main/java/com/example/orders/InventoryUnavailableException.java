package com.example.orders;

public final class InventoryUnavailableException extends OrderException {
    public InventoryUnavailableException(String operation, Throwable cause) {
        super(operation + ": inventory unavailable", cause);
    }
}
