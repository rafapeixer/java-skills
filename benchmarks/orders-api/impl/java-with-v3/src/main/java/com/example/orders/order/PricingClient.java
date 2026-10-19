package com.example.orders.order;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Flow;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/** The only HttpClient for the pricing service, shared by every request. */
@Component
class PricingClient implements AutoCloseable {

    // Little's law: ~150 lookups/s per instance at peak x (20 ms normal + 800 ms stall) ~ 120
    // in flight; past 128 a slow pricing service gets 503s instead of an ever-growing backlog.
    static final int MAX_IN_FLIGHT = 128;
    private static final long PERMIT_WAIT_NANOS = Duration.ofMillis(20).toNanos();
    private static final Duration CALL_TIMEOUT = Duration.ofMillis(800);
    private static final long BACKOFF_CAP_NANOS = Duration.ofMillis(50).toNanos();
    private static final int MAX_ATTEMPTS = 2;
    private static final Set<Integer> RETRYABLE = Set.of(429, 502, 503, 504);
    private static final int MAX_BODY_BYTES = 4 * 1024;

    @JsonIgnoreProperties(ignoreUnknown = true) // another team's payload: tolerate additions
    record Price(String sku, Long cents) {
    }

    private final String base;
    private final JsonMapper json;
    private final HttpClient http;
    private final Semaphore permits = new Semaphore(MAX_IN_FLIGHT);

    PricingClient(@Value("${pricing.url}") String baseUrl, JsonMapper json) {
        this.base = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        URI.create(this.base); // fail at startup on a malformed PRICING_URL
        this.json = json;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(500))
                .followRedirects(HttpClient.Redirect.NEVER)
                .executor(Executors.newVirtualThreadPerTaskExecutor())
                .build();
    }

    /**
     * Price of one unit of {@code sku} in cents.
     *
     * @throws UnknownSkuException when pricing answers 404
     * @throws PricingUnavailableException on any other failure, or when the deadline passes
     */
    long cents(String sku, Deadline deadline) throws InterruptedException {
        if (!permits.tryAcquire(Math.min(PERMIT_WAIT_NANOS, deadline.remainingNanos()), TimeUnit.NANOSECONDS)) {
            throw new PricingUnavailableException("more than " + MAX_IN_FLIGHT + " pricing calls in flight");
        }
        try {
            URI uri = URI.create(base + "/prices/" + URLEncoder.encode(sku, StandardCharsets.UTF_8).replace("+", "%20"));
            for (int attempt = 1; ; attempt++) {
                boolean last = attempt == MAX_ATTEMPTS;
                long budget = Math.min(CALL_TIMEOUT.toNanos(), deadline.remainingNanos());
                if (budget <= 0) {
                    throw new PricingUnavailableException("quote deadline passed before pricing sku " + sku);
                }
                HttpRequest request = HttpRequest.newBuilder(uri)
                        .timeout(Duration.ofNanos(budget))
                        .header("Accept", "application/json")
                        .GET()
                        .build();
                CompletableFuture<HttpResponse<byte[]>> call =
                        http.sendAsync(request, info -> new LimitedBody(MAX_BODY_BYTES));
                HttpResponse<byte[]> response;
                try {
                    // On JDK 25 HttpRequest.timeout stops at the headers; get() bounds the body too.
                    response = call.get(budget, TimeUnit.NANOSECONDS);
                } catch (TimeoutException e) {
                    call.cancel(true);
                    throw new PricingUnavailableException("pricing timed out for sku " + sku, e);
                } catch (InterruptedException e) {
                    call.cancel(true);
                    throw e;
                } catch (ExecutionException e) {
                    if (!last && e.getCause() instanceof ConnectException) {
                        backoff(deadline);
                        continue;
                    }
                    throw new PricingUnavailableException("pricing call failed for sku " + sku, e.getCause());
                }
                int status = response.statusCode();
                if (status == 200) {
                    return parse(sku, response.body());
                }
                if (status == 404) {
                    throw new UnknownSkuException(sku);
                }
                if (!last && RETRYABLE.contains(status)) {
                    backoff(deadline);
                    continue;
                }
                throw new PricingUnavailableException("pricing returned " + status + " for sku " + sku);
            }
        } finally {
            permits.release();
        }
    }

    private long parse(String sku, byte[] body) {
        Price price;
        try {
            price = json.readValue(body, Price.class);
        } catch (JacksonException e) {
            throw new PricingUnavailableException("unreadable pricing response for sku " + sku, e);
        }
        if (price == null || price.cents() == null || price.cents() < 0) {
            throw new PricingUnavailableException("pricing response without valid cents for sku " + sku);
        }
        return price.cents();
    }

    /** Full jitter up to a small cap, never past the deadline. */
    private static void backoff(Deadline deadline) throws InterruptedException {
        long nanos = Math.min(ThreadLocalRandom.current().nextLong(BACKOFF_CAP_NANOS + 1), deadline.remainingNanos());
        Thread.sleep(Duration.ofNanos(nanos));
    }

    @Override
    public void close() {
        http.close();
    }

    /** Buffers a response body and fails it once it exceeds {@code max} bytes. */
    private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {

        private final int max;
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private Flow.Subscription subscription;

        LimitedBody(int max) {
            this.max = max;
        }

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            subscription.request(Long.MAX_VALUE);
        }

        @Override
        public void onNext(List<ByteBuffer> items) {
            if (result.isDone()) {
                return;
            }
            for (ByteBuffer item : items) {
                if (buffer.size() + item.remaining() > max) {
                    subscription.cancel();
                    result.completeExceptionally(new IOException("pricing response larger than " + max + " bytes"));
                    return;
                }
                byte[] bytes = new byte[item.remaining()];
                item.get(bytes);
                buffer.writeBytes(bytes);
            }
        }

        @Override
        public void onError(Throwable throwable) {
            result.completeExceptionally(throwable);
        }

        @Override
        public void onComplete() {
            result.complete(buffer.toByteArray());
        }

        @Override
        public CompletionStage<byte[]> getBody() {
            return result;
        }
    }
}
