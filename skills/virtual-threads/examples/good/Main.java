import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;

// The pricing service accepts at most 10 concurrent calls per replica. That limit
// is a Semaphore around the call, not the size of a thread pool.
static final int PRICING_MAX_CONCURRENT = 10;
static final Semaphore pricingPermits = new Semaphore(PRICING_MAX_CONCURRENT);

// Immutable and thread-safe: one shared instance instead of one per thread in a ThreadLocal.
static final DateTimeFormatter DAY = DateTimeFormatter.ISO_LOCAL_DATE;

static final AtomicInteger inPricing = new AtomicInteger();
static final AtomicInteger maxInPricing = new AtomicInteger();

void main() throws Exception {
    // 1. 10,000 tasks that each block for one second, one virtual thread per task.
    Duration plain = time(() -> {
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < 10_000; i++) {
                executor.submit(() -> {
                    Thread.sleep(Duration.ofSeconds(1));
                    return null;
                });
            }
        } // close() waits for every task, so nothing outlives this block
    });

    // 2. The same tasks blocking while holding a monitor. Since JDK 24 (JEP 491)
    // synchronized no longer pins, so this is as fast as the plain version.
    Duration inSynchronized = time(() -> {
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < 10_000; i++) {
                executor.submit(() -> {
                    var lock = new Object();
                    synchronized (lock) {
                        Thread.sleep(Duration.ofSeconds(1));
                    }
                    return null;
                });
            }
        }
    });

    // A pool of 200 platform threads needs 50 s for each batch; pinned carriers
    // (one per CPU) would need thousands of seconds. 30 s is a generous bound.
    for (Duration d : List.of(plain, inSynchronized)) {
        if (d.compareTo(Duration.ofSeconds(30)) > 0) {
            throw new AssertionError("10,000 blocking tasks took " + d);
        }
    }

    // 3. 1,000 tasks share a dependency that allows 10 concurrent calls.
    List<Future<String>> quotes = new ArrayList<>();
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
        for (int i = 0; i < 1_000; i++) {
            int productId = i;
            quotes.add(executor.submit(() -> quote(productId)));
        }
    }
    for (int i = 0; i < quotes.size(); i++) {
        String q = quotes.get(i).get();
        if (!q.equals(i + "@2026-10-18")) {
            throw new AssertionError("wrong quote " + q);
        }
    }
    if (maxInPricing.get() > PRICING_MAX_CONCURRENT) {
        throw new AssertionError("pricing saw " + maxInPricing.get() + " concurrent calls");
    }
    if (pricingPermits.availablePermits() != PRICING_MAX_CONCURRENT) {
        throw new AssertionError("a permit leaked");
    }
    System.out.printf("10,000 tasks: %d ms, in synchronized: %d ms; pricing peak %d/%d%n",
            plain.toMillis(), inSynchronized.toMillis(), maxInPricing.get(), PRICING_MAX_CONCURRENT);
}

static String quote(int productId) throws InterruptedException {
    pricingPermits.acquire();
    try {
        return callPricingService(productId);
    } finally {
        pricingPermits.release();
    }
}

static String callPricingService(int productId) throws InterruptedException {
    int now = inPricing.incrementAndGet();
    maxInPricing.accumulateAndGet(now, Math::max);
    try {
        Thread.sleep(Duration.ofMillis(5)); // stands in for the network call
        return productId + "@" + DAY.format(LocalDate.of(2026, 10, 18));
    } finally {
        inPricing.decrementAndGet();
    }
}

interface Body {
    void run() throws Exception;
}

static Duration time(Body body) throws Exception {
    long start = System.nanoTime();
    body.run();
    return Duration.ofNanos(System.nanoTime() - start);
}
