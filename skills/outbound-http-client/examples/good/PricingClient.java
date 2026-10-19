import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;

/** The only client for the pricing service. Built once at startup, shared by every request. */
public final class PricingClient {
    static final int MAX_BODY = 64 * 1024; // largest legitimate response, plus margin
    static final int MAX_ATTEMPTS = 3;
    static final Duration BACKOFF_BASE = Duration.ofMillis(50);
    static final Duration BACKOFF_CAP = Duration.ofSeconds(1);
    static final Set<Integer> RETRYABLE = Set.of(429, 502, 503, 504);

    final HttpClient http;
    private final URI base;

    public PricingClient(URI base) {
        this.base = base;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(1)) // bounds only TCP (and TLS) setup
                .followRedirects(HttpClient.Redirect.NEVER) // the default; stated so nobody "fixes" it
                // The default executor is a platform thread pool that runs the client's
                // response handling; virtual threads keep that work on the caller's scheduler.
                .executor(Executors.newVirtualThreadPerTaskExecutor())
                .build();
    }

    /** GET is idempotent, so it is retried on failures that say nothing about the request itself. */
    public String price(String sku, Duration timeout) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(base.resolve("/prices/" + sku))
                .timeout(timeout) // bounds the response; on JDK 25 only until headers arrive
                .GET()
                .build();
        return bodyOf(send(request, true));
    }

    /** POST creates a quote; sent once, because a retry after a timeout can create two. */
    public String createQuote(String json, Duration timeout) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(base.resolve("/quotes"))
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();
        return bodyOf(send(request, false));
    }

    private HttpResponse<InputStream> send(HttpRequest request, boolean idempotent)
            throws IOException, InterruptedException {
        for (int attempt = 1; ; attempt++) {
            boolean last = !idempotent || attempt == MAX_ATTEMPTS;
            try {
                HttpResponse<InputStream> response = http.send(request, BodyHandlers.ofInputStream());
                if (last || !RETRYABLE.contains(response.statusCode())) {
                    return response;
                }
                response.body().close(); // release the exchange before waiting
                Thread.sleep(backoff(attempt, retryAfter(response)));
            } catch (HttpTimeoutException | ConnectException e) {
                if (last) {
                    throw e;
                }
                Thread.sleep(backoff(attempt, null));
            }
        }
    }

    /** Exponential backoff with full jitter, capped; a server's Retry-After wins when it is longer. */
    static Duration backoff(int attempt, Duration retryAfter) {
        long ceiling = Math.min(BACKOFF_CAP.toMillis(), BACKOFF_BASE.toMillis() << (attempt - 1));
        Duration jittered = Duration.ofMillis(ThreadLocalRandom.current().nextLong(ceiling + 1));
        if (retryAfter != null && retryAfter.compareTo(jittered) > 0) {
            return retryAfter.compareTo(BACKOFF_CAP) < 0 ? retryAfter : BACKOFF_CAP;
        }
        return jittered;
    }

    private static Duration retryAfter(HttpResponse<?> response) {
        return response.headers().firstValue("Retry-After")
                .filter(v -> v.chars().allMatch(Character::isDigit))
                .map(v -> Duration.ofSeconds(Long.parseLong(v)))
                .orElse(null);
    }

    /** Reads at most MAX_BODY bytes and always closes the body, on success and on error. */
    private static String bodyOf(HttpResponse<InputStream> response) throws IOException {
        try (InputStream in = response.body()) {
            byte[] bytes = in.readNBytes(MAX_BODY + 1);
            if (bytes.length > MAX_BODY) {
                throw new IOException("pricing response larger than " + MAX_BODY + " bytes");
            }
            if (response.statusCode() != 200 && response.statusCode() != 201) {
                // HttpClient does not throw on 4xx/5xx; the status is ours to check.
                throw new UpstreamException(response.statusCode(), response.request().uri().getPath());
            }
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }

    public static final class UpstreamException extends IOException {
        final int status;

        UpstreamException(int status, String path) {
            super("pricing " + path + " answered " + status);
            this.status = status;
        }
    }
}
