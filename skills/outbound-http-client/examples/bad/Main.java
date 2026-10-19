import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;

public class Main {
    static final URI PRICING = URI.create("https://pricing.internal");

    static String price(String sku) throws IOException, InterruptedException {
        // MISTAKE: a new HttpClient per call; its connection pool dies with it, so every call
        // pays a TCP and TLS handshake, and each client starts its own selector thread.
        // MISTAKE: no connectTimeout and no request timeout; a stalled upstream blocks forever.
        HttpClient client = HttpClient.newBuilder()
                // MISTAKE: ALWAYS follows https -> http downgrades and any host the upstream names.
                .followRedirects(HttpClient.Redirect.ALWAYS)
                .build();
        HttpRequest request = HttpRequest.newBuilder(PRICING.resolve("/prices/" + sku)).build();
        // MISTAKE: ofString reads the whole body into memory, however large the upstream sends it.
        HttpResponse<String> response = client.send(request, BodyHandlers.ofString());
        // MISTAKE: no status check; HttpClient does not throw on 4xx/5xx, so a 404 or 500
        // error page is returned to the caller as if it were a price.
        return response.body();
    }

    static String createQuote(HttpClient client, String json) throws InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(PRICING.resolve("/quotes"))
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();
        // MISTAKE: retries a POST, forever, with no delay. A timeout does not mean the quote
        // was not created, so each retry can create another; and when the upstream is down,
        // every caller hammers it in a tight loop, which keeps it down.
        while (true) {
            try {
                HttpResponse<String> response = client.send(request, BodyHandlers.ofString());
                if (response.statusCode() < 500) {
                    return response.body();
                }
            } catch (IOException e) {
                // try again
            }
        }
    }

    static byte[] priceList(HttpClient client) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(PRICING.resolve("/prices")).build();
        HttpResponse<InputStream> response = client.send(request, BodyHandlers.ofInputStream());
        // MISTAKE: returns on an error status without closing the stream; the exchange stays
        // open and the connection is never released back to the pool.
        if (response.statusCode() != 200) {
            throw new IOException("pricing answered " + response.statusCode());
        }
        // MISTAKE: readAllBytes has no size limit, and the stream is never closed here either.
        return response.body().readAllBytes();
    }

    public static void main(String[] args) {
        System.out.println("compiles; not run");
    }
}
