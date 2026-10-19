package com.example.orders;

import com.example.orders.Dtos.OrderItem;
import com.example.orders.Dtos.Quote;
import java.util.List;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
class QuoteService {

    private final OrderService orders;
    private final PricingClient pricing;
    private final long quoteTimeoutNanos;
    private final int parallelismPerQuote;

    QuoteService(
            OrderService orders,
            PricingClient pricing,
            @Value("${QUOTE_TIMEOUT_MS:2500}") long quoteTimeoutMs,
            @Value("${PRICING_PARALLELISM_PER_QUOTE:10}") int parallelismPerQuote) {
        this.orders = orders;
        this.pricing = pricing;
        this.quoteTimeoutNanos = TimeUnit.MILLISECONDS.toNanos(quoteTimeoutMs);
        this.parallelismPerQuote = parallelismPerQuote;
    }

    /**
     * Prices every item of the order in parallel. The order is loaded in its own short
     * transaction first, so no database connection is held while waiting on the pricing service.
     * The first failure wins: outstanding lookups are cancelled and the error is returned.
     */
    Quote quote(long orderId) {
        List<OrderItem> items = orders.get(orderId).items();
        long deadline = System.nanoTime() + quoteTimeoutNanos;
        Semaphore parallelism = new Semaphore(parallelismPerQuote);

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            CompletionService<Long> lookups = new ExecutorCompletionService<>(executor);
            for (OrderItem item : items) {
                lookups.submit(() -> {
                    if (!parallelism.tryAcquire(deadline - System.nanoTime(), TimeUnit.NANOSECONDS)) {
                        throw new PricingUnavailableException("pricing service timed out");
                    }
                    try {
                        return Math.multiplyExact(pricing.priceCents(item.sku(), deadline), (long) item.qty());
                    } finally {
                        parallelism.release();
                    }
                });
            }

            try {
                long total = 0;
                for (int i = 0; i < items.size(); i++) {
                    Future<Long> done = lookups.poll(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
                    if (done == null) {
                        throw new PricingUnavailableException("pricing service timed out");
                    }
                    total = Math.addExact(total, result(done));
                }
                return new Quote(orderId, total);
            } catch (RuntimeException | InterruptedException e) {
                executor.shutdownNow(); // interrupt outstanding lookups so close() returns promptly
                throw e;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PricingUnavailableException("quote interrupted");
        }
    }

    private static long result(Future<Long> future) throws InterruptedException {
        try {
            return future.get();
        } catch (ExecutionException e) {
            if (e.getCause() instanceof RuntimeException cause) {
                throw cause;
            }
            throw new PricingUnavailableException("pricing lookup failed");
        }
    }
}
