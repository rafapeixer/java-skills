package com.example.orders;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Client for the pricing service. Every call is bounded by a caller-supplied deadline, a
 * per-attempt timeout, and a process-wide concurrency cap (bulkhead) so a slow pricing service
 * cannot tie up unbounded resources here or be hammered harder when it is already struggling.
 */
@Component
class PricingClient {

    private static final int MAX_ATTEMPTS = 2;

    private final String baseUrl;
    private final Duration requestTimeout;
    private final Semaphore bulkhead;
    private final HttpClient http;
    private final JsonMapper json = JsonMapper.builder().build();

    PricingClient(
            @Value("${PRICING_URL}") String pricingUrl,
            @Value("${PRICING_CONNECT_TIMEOUT_MS:500}") long connectTimeoutMs,
            @Value("${PRICING_REQUEST_TIMEOUT_MS:1000}") long requestTimeoutMs,
            @Value("${PRICING_MAX_CONCURRENCY:64}") int maxConcurrency) {
        if (pricingUrl == null || pricingUrl.isBlank()) {
            throw new IllegalArgumentException("PRICING_URL is not set");
        }
        this.baseUrl = pricingUrl.endsWith("/") ? pricingUrl.substring(0, pricingUrl.length() - 1) : pricingUrl;
        URI.create(this.baseUrl); // fail fast on a malformed URL
        this.requestTimeout = Duration.ofMillis(requestTimeoutMs);
        this.bulkhead = new Semaphore(maxConcurrency);
        this.http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofMillis(connectTimeoutMs))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    /**
     * Returns the unit price of {@code sku} in cents.
     *
     * @param deadlineNanos absolute {@link System#nanoTime()} deadline for the whole lookup
     * @throws UnknownSkuException if the pricing service returns 404
     * @throws PricingUnavailableException on errors, timeouts, or when over the concurrency cap
     */
    long priceCents(String sku, long deadlineNanos) throws InterruptedException {
        if (!bulkhead.tryAcquire(deadlineNanos - System.nanoTime(), TimeUnit.NANOSECONDS)) {
            throw new PricingUnavailableException("pricing service is overloaded");
        }
        try {
            PricingUnavailableException failure = new PricingUnavailableException("pricing service timed out");
            for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
                long remaining = deadlineNanos - System.nanoTime();
                if (remaining <= 0) {
                    break;
                }
                try {
                    return fetch(sku, Duration.ofNanos(Math.min(requestTimeout.toNanos(), remaining)));
                } catch (PricingUnavailableException e) {
                    failure = e;
                    if (!e.retryable()) {
                        break;
                    }
                }
            }
            throw failure;
        } finally {
            bulkhead.release();
        }
    }

    private long fetch(String sku, Duration timeout) throws InterruptedException {
        // URLEncoder does form encoding; a path segment needs %20 for spaces. '/' is encoded too.
        String encodedSku = URLEncoder.encode(sku, StandardCharsets.UTF_8).replace("+", "%20");
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/prices/" + encodedSku))
                .timeout(timeout)
                .header("Accept", "application/json")
                .GET()
                .build();
        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (HttpTimeoutException e) {
            throw new PricingUnavailableException("pricing service timed out", true);
        } catch (IOException e) {
            throw new PricingUnavailableException("pricing service unreachable", true);
        }
        return switch (response.statusCode()) {
            case 200 -> parseCents(response.body());
            case 404 -> throw new UnknownSkuException(sku);
            // 5xx may be a transient blip; anything else (e.g. 429) should not be retried.
            default -> throw new PricingUnavailableException(
                    "pricing service returned " + response.statusCode(), response.statusCode() >= 500);
        };
    }

    private long parseCents(String body) {
        try {
            JsonNode cents = json.readTree(body).get("cents");
            if (cents == null || !(cents.isInt() || cents.isLong()) ||cents.longValue() < 0) {
                throw new PricingUnavailableException("pricing service returned an invalid price");
            }
            return cents.longValue();
        } catch (JacksonException e) {
            throw new PricingUnavailableException("pricing service returned invalid JSON");
        }
    }
}
