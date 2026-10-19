package example;

import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** One narrow, read-only tool. Arguments come from a model, so they are untrusted input. */
public final class OrderTools {
    private static final Logger log = LoggerFactory.getLogger(OrderTools.class);

    public interface OrderStore {
        List<String> search(String status, int limit) throws Exception;
    }

    // The SDK validates every call against this schema before the handler runs
    // (validateToolInputs defaults to true): enum, bounds, required, no extra keys.
    static final String SEARCH_SCHEMA = """
            {"type": "object",
             "properties": {
               "status": {"type": "string", "enum": ["pending", "shipped", "delivered", "cancelled"]},
               "limit": {"type": "integer", "minimum": 1, "maximum": 50}},
             "required": ["status", "limit"],
             "additionalProperties": false}""";

    private final OrderStore store;
    private final Duration timeout;
    private final Semaphore inFlight;
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();

    public OrderTools(OrderStore store, Duration timeout, int maxConcurrent) {
        this.store = store;
        this.timeout = timeout;
        this.inFlight = new Semaphore(maxConcurrent);
    }

    public SyncToolSpecification searchOrders(McpJsonMapper json) {
        Tool tool = Tool.builder()
                .name("search_orders")
                .description("Lists order ids with the given status, newest first.")
                .inputSchema(json, SEARCH_SCHEMA)
                .build();
        return SyncToolSpecification.builder()
                .tool(tool)
                .callHandler((exchange, request) -> search(request))
                .build();
    }

    CallToolResult search(CallToolRequest request) {
        String status = (String) request.arguments().get("status");
        int limit = ((Number) request.arguments().get("limit")).intValue();
        if (!inFlight.tryAcquire()) {
            return error("busy: too many concurrent searches, retry shortly");
        }
        try {
            Future<List<String>> work = workers.submit(() -> store.search(status, limit));
            try {
                List<String> ids = work.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
                return CallToolResult.builder()
                        .addTextContent(String.join(",", ids))
                        .structuredContent(Map.of("orderIds", ids))
                        .build();
            } catch (TimeoutException e) {
                work.cancel(true); // interrupts the query instead of letting it run on
                return error("search timed out after " + timeout.toMillis() + " ms, narrow the query");
            } catch (ExecutionException e) {
                log.warn("search_orders failed", e.getCause()); // details go to stderr only
                return error("order store unavailable, retry later");
            } catch (InterruptedException e) {
                work.cancel(true);
                Thread.currentThread().interrupt();
                return error("search cancelled");
            }
        } finally {
            inFlight.release();
        }
    }

    /** A tool error (isError: true) the model can read and act on, not a protocol error. */
    private static CallToolResult error(String message) {
        return CallToolResult.builder().addTextContent(message).isError(true).build();
    }
}
