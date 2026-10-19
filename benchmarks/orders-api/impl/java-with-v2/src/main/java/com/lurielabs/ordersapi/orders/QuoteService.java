package com.lurielabs.ordersapi.orders;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.Semaphore;

import com.lurielabs.ordersapi.api.NotFoundException;
import com.lurielabs.ordersapi.api.OverloadedException;
import com.lurielabs.ordersapi.pricing.Deadline;
import com.lurielabs.ordersapi.pricing.PricingClient;
import org.springframework.stereotype.Service;

@Service
class QuoteService {

    // Whole quote, database lookup included; well under the load balancer's 60 s idle timeout and
    // short enough that a stalled pricing service turns into fast 503s.
    private static final Duration BUDGET = Duration.ofSeconds(2);

    // Little's law: assumed peak of 20 quotes/s per instance x (0.3 s normal + 2 s worst case,
    // the deadline) = 46 in flight; 48 permits. With FAN_OUT 10 that caps pricing connections
    // from one instance at 480. Revisit with the measured quote rate.
    private static final int MAX_CONCURRENT_QUOTES = 48;

    private final Semaphore inFlight = new Semaphore(MAX_CONCURRENT_QUOTES);
    private final OrderRepository orders;
    private final PricingClient pricing;

    QuoteService(OrderRepository orders, PricingClient pricing) {
        this.orders = orders;
        this.pricing = pricing;
    }

    record Quote(long orderId, long totalCents) {
    }

    Quote quote(long orderId) {
        Deadline deadline = Deadline.after(BUDGET);
        // The connection goes back to the pool here, before any pricing call starts.
        Order order = orders.find(orderId).orElseThrow(() -> new NotFoundException("order not found"));
        if (!inFlight.tryAcquire()) {
            throw new OverloadedException("concurrent quotes");
        }
        try {
            Map<String, Long> prices = pricing.prices(order.items().stream().map(Order.Item::sku).toList(), deadline);
            long total = 0;
            for (Order.Item item : order.items()) {
                total = Math.addExact(total, Math.multiplyExact(prices.get(item.sku()), (long) item.qty()));
            }
            return new Quote(orderId, total);
        } finally {
            inFlight.release();
        }
    }
}
