package com.lurielabs.ordersapi.pricing;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/** The only HttpClient for the pricing service, built once and shared. */
@Component
public class PricingClient implements AutoCloseable {

    // Normal latency is ~20 ms; a call still waiting after 1 s means pricing is in trouble.
    private static final Duration CALL_CAP = Duration.ofSeconds(1);
    // Calls in flight per quote: 100 items at ~20 ms each finish in ~200 ms without opening
    // 100 connections to the pricing service for a single request.
    private static final int FAN_OUT = 10;
    private static final int MAX_BODY_BYTES = 4 * 1024;

    private final String pricesBase;
    private final JsonMapper json;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final HttpClient http;

    PricingClient(@Value("${pricing.url}") String baseUrl, JsonMapper json) {
        String base = baseUrl.strip();
        this.pricesBase = (base.endsWith("/") ? base.substring(0, base.length() - 1) : base) + "/prices/";
        URI.create(pricesBase); // fail at startup on a malformed PRICING_URL
        this.json = json;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(500))
                .followRedirects(HttpClient.Redirect.NEVER)
                .executor(executor)
                .build();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Price(String sku, Long cents) {
    }

    /**
     * Prices every SKU, at most FAN_OUT calls at a time, all bounded by the deadline. On JDK 25
     * HttpRequest.timeout does not cover the body, so each wait is Future.get(time left) and
     * every call still running when this method leaves is cancelled.
     */
    public Map<String, Long> prices(Collection<String> skus, Deadline deadline) {
        Semaphore slots = new Semaphore(FAN_OUT);
        Map<String, CompletableFuture<HttpResponse<byte[]>>> calls = new LinkedHashMap<>();
        try {
            for (String sku : skus) {
                if (!slots.tryAcquire(deadline.remainingNanos(), TimeUnit.NANOSECONDS)) {
                    throw new PricingUnavailableException("deadline exceeded waiting to price sku " + sku);
                }
                CompletableFuture<HttpResponse<byte[]>> call;
                try {
                    call = http.sendAsync(request(sku, deadline), info -> new LimitedBodySubscriber(MAX_BODY_BYTES));
                } catch (RuntimeException e) {
                    slots.release();
                    throw e;
                }
                call.whenComplete((response, error) -> slots.release());
                calls.put(sku, call);
            }
            Map<String, Long> prices = new HashMap<>();
            for (var call : calls.entrySet()) {
                HttpResponse<byte[]> response = call.getValue().get(deadline.remainingNanos(), TimeUnit.NANOSECONDS);
                prices.put(call.getKey(), parse(call.getKey(), response));
            }
            return prices;
        } catch (TimeoutException e) {
            throw new PricingUnavailableException("pricing did not answer within the deadline", e);
        } catch (ExecutionException e) {
            throw new PricingUnavailableException("pricing call failed", e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PricingUnavailableException("interrupted while waiting for pricing", e);
        } finally {
            // No-op for finished calls; aborts the exchange (and frees the connection) for the rest.
            calls.values().forEach(call -> call.cancel(true));
        }
    }

    private HttpRequest request(String sku, Deadline deadline) {
        String path = URLEncoder.encode(sku, StandardCharsets.UTF_8).replace("+", "%20");
        return HttpRequest.newBuilder(URI.create(pricesBase + path))
                .timeout(deadline.timeoutFor("pricing GET /prices/" + sku, CALL_CAP))
                .header("Accept", "application/json")
                .GET()
                .build();
    }

    private long parse(String sku, HttpResponse<byte[]> response) {
        int status = response.statusCode();
        if (status == 404) {
            throw new UnknownSkuException(sku);
        }
        if (status != 200) {
            throw new PricingUnavailableException("pricing GET /prices/" + sku + " returned " + status);
        }
        Price price;
        try {
            price = json.readValue(response.body(), Price.class);
        } catch (JacksonException e) {
            throw new PricingUnavailableException("pricing returned an unreadable body for sku " + sku, e);
        }
        if (price == null || price.cents() == null || price.cents() < 0) {
            throw new PricingUnavailableException("pricing returned no valid price for sku " + sku);
        }
        return price.cents();
    }

    @Override
    public void close() {
        http.shutdownNow();
        executor.close();
    }
}
