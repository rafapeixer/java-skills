import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

// Little's law: the replica peaks at 40 req/s and a request takes 50 ms, so
// 40 × 0.05 = 2 requests are in flight on average; 4 doubles that for bursts.
// Above 4 in flight the replica is overloaded, and the excess is rejected at once.
static final int MAX_IN_FLIGHT = 4;
static final Semaphore admission = new Semaphore(MAX_IN_FLIGHT);

static final CountDownLatch admitted = new CountDownLatch(MAX_IN_FLIGHT);
static final CountDownLatch slowDependency = new CountDownLatch(1);

void main() throws Exception {
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
    server.createContext("/orders", exchange -> handle(exchange));
    server.start();
    try (var client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()) {
        var request = HttpRequest.newBuilder(
                URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/orders")).build();

        // Fill every slot with requests stuck on a slow dependency.
        List<CompletableFuture<HttpResponse<String>>> stuck = new ArrayList<>();
        for (int i = 0; i < MAX_IN_FLIGHT; i++) {
            stuck.add(client.sendAsync(request, HttpResponse.BodyHandlers.ofString()));
        }
        check(admitted.await(30, TimeUnit.SECONDS), "slots never filled");

        // 50 more requests arrive while the replica is full: each must get a 503
        // while the admitted ones are still blocked, so nothing queued behind them.
        List<CompletableFuture<HttpResponse<String>>> excess = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            excess.add(client.sendAsync(request, HttpResponse.BodyHandlers.ofString()));
        }
        CompletableFuture.allOf(excess.toArray(CompletableFuture[]::new)).get(30, TimeUnit.SECONDS);
        for (var f : excess) {
            HttpResponse<String> r = f.join();
            check(r.statusCode() == 503, "expected 503, got " + r.statusCode());
            check(r.headers().firstValue("Retry-After").equals(java.util.Optional.of("1")), "no Retry-After");
        }
        check(stuck.stream().noneMatch(CompletableFuture::isDone), "rejections waited for a slot");

        // The dependency recovers: admitted requests finish and the permits come back.
        slowDependency.countDown();
        for (var f : stuck) {
            check(f.get(30, TimeUnit.SECONDS).statusCode() == 200, "admitted request failed");
        }
        check(admission.availablePermits() == MAX_IN_FLIGHT, "a permit leaked");
    } finally {
        server.stop(0);
    }

    checkBoundedQueue();
    System.out.println("ok: 50 requests shed with 503 while " + MAX_IN_FLIGHT + " were in flight");
}

static void handle(HttpExchange exchange) throws java.io.IOException {
    try (exchange) {
        if (!admission.tryAcquire()) {
            exchange.getResponseHeaders().set("Retry-After", "1");
            exchange.sendResponseHeaders(503, -1); // costs microseconds, holds nothing
            return;
        }
        try {
            admitted.countDown();
            slowDependency.await(60, TimeUnit.SECONDS); // stands in for the real work
            byte[] body = "order 1".getBytes();
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            exchange.sendResponseHeaders(500, -1);
        } finally {
            admission.release();
        }
    }
}

// Background work: a fixed number of workers and a bounded queue that rejects
// when full, instead of Executors.newFixedThreadPool's unbounded queue.
static void checkBoundedQueue() throws Exception {
    var release = new CountDownLatch(1);
    var executor = new ThreadPoolExecutor(2, 2, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(10), new ThreadPoolExecutor.AbortPolicy());
    int rejected = 0;
    for (int i = 0; i < 100; i++) {
        try {
            executor.execute(() -> await(release));
        } catch (RejectedExecutionException e) {
            rejected++; // shed: tell the caller now, e.g. 503 or nack the message
        }
    }
    check(rejected == 88, "2 running + 10 queued should leave 88 rejected, got " + rejected);
    check(executor.getQueue().size() == 10, "queue grew past its bound");
    release.countDown();
    executor.shutdown();
    check(executor.awaitTermination(30, TimeUnit.SECONDS), "workers did not finish");
}

static void await(CountDownLatch latch) {
    try {
        latch.await();
    } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
    }
}

static void check(boolean ok, String what) {
    if (!ok) {
        throw new AssertionError(what);
    }
}
