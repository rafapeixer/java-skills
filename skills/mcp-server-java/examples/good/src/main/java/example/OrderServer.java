package example;

import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.DefaultServerTransportSecurityValidator;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema.ServerCapabilities;
import io.modelcontextprotocol.spec.McpServerTransportProvider;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.Duration;
import java.util.List;
import java.util.Map;

public final class OrderServer {
    private OrderServer() {}

    static McpSyncServer build(McpServerTransportProvider transport, McpJsonMapper json, OrderTools tools) {
        return McpServer.sync(transport)
                .serverInfo("orders", "1.0.0")
                .capabilities(ServerCapabilities.builder().tools(false).build())
                .tools(tools.searchOrders(json))
                .build();
    }

    /** STDIO: the client starts this process; stdin and stdout carry JSON-RPC only. */
    static McpSyncServer stdio(InputStream in, OutputStream out, OrderTools tools) {
        McpJsonMapper json = McpJsonDefaults.getMapper();
        return build(new StdioServerTransportProvider(json, in, out), json, tools);
    }

    /**
     * Streamable HTTP for remote clients. Register it as a servlet behind an OAuth 2.1
     * resource server that checks the token's audience; the extractor passes the
     * authenticated subject to the tools, so no tool trusts an identity in its arguments.
     */
    static HttpServletStreamableServerTransportProvider http(McpJsonMapper json) {
        return HttpServletStreamableServerTransportProvider.builder()
                .jsonMapper(json)
                .mcpEndpoint("/mcp")
                .securityValidator(DefaultServerTransportSecurityValidator.builder()
                        .allowedOrigins(List.of("https://agent.example.com"))
                        .allowedHosts(List.of("mcp.example.com"))
                        .build())
                .contextExtractor(request -> request.getUserPrincipal() == null
                        ? McpTransportContext.EMPTY
                        : McpTransportContext.create(Map.of("subject", request.getUserPrincipal().getName())))
                .build();
    }

    public static void main(String[] args) {
        OrderTools.OrderStore store = (status, limit) -> List.of(); // the real store goes here
        // Nothing in this process may print to System.out: one stray line corrupts the stream.
        stdio(System.in, System.out, new OrderTools(store, Duration.ofSeconds(5), 8));
    }
}
