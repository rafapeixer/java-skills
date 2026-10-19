import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;

// MISTAKE: a guessed limit with no arithmetic. Nobody can tell whether 100 is
// above or below what this replica and its dependencies can serve.
static final Semaphore IN_FLIGHT = new Semaphore(100);

// MISTAKE: newFixedThreadPool has an unbounded queue. Above 200 concurrent
// requests every new one waits in memory, behind work its client has already
// abandoned, until the heap or the latency budget runs out. Nothing is rejected.
static final ExecutorService REQUESTS = Executors.newFixedThreadPool(200);

// MISTAKE: the same unbounded queue for background work; a burst of 1 million
// emails becomes 1 million queued lambdas instead of a fast "try later".
static final ExecutorService EMAILS = Executors.newFixedThreadPool(4);

void main() throws Exception {
    HttpServer server = HttpServer.create(new InetSocketAddress(8080), 0);
    server.setExecutor(REQUESTS);
    server.createContext("/orders", exchange -> handle(exchange));
    server.start();
}

static void handle(HttpExchange exchange) throws java.io.IOException {
    try (exchange) {
        // MISTAKE: a blocking acquire turns the limit into a queue. When it is
        // full, requests park here with no deadline and no 503, and the server
        // keeps accepting work it cannot finish.
        IN_FLIGHT.acquireUninterruptibly();
        try {
            EMAILS.submit(() -> System.out.println("confirmation email"));
            exchange.sendResponseHeaders(200, -1);
        } finally {
            IN_FLIGHT.release();
        }
    }
}
