import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse.BodyHandlers;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

// The request handler has a 2 s budget (the load balancer gives up at 2 s).
public class Main {
    // MISTAKE: no connectTimeout; a SYN to a dead host waits for the OS TCP timeout
    // (often more than a minute) while the request it belongs to was abandoned long ago.
    static final HttpClient client = HttpClient.newHttpClient();
    static final ExecutorService pool = Executors.newFixedThreadPool(8);

    static String price(String sku) throws Exception {
        // MISTAKE: no HttpRequest.timeout; the default is "block forever", so one stuck
        // upstream holds this thread and its connection until someone restarts the service.
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://pricing.internal/prices/" + sku)).build();
        return client.send(request, BodyHandlers.ofString()).body();
    }

    static String stock(String sku) throws Exception {
        // MISTAKE: a fixed 5 s per call inside a 2 s request; the caller is gone after 2 s
        // and this call keeps a connection busy for 3 more seconds doing useless work.
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://stock.internal/stock/" + sku))
                .timeout(Duration.ofSeconds(5))
                .build();
        return client.send(request, BodyHandlers.ofString()).body();
    }

    static String quote(Connection c, String sku) throws Exception {
        // MISTAKE: Future.get() with no timeout; it waits as long as the slowest task.
        Future<String> p = pool.submit(() -> price(sku));
        String priced = p.get();

        String inStock;
        try {
            CompletableFuture<String> s = CompletableFuture.supplyAsync(() -> {
                try {
                    return stock(sku);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
            inStock = s.get(1, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            // MISTAKE: retrying after a timeout with a fresh full timeout and without
            // cancelling the first call; two calls now run, and the request budget is ignored.
            inStock = stock(sku);
        }
        return priced + "/" + inStock + "/" + reservations(c, sku);
    }

    static long reservations(Connection c, String sku) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("select count(*) from reservations where sku = ?")) {
            // MISTAKE: no setQueryTimeout; a query waiting on a row lock runs until the lock
            // is released, holding a pooled connection that other requests are queuing for.
            ps.setString(1, sku);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    static void reserve(Connection c, String sku) throws Exception {
        try (PreparedStatement ps = c.prepareStatement("update stock set reserved = reserved + 1 where sku = ?")) {
            // MISTAKE: the remaining budget in milliseconds divided down to seconds; 800 ms
            // becomes 0, and 0 means "no limit" in JDBC.
            long remainingMillis = 800;
            ps.setQueryTimeout((int) (remainingMillis / 1000));
            ps.setString(1, sku);
            ps.executeUpdate();
        }
    }

    public static void main(String[] args) {
        System.out.println("compiles; not run");
    }
}
