package example;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.modelcontextprotocol.server.McpSyncServer;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStreamReader;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Drives the real STDIO transport in process: JSON-RPC lines in, JSON-RPC lines out. */
class OrderServerTest {
    static final ObjectMapper JSON = new ObjectMapper();
    final AtomicInteger storeCalls = new AtomicInteger();
    final AtomicBoolean interrupted = new AtomicBoolean();
    final CountDownLatch slowStarted = new CountDownLatch(1);
    final ByteArrayOutputStream stray = new ByteArrayOutputStream();
    PrintStream realOut;
    PipedOutputStream toServer;
    BufferedReader fromServer;
    McpSyncServer server;
    int nextId = 1;

    @BeforeEach
    void start() throws Exception {
        realOut = System.out;
        System.setOut(new PrintStream(stray, true, StandardCharsets.UTF_8)); // catches any stray print
        OrderTools.OrderStore store = (status, limit) -> {
            storeCalls.incrementAndGet();
            if (status.equals("pending")) {
                slowStarted.countDown();
                try {
                    Thread.sleep(10_000);
                } catch (InterruptedException e) {
                    interrupted.set(true);
                    throw e;
                }
            }
            if (status.equals("cancelled")) {
                throw new IllegalStateException("relation \"orders_v2\" does not exist");
            }
            return List.of("o-1", "o-2").subList(0, Math.min(limit, 2));
        };
        toServer = new PipedOutputStream();
        var serverIn = new PipedInputStream(toServer, 1 << 16);
        var serverOut = new PipedOutputStream();
        fromServer = new BufferedReader(new InputStreamReader(new PipedInputStream(serverOut, 1 << 16), StandardCharsets.UTF_8));
        server = OrderServer.stdio(serverIn, serverOut, new OrderTools(store, Duration.ofMillis(300), 4));
        JsonNode init = call("initialize", """
                {"protocolVersion": "2025-11-25", "capabilities": {}, "clientInfo": {"name": "test", "version": "1"}}""");
        assertEquals("orders", init.at("/result/serverInfo/name").asString());
        send("{\"jsonrpc\": \"2.0\", \"method\": \"notifications/initialized\"}");
    }

    @AfterEach
    void stop() {
        server.closeGracefully();
        System.setOut(realOut);
        assertEquals("", stray.toString(StandardCharsets.UTF_8), "something wrote to System.out");
    }

    @Test
    void validCallReturnsStructuredContent() throws Exception {
        JsonNode result = callTool("{\"status\": \"shipped\", \"limit\": 5}");
        assertFalse(result.path("isError").asBoolean());
        assertEquals("o-1", result.at("/structuredContent/orderIds/0").asString());
    }

    @Test
    void schemaRejectsBadArgumentsBeforeTheHandlerRuns() throws Exception {
        for (String args : List.of("{\"status\": \"shipped\", \"limit\": 500}",
                "{\"status\": \"lost\", \"limit\": 5}",
                "{\"status\": \"shipped\", \"limit\": 5, \"owner\": \"bob\"}",
                "{\"limit\": 5}")) {
            JsonNode result = callTool(args);
            assertTrue(result.path("isError").asBoolean(), args);
        }
        assertEquals(0, storeCalls.get());
    }

    @Test
    void slowWorkTimesOutAndIsInterrupted() throws Exception {
        long start = System.nanoTime();
        JsonNode result = callTool("{\"status\": \"pending\", \"limit\": 5}");
        assertTrue(result.path("isError").asBoolean());
        assertTrue(result.at("/content/0/text").asString().contains("timed out"));
        assertTrue(slowStarted.await(1, TimeUnit.SECONDS));
        assertTrue(System.nanoTime() - start < TimeUnit.SECONDS.toNanos(5));
        for (int i = 0; i < 50 && !interrupted.get(); i++) {
            Thread.sleep(20);
        }
        assertTrue(interrupted.get(), "the store call kept running after the timeout");
    }

    @Test
    void storeFailureIsAToolErrorWithoutInternals() throws Exception {
        JsonNode result = callTool("{\"status\": \"cancelled\", \"limit\": 5}");
        assertTrue(result.path("isError").asBoolean());
        String text = result.at("/content/0/text").asString();
        assertEquals("order store unavailable, retry later", text);
    }

    private JsonNode callTool(String arguments) throws Exception {
        JsonNode response = call("tools/call", "{\"name\": \"search_orders\", \"arguments\": " + arguments + "}");
        assertTrue(response.has("result"), response.toString());
        return response.get("result");
    }

    private JsonNode call(String method, String params) throws Exception {
        int id = nextId++;
        send("{\"jsonrpc\": \"2.0\", \"id\": " + id + ", \"method\": \"" + method + "\", \"params\": " + params + "}");
        while (true) {
            String line = fromServer.readLine();
            JsonNode message = JSON.readTree(line); // every stdout line must be one JSON-RPC message
            assertEquals("2.0", message.path("jsonrpc").asString());
            if (message.path("id").asInt(-1) == id) {
                return message;
            }
        }
    }

    private void send(String line) throws Exception {
        toServer.write((line.replace("\n", " ") + "\n").getBytes(StandardCharsets.UTF_8));
        toServer.flush();
    }
}
