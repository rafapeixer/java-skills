package com.example.ordersapi.pricing;

import com.example.ordersapi.Deadline;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.net.ConnectException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/** Client for the pricing service: {@code GET {PRICING_URL}/prices/{sku}}. One shared HttpClient. */
@Component
public class PricingClient implements AutoCloseable {

    /**
     * Per-replica bound on in-flight pricing calls, so a slow pricing service cannot hold an
     * unbounded number of sockets and virtual threads. Little's law, measured: a replica
     * serves up to about 6,000 req/s, 15% of them quotes with about 3 SKUs, so 2,700 calls/s;
     * at 30 ms that is about 80 in flight, and a 100 ms stall (GC pause, JIT, CPU saturation)
     * adds 270 more. 200 was hit in exactly those bursts and turned them into 503s; 512 covers
     * them. With pricing degraded (1 s cap) the 1.5 s slow-pricing test behaved the same with
     * 200 and 512; the request admission limit (256) bounds quotes as well. Beyond this, fail
     * fast with 503 rather than queue.
     */
    static final int MAX_IN_FLIGHT = 512;

    /** Pricing normally answers in ~20 ms; a second means it is broken for this call. */
    private static final Duration CALL_CAP = Duration.ofSeconds(1);
    private static final int MAX_BODY_BYTES = 16 * 1024;
    private static final int MAX_ATTEMPTS = 2;

    @JsonIgnoreProperties(ignoreUnknown = true) // another team's payload: new fields must not break us
    record Price(@Nullable String sku, @Nullable Long cents) {}

    /** Failure that says nothing about the request itself, so a GET may be retried. */
    private static final class RetryableFailure extends Exception {
        RetryableFailure(String message, @Nullable Throwable cause) {
            super(message, cause);
        }
    }

    private final Semaphore inFlight = new Semaphore(MAX_IN_FLIGHT);
    private final HttpClient http;
    private final String baseUrl;
    private final JsonMapper json;

    PricingClient(@Value("${pricing.url}") String pricingUrl, JsonMapper json) {
        URI base = URI.create(pricingUrl);
        if (!"http".equals(base.getScheme()) && !"https".equals(base.getScheme())) {
            throw new IllegalArgumentException("PRICING_URL must be an http or https URL");
        }
        this.baseUrl = pricingUrl.endsWith("/") ? pricingUrl.substring(0, pricingUrl.length() - 1) : pricingUrl;
        this.json = json;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(500))
                .followRedirects(HttpClient.Redirect.NEVER)
                // Plain HTTP/1.1: no h2c upgrade attempt against an internal http:// service.
                .version(HttpClient.Version.HTTP_1_1)
                // The client's own async tasks (response parsing, completing the future) run on
                // this executor. The default is a cached pool of platform threads; with virtual
                // threads every hand-off back to the caller is cheaper (measured -16% CPU/request).
                .executor(Executors.newVirtualThreadPerTaskExecutor())
                .build();
    }

    /** Price of one unit of {@code sku} in cents, within the request's deadline. */
    public long unitPriceCents(String sku, Deadline deadline) {
        if (!inFlight.tryAcquire()) {
            throw new PricingUnavailableException("pricing concurrency limit reached");
        }
        try {
            for (int attempt = 1; ; attempt++) {
                try {
                    return attempt(sku, deadline);
                } catch (RetryableFailure e) {
                    if (attempt == MAX_ATTEMPTS) {
                        throw new PricingUnavailableException(e.getMessage(), e.getCause());
                    }
                    backoff(deadline);
                }
            }
        } finally {
            inFlight.release();
        }
    }

    private long attempt(String sku, Deadline deadline) throws RetryableFailure {
        Duration timeout = deadline.timeoutFor("pricing lookup", CALL_CAP);
        HttpRequest request = HttpRequest.newBuilder(uriFor(sku))
                .timeout(timeout)
                .header("Accept", "application/json")
                .GET()
                .build();
        // On JDK 25 HttpRequest.timeout stops at the headers; get(timeout) bounds the body too.
        CompletableFuture<HttpResponse<byte[]>> call =
                http.sendAsync(request, info -> new LimitedBodySubscriber(MAX_BODY_BYTES));
        HttpResponse<byte[]> response;
        try {
            response = call.get(timeout.toNanos(), TimeUnit.NANOSECONDS);
        } catch (TimeoutException e) {
            call.cancel(true); // abort the exchange so its connection is released
            throw new PricingUnavailableException("pricing did not answer within " + timeout.toMillis() + " ms", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            call.cancel(true);
            throw new PricingUnavailableException("interrupted while calling pricing", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof ConnectException || cause instanceof HttpConnectTimeoutException) {
                throw new RetryableFailure("cannot connect to pricing", cause);
            }
            throw new PricingUnavailableException("pricing call failed", cause);
        }

        int status = response.statusCode();
        return switch (status) {
            case 200 -> parse(sku, response.body());
            case 404 -> throw new UnknownSkuException(sku);
            case 429, 502, 503, 504 -> throw new RetryableFailure("pricing answered " + status, null);
            default -> throw new PricingUnavailableException("pricing answered " + status);
        };
    }

    private long parse(String sku, byte[] body) {
        Price price;
        try {
            price = json.readValue(body, Price.class);
        } catch (JacksonException e) {
            throw new PricingUnavailableException("pricing returned invalid JSON for sku " + sku, e);
        }
        if (price.cents() == null || price.cents() < 0) {
            throw new PricingUnavailableException("pricing returned no valid price for sku " + sku);
        }
        return price.cents();
    }

    /** Full-jitter wait before the single retry, never past the deadline. */
    private static void backoff(Deadline deadline) {
        long maxMillis = Math.min(50, deadline.remaining().toMillis() / 2);
        if (maxMillis <= 0) {
            return; // the next attempt's timeoutFor reports the spent deadline
        }
        try {
            Thread.sleep(ThreadLocalRandom.current().nextLong(maxMillis + 1));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PricingUnavailableException("interrupted while waiting to retry pricing", e);
        }
    }

    private URI uriFor(String sku) {
        // URLEncoder is form encoding: '+' would be a literal plus in a path segment.
        String segment = URLEncoder.encode(sku, StandardCharsets.UTF_8).replace("+", "%20");
        return URI.create(baseUrl + "/prices/" + segment);
    }

    @Override
    public void close() {
        http.close();
    }
}
