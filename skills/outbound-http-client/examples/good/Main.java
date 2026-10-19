import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

public class Main {
    static final Set<Integer> clientPorts = ConcurrentHashMap.newKeySet();
    static final AtomicInteger hits = new AtomicInteger();

    public static void main(String[] args) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        AtomicInteger flaky = new AtomicInteger();
        AtomicInteger throttled = new AtomicInteger();
        server.createContext("/prices/ok", ex -> reply(ex, 200, "{\"cents\":1299}"));
        server.createContext("/prices/flaky", ex -> reply(ex, flaky.incrementAndGet() <= 2 ? 503 : 200, "{}"));
        server.createContext("/prices/down", ex -> reply(ex, 503, "busy"));
        server.createContext("/prices/missing", ex -> reply(ex, 404, "no such sku"));
        server.createContext("/prices/huge", ex -> reply(ex, 200, "x".repeat(1 << 20)));
        server.createContext("/prices/throttled", ex -> {
            if (throttled.incrementAndGet() == 1) ex.getResponseHeaders().set("Retry-After", "1");
            reply(ex, throttled.get() == 1 ? 429 : 200, "{}");
        });
        server.createContext("/prices/slow", ex -> {
            sleep(2000);
            reply(ex, 200, "{}");
        });
        server.createContext("/quotes", ex -> reply(ex, 503, "busy"));
        server.start();
        PricingClient pricing = new PricingClient(URI.create("http://127.0.0.1:" + server.getAddress().getPort()));
        Duration timeout = Duration.ofMillis(300);

        // 1. One shared client: 20 calls, error responses included, reuse one connection.
        for (int i = 0; i < 10; i++) {
            check(pricing.price("ok", timeout).contains("1299"), "price");
            expectStatus(404, () -> pricing.price("missing", timeout));
        }
        check(clientPorts.size() == 1, "20 calls should share 1 connection, used " + clientPorts.size());
        // The client's own work runs on virtual threads, not on a platform thread pool.
        var onVirtual = new java.util.concurrent.CompletableFuture<Boolean>();
        pricing.http.executor().orElseThrow().execute(() -> onVirtual.complete(Thread.currentThread().isVirtual()));
        check(onVirtual.get(5, java.util.concurrent.TimeUnit.SECONDS), "HttpClient executor is not virtual-thread based");

        // 2. A GET that fails twice with 503 succeeds on the third attempt.
        hits.set(0);
        pricing.price("flaky", timeout);
        check(hits.get() == 3, "flaky GET took " + hits.get() + " attempts");

        // 3. Retries are bounded: an upstream that stays down gets exactly MAX_ATTEMPTS calls.
        hits.set(0);
        expectStatus(503, () -> pricing.price("down", timeout));
        check(hits.get() == PricingClient.MAX_ATTEMPTS, "down GET took " + hits.get() + " attempts");

        // 4. A POST is never retried: one call, and the 503 reaches the caller.
        hits.set(0);
        expectStatus(503, () -> pricing.createQuote("{\"sku\":\"ok\"}", timeout));
        check(hits.get() == 1, "POST sent " + hits.get() + " times");

        // 5. Retry-After from the server is honored.
        long start = System.nanoTime();
        pricing.price("throttled", timeout);
        check(ms(start) >= 1000, "Retry-After: 1 should delay the retry by 1 s");

        // 6. The body is bounded: 1 MiB from a buggy upstream is an error, not 1 MiB in the heap.
        try {
            pricing.price("huge", timeout);
            throw new AssertionError("expected oversized body to fail");
        } catch (IOException e) {
            check(e.getMessage().contains("larger than"), "oversized: " + e.getMessage());
        }

        // 7. The request timeout fires per attempt; three timed-out attempts end well before 2 s each.
        start = System.nanoTime();
        try {
            pricing.price("slow", timeout);
            throw new AssertionError("expected timeout");
        } catch (HttpTimeoutException expected) {
            check(ms(start) < 1900, "timeouts took " + ms(start) + " ms");
        }

        // 8. Backoff stays within [0, cap] and grows with the attempt.
        for (int attempt = 1; attempt <= 6; attempt++) {
            long d = PricingClient.backoff(attempt, null).toMillis();
            long ceiling = Math.min(1000, 50L << (attempt - 1));
            check(d >= 0 && d <= ceiling, "backoff " + d + " for attempt " + attempt);
        }

        server.stop(0);
        System.out.println("ok");
    }

    static void reply(HttpExchange ex, int status, String body) throws IOException {
        hits.incrementAndGet();
        clientPorts.add(ex.getRemoteAddress().getPort());
        byte[] bytes = body.getBytes();
        ex.sendResponseHeaders(status, bytes.length);
        try (var out = ex.getResponseBody()) {
            out.write(bytes);
        } catch (IOException e) {
            // the client stopped reading (oversized body); expected
        }
    }

    interface Call { Object run() throws Exception; }

    static void expectStatus(int status, Call call) throws Exception {
        try {
            call.run();
            throw new AssertionError("expected status " + status);
        } catch (PricingClient.UpstreamException e) {
            check(e.status == status, "expected " + status + ", got " + e.status);
        }
    }

    static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    static long ms(long startNanos) { return (System.nanoTime() - startNanos) / 1_000_000; }

    static void check(boolean ok, String what) { if (!ok) throw new AssertionError(what); }
}
