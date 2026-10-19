package com.example.orders;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class OrderService {
    private static final Logger log = LoggerFactory.getLogger(OrderService.class);
    private final Map<String, Order> orders = new ConcurrentHashMap<>();
    private final InventoryClient inventory;

    public OrderService(InventoryClient inventory) {
        this.inventory = inventory;
    }

    public Order get(String id) throws OrderNotFoundException {
        Order order = orders.get(id);
        if (order == null) {
            throw new OrderNotFoundException("not found");  // MISTAKE: no id; the log cannot say which order
        }
        return order;
    }

    public Order place(String sku, int quantity) {
        int available;
        try {
            available = inventory.available(sku);
        } catch (IOException e) {
            // MISTAKE: logs and rethrows, so the same failure is logged again by every layer
            // above; and new RuntimeException(e.getMessage()) drops the cause and its stack trace.
            log.error("inventory failed", e);
            throw new RuntimeException(e.getMessage());
        } catch (InterruptedException e) {
            // MISTAKE: swallows the interrupt; the flag is cleared, so shutdown or a cancelled
            // Future cannot stop this thread, and the order goes ahead with zero stock.
            available = 0;
        }
        if (available < quantity) {
            throw new IllegalStateException("insufficient stock");
        }
        Order order = new Order(UUID.randomUUID().toString(), sku, quantity);
        orders.put(order.id(), order);
        return order;
    }
}
