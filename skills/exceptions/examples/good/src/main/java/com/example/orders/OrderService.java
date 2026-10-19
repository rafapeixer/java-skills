package com.example.orders;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class OrderService {
    private final Map<String, Order> orders = new ConcurrentHashMap<>();
    private final InventoryClient inventory;

    public OrderService(InventoryClient inventory) {
        this.inventory = inventory;
    }

    public Order get(String id) {
        Order order = orders.get(id);
        if (order == null) {
            throw new OrderNotFoundException(id);
        }
        return order;
    }

    public Order place(String sku, int quantity) {
        int available = availableStock(sku);
        if (available < quantity) {
            throw new InsufficientStockException(sku, quantity, available);
        }
        Order order = new Order(UUID.randomUUID().toString(), sku, quantity);
        orders.put(order.id(), order);
        return order;
    }

    private int availableStock(String sku) {
        try {
            return inventory.available(sku);
        } catch (IOException e) {
            // Translate at the layer boundary: say what we were doing, keep the cause.
            throw new InventoryUnavailableException("check stock for sku " + sku, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); // restore the flag so the caller sees it
            throw new InventoryUnavailableException("check stock for sku " + sku + " (interrupted)", e);
        }
    }
}
