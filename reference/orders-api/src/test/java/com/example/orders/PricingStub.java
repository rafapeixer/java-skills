package com.example.orders;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The pricing service as SPEC.md describes it: GET /prices/{sku} answers 200 {"sku","cents"}
 * or 404. A few SKUs misbehave on purpose.
 */
public final class PricingStub {

    public static final Map<String, Long> PRICES = Map.of("A1", 1250L, "B2", 300L, "C 3", 75L);
    /** Answers after 3 s: past the 800 ms call timeout and the 1.5 s quote budget. */
    public static final String SLOW = "SLOW";
    /** Always answers 500, which is not retried. */
    public static final String BROKEN = "BROKEN";
    /** Answers 503 on the first call, then 200 with 100 cents: the one retry recovers it. */
    public static final String FLAKY = "FLAKY";

    private final HttpServer server;
    private final Map<String, AtomicInteger> hits = new ConcurrentHashMap<>();

    private PricingStub(HttpServer server) {
        this.server = server;
    }

    static PricingStub start() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            PricingStub stub = new PricingStub(server);
            server.setExecutor(Executors.newVirtualThreadPerTaskExecutor()); // a slow answer blocks nobody
            server.createContext("/prices/", stub::handle);
            server.start();
            return stub;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public String url() {
        return "http://localhost:" + server.getAddress().getPort();
    }

    public int hits(String sku) {
        AtomicInteger n = hits.get(sku);
        return n == null ? 0 : n.get();
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            String sku = exchange.getRequestURI().getPath().substring("/prices/".length());
            int hit = hits.computeIfAbsent(sku, k -> new AtomicInteger()).incrementAndGet();
            switch (sku) {
                case SLOW -> {
                    sleep(Duration.ofSeconds(3));
                    ok(exchange, sku, 1);
                }
                case BROKEN -> exchange.sendResponseHeaders(500, -1);
                case FLAKY -> {
                    if (hit == 1) {
                        exchange.sendResponseHeaders(503, -1);
                    } else {
                        ok(exchange, sku, 100);
                    }
                }
                default -> {
                    Long cents = PRICES.get(sku);
                    if (cents == null) {
                        exchange.sendResponseHeaders(404, -1);
                    } else {
                        ok(exchange, sku, cents);
                    }
                }
            }
        }
    }

    private static void ok(HttpExchange exchange, String sku, long cents) throws IOException {
        // The extra field checks that the client tolerates additions to the payload.
        byte[] body = ("{\"sku\":\"" + sku + "\",\"cents\":" + cents + ",\"currency\":\"USD\"}")
                .getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
