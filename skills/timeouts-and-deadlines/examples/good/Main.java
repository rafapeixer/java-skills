import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse.BodyHandlers;
import java.net.http.HttpTimeoutException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

public class Main {
    static final AtomicInteger hits = new AtomicInteger();

    // Every call is bounded twice: the request timeout, and Future.get with the same value.
    // On JDK 25 the request timeout stops once headers arrive; the get also covers the body.
    static String get(HttpClient client, URI uri, Deadline deadline, Duration cap) {
        String op = "GET " + uri.getPath();
        Duration timeout = deadline.timeoutFor(op, cap); // throws before sending if the budget is spent
        HttpRequest request = HttpRequest.newBuilder(uri).timeout(timeout).build();
        CompletableFuture<String> call = client.sendAsync(request, BodyHandlers.ofString())
                .thenApply(r -> r.body());
        try {
            return call.get(timeout.toNanos(), TimeUnit.NANOSECONDS);
        } catch (TimeoutException e) {
            call.cancel(true); // abort the exchange so the connection and thread are released
            throw new Deadline.Exceeded(op, timeout, e);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof HttpTimeoutException) {
                throw new Deadline.Exceeded(op, timeout, e.getCause());
            }
            throw new IllegalStateException(op + " failed", e.getCause());
        } catch (InterruptedException e) {
            call.cancel(true);
            Thread.currentThread().interrupt();
            throw new Deadline.Exceeded(op, timeout, e);
        }
    }

    // With PostgreSQL the everyday bound is server-side: statement_timeout on the role or in
    // pgJDBC's options (-c statement_timeout=5000) covers every statement at no driver cost.
    // A per-statement query timeout arms a driver timer each time, so use it only where the
    // request's remaining deadline must cut a query shorter than that server bound.
    static long countOrders(Connection c, long customerId, Deadline deadline) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("select count(*) from orders where customer_id = ?")) {
            ps.setQueryTimeout(deadline.queryTimeoutSeconds("count orders", Duration.ofSeconds(2)));
            ps.setLong(1, customerId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    public static void main(String[] args) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/fast", ex -> { hits.incrementAndGet(); reply(ex, 0, 0); });
        server.createContext("/slow-headers", ex -> { hits.incrementAndGet(); reply(ex, 2000, 0); });
        server.createContext("/slow-body", ex -> { hits.incrementAndGet(); reply(ex, 0, 2000); });
        server.start();
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build();

        // 1. The slow call is cut at what is left of a 1 s budget, not at its 5 s cap or the server's 2 s.
        Deadline request = Deadline.after(Duration.ofSeconds(1));
        long start = System.nanoTime();
        check(get(client, URI.create(base + "/fast"), request, Duration.ofSeconds(5)).equals("ok"), "fast call");
        expectExceeded(() -> get(client, URI.create(base + "/slow-headers"), request, Duration.ofSeconds(5)));
        long took = ms(start);
        check(took >= 900 && took < 1500, "slow call cut at the request budget, took " + took + " ms");

        // 2. The budget shrinks: after 300 ms of work, the next call gets what is left.
        Deadline shrinking = Deadline.after(Duration.ofMillis(600));
        Thread.sleep(300);
        long left = shrinking.timeoutFor("next call", Duration.ofSeconds(5)).toMillis();
        check(left <= 300 && left > 200, "second call timeout is the remainder, got " + left);

        // 3. A spent budget fails before any request is sent.
        Deadline spent = Deadline.after(Duration.ofMillis(20));
        Thread.sleep(30);
        int before = hits.get();
        expectExceeded(() -> get(client, URI.create(base + "/fast"), spent, Duration.ofSeconds(5)));
        check(hits.get() == before, "no request sent on a spent budget");

        // 4. A body that trickles in is cut too, on every JDK, by Future.get.
        start = System.nanoTime();
        expectExceeded(() -> get(client, URI.create(base + "/slow-body"), Deadline.after(Duration.ofMillis(500)), Duration.ofSeconds(5)));
        took = ms(start);
        check(took < 1200, "slow body cut by Future.get, took " + took + " ms");

        // 5. HttpRequest.timeout alone covers the body only from JDK 26 (JDK-8208693).
        HttpRequest plain = HttpRequest.newBuilder(URI.create(base + "/slow-body")).timeout(Duration.ofMillis(500)).build();
        boolean bodyCovered;
        try {
            client.send(plain, BodyHandlers.ofString());
            bodyCovered = false;
        } catch (HttpTimeoutException e) {
            bodyCovered = true;
        }
        check(bodyCovered == (Runtime.version().feature() >= 26), "request timeout body coverage on JDK " + Runtime.version().feature());

        // 6. JDBC gets the remaining budget in whole seconds, rounded up, never 0 (= no limit).
        int[] seen = new int[1];
        check(countOrders(fakeConnection(seen), 7, Deadline.after(Duration.ofMillis(1200))) == 42, "count");
        check(seen[0] == 2, "query timeout from a 1.2 s budget is 2 s, got " + seen[0]);
        countOrders(fakeConnection(seen), 7, Deadline.after(Duration.ofMillis(300)));
        check(seen[0] == 1, "query timeout from a 0.3 s budget is 1 s, got " + seen[0]);

        client.shutdownNow();
        server.stop(0);
        System.out.println("ok");
    }

    static void reply(com.sun.net.httpserver.HttpExchange ex, long headerDelay, long bodyDelay) throws IOException {
        try {
            Thread.sleep(headerDelay);
            ex.sendResponseHeaders(200, 0); // chunked, so the body can arrive late
            var out = ex.getResponseBody();
            out.write('o');
            out.flush();
            Thread.sleep(bodyDelay);
            out.write('k');
            out.close();
        } catch (InterruptedException | IOException e) {
            ex.close(); // the client gave up; nothing left to send
        }
    }

    // A JDBC Connection that records setQueryTimeout and returns one row with 42.
    static Connection fakeConnection(int[] seenTimeout) {
        ResultSet rs = proxy(ResultSet.class, (name, a) -> name.equals("next") ? true : name.equals("getLong") ? 42L : null);
        PreparedStatement ps = proxy(PreparedStatement.class, (name, a) -> {
            if (name.equals("setQueryTimeout")) seenTimeout[0] = (int) a[0];
            return name.equals("executeQuery") ? rs : null;
        });
        return proxy(Connection.class, (name, a) -> name.equals("prepareStatement") ? ps : null);
    }

    interface Answer { Object apply(String method, Object[] args); }

    static <T> T proxy(Class<T> type, Answer answer) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type},
                (p, m, a) -> answer.apply(m.getName(), a)));
    }

    static void expectExceeded(Runnable r) {
        try {
            r.run();
        } catch (Deadline.Exceeded expected) {
            return;
        }
        throw new AssertionError("expected Deadline.Exceeded");
    }

    static long ms(long startNanos) { return (System.nanoTime() - startNanos) / 1_000_000; }

    static void check(boolean ok, String what) { if (!ok) throw new AssertionError(what); }
}
