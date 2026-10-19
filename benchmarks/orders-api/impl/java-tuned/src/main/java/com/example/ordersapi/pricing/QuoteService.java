package com.example.ordersapi.pricing;

import com.example.ordersapi.Deadline;
import com.example.ordersapi.orders.Item;
import com.example.ordersapi.orders.OrderNotFoundException;
import com.example.ordersapi.orders.OrderStore;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.stereotype.Service;

@Service
class QuoteService {

    /** Each lookup bounds itself by the deadline; this only covers handing back its result. */
    private static final Duration GRACE = Duration.ofMillis(50);

    private final OrderStore orders;
    private final PricingClient pricing;

    QuoteService(OrderStore orders, PricingClient pricing) {
        this.orders = orders;
        this.pricing = pricing;
    }

    /**
     * Total price of the order in cents. The items are read first, in one statement,
     * so no database connection is held while pricing is called. SKUs are
     * priced concurrently, one virtual thread each.
     */
    long totalCents(long orderId, Deadline deadline) {
        List<Item> items = orders.findItems(orderId).orElseThrow(() -> new OrderNotFoundException(orderId));
        if (items.isEmpty()) {
            return 0;
        }
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<Long>> lines = new ArrayList<>(items.size());
            for (Item item : items) {
                lines.add(executor.submit(() -> lineTotal(item, deadline)));
            }
            try {
                long total = 0;
                for (Future<Long> line : lines) {
                    total = Math.addExact(total, await(line, deadline));
                }
                return total;
            } catch (ArithmeticException e) {
                throw new PricingUnavailableException("order total overflows", e);
            } finally {
                // On any failure stop the remaining lookups; close() then returns promptly.
                lines.forEach(line -> line.cancel(true));
            }
        }
    }

    private long lineTotal(Item item, Deadline deadline) {
        long unit = pricing.unitPriceCents(item.sku(), deadline);
        try {
            return Math.multiplyExact(unit, (long) item.qty());
        } catch (ArithmeticException e) {
            throw new PricingUnavailableException("price for sku " + item.sku() + " overflows", e);
        }
    }

    private static long await(Future<Long> line, Deadline deadline) {
        long waitNanos = Math.max(0, deadline.remaining().toNanos()) + GRACE.toNanos();
        try {
            return line.get(waitNanos, TimeUnit.NANOSECONDS);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof RuntimeException failure) {
                throw failure; // UnknownSku, PricingUnavailable, DeadlineExceeded
            }
            throw new PricingUnavailableException("pricing lookup failed", e.getCause());
        } catch (TimeoutException e) {
            throw new PricingUnavailableException("pricing did not answer within the request deadline", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PricingUnavailableException("interrupted while waiting for pricing", e);
        }
    }
}
