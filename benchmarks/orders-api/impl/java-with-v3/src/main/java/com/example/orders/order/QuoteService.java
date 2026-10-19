package com.example.orders.order;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Service;

/** Prices an order's items in parallel; no database connection is held while pricing runs. */
@Service
class QuoteService implements AutoCloseable {

    /** The whole quote, all lookups included; past it the client gets a 503. */
    static final Duration BUDGET = Duration.ofMillis(1500);
    /** Lookups one quote runs at once, so a 100-item order cannot take every pricing permit. */
    private static final int FAN_OUT = 16;

    private final OrderRepository orders;
    private final PricingClient pricing;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    QuoteService(OrderRepository orders, PricingClient pricing) {
        this.orders = orders;
        this.pricing = pricing;
    }

    long totalCents(long orderId) {
        // The order is read (one statement, connection returned) before the deadline starts.
        Order order = orders.find(orderId).orElseThrow(() -> new OrderNotFoundException(orderId));
        Deadline deadline = Deadline.after(BUDGET);
        Semaphore window = new Semaphore(FAN_OUT);
        CompletionService<Long> lines = new ExecutorCompletionService<>(executor);
        List<Future<Long>> started = new ArrayList<>(order.items().size());
        try {
            for (Order.Item item : order.items()) {
                started.add(lines.submit(() -> linePrice(item, window, deadline)));
            }
            long total = 0;
            for (int i = 0; i < started.size(); i++) {
                // Completion order: the first unknown SKU or failure ends the quote at once.
                Future<Long> done = lines.poll(deadline.remainingNanos(), TimeUnit.NANOSECONDS);
                if (done == null) {
                    throw new PricingUnavailableException("pricing did not answer within " + BUDGET.toMillis() + " ms");
                }
                total = Math.addExact(total, result(done));
            }
            return total;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PricingUnavailableException("interrupted while pricing order " + orderId, e);
        } finally {
            started.forEach(f -> f.cancel(true));
        }
    }

    private long linePrice(Order.Item item, Semaphore window, Deadline deadline) throws InterruptedException {
        if (!window.tryAcquire(deadline.remainingNanos(), TimeUnit.NANOSECONDS)) {
            throw new PricingUnavailableException("quote deadline passed before pricing sku " + item.sku());
        }
        try {
            return Math.multiplyExact(pricing.cents(item.sku(), deadline), (long) item.qty());
        } finally {
            window.release();
        }
    }

    private static long result(Future<Long> done) throws InterruptedException {
        try {
            return done.get();
        } catch (ExecutionException e) {
            if (e.getCause() instanceof UnknownSkuException unknown) {
                throw unknown;
            }
            if (e.getCause() instanceof PricingUnavailableException unavailable) {
                throw unavailable;
            }
            if (e.getCause() instanceof ArithmeticException overflow) {
                throw new IllegalStateException("order total overflows", overflow);
            }
            throw new PricingUnavailableException("pricing lookup failed", e.getCause());
        }
    }

    @Override
    public void close() {
        executor.close();
    }
}
