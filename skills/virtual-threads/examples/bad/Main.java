import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

// MISTAKE: pooling virtual threads. Virtual threads are cheap and should never be
// pooled; the pool size here is really a concurrency limit for the pricing service,
// hidden in a thread factory and shared by every other task that uses this pool.
static final ExecutorService PRICING_POOL =
        Executors.newFixedThreadPool(10, Thread.ofVirtual().factory());

// MISTAKE: caching an expensive object per thread. Virtual threads are never reused,
// so every task builds its own SimpleDateFormat; with a million threads that is a
// million formatters on the heap. Share one immutable DateTimeFormatter instead.
static final ThreadLocal<SimpleDateFormat> FORMAT =
        ThreadLocal.withInitial(() -> new SimpleDateFormat("yyyy-MM-dd"));

void main() throws Exception {
    // MISTAKE: the executor is never closed. Nothing waits for these tasks, and the
    // JVM exits under them because virtual threads are daemon threads.
    ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    for (int i = 0; i < 10_000; i++) {
        int productId = i;
        executor.submit(() -> quote(productId));
    }

    List<Future<String>> futures = List.of(PRICING_POOL.submit(() -> quote(1)));
    for (Future<String> f : futures) {
        System.out.println(f.get());
    }
}

static String quote(int productId) throws Exception {
    return productId + "@" + FORMAT.get().format(new Date()) + Slow.RATES;
}

static class Slow {
    static final String RATES = load();

    static String load() {
        try {
            // MISTAKE: blocking in a class initializer still pins the carrier on JDK 24+,
            // and so does every virtual thread that waits for this class to finish
            // initializing. Load slow data lazily in a normal method, not in <clinit>.
            Thread.sleep(1_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return "";
    }
}
