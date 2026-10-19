package com.example.orders;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * One PostgreSQL container, one stub pricing server and one application context for every
 * integration test: the subclasses share this configuration, so Spring caches the context.
 * The service runs as configured in application.properties; only the shutdown drain is zero,
 * so the JVM does not wait 5 s at the end of the run.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, properties = "shutdown.drain=0s")
public abstract class OrdersApiEnvironment {

    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17").withInitScript("schema.sql");
    protected static final PricingStub PRICING = PricingStub.start();
    protected static final JsonMapper JSON = JsonMapper.builder().build();
    private static final HttpClient HTTP = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();

    static {
        POSTGRES.start(); // removed by Testcontainers' Ryuk when the JVM exits
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        // A postgres:// URL, as DATABASE_URL is in production, so DatabaseConfig's parsing runs too.
        registry.add("database.url", () -> "postgres://%s:%s@%s:%d/%s".formatted(
                POSTGRES.getUsername(), POSTGRES.getPassword(), POSTGRES.getHost(),
                POSTGRES.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT), POSTGRES.getDatabaseName()));
        registry.add("pricing.url", PRICING::url);
    }

    @LocalServerPort
    protected int port;

    protected record Response(int status, HttpHeaders headers, String body) {

        public JsonNode json() {
            return JSON.readTree(body);
        }

        /** The message of an {"error": ...} body; fails if the body has another shape. */
        public String error() {
            JsonNode node = json();
            if (node.size() != 1 || !node.path("error").isString()) {
                throw new AssertionError("not an {\"error\": ...} body: " + body);
            }
            return node.get("error").asString();
        }
    }

    protected HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
    }

    protected Response get(String path) {
        return send(request(path).GET());
    }

    protected Response post(String path, String json) {
        return send(request(path).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json)));
    }

    protected Response send(HttpRequest.Builder request) {
        try {
            HttpResponse<String> response = HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
            return new Response(response.statusCode(), response.headers(), response.body());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    /** Creates an order through the API and returns its id. */
    protected long createOrder(long customerId, String itemsJson) {
        Response created = post("/orders", "{\"customer_id\":" + customerId + ",\"items\":" + itemsJson + "}");
        if (created.status() != 201) {
            throw new AssertionError("create failed: " + created.status() + " " + created.body());
        }
        return created.json().get("id").asLong();
    }
}
