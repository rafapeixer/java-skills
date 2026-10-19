package example;

import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.ServerCapabilities;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;

public final class OrderServer {
    public static void main(String[] args) {
        McpJsonMapper json = McpJsonDefaults.getMapper();
        // MISTAKE: a print on stdout with the STDIO transport. The client reads this line as a
        // JSON-RPC message, fails to parse it and drops the connection or the response.
        System.out.println("orders MCP server starting...");

        // MISTAKE: a tool that runs whatever SQL the model sends, with an empty schema.
        // One prompt injection in a README or a ticket becomes "delete from orders".
        Tool tool = Tool.builder()
                .name("run_sql")
                .description("Runs a SQL query against the orders database.")
                .inputSchema(json, "{\"type\": \"object\"}")
                .build();
        SyncToolSpecification runSql = SyncToolSpecification.builder()
                .tool(tool)
                .callHandler((exchange, request) -> {
                    String sql = (String) request.arguments().get("query");
                    System.out.println("running " + sql); // MISTAKE: debug output on stdout again
                    // MISTAKE: no timeout and no concurrency cap. A slow query holds the call,
                    // and a model that retries piles up connections until the database refuses.
                    try (Connection c = DriverManager.getConnection(System.getenv("DB_URL"));
                            ResultSet rs = c.createStatement().executeQuery(sql)) {
                        StringBuilder out = new StringBuilder();
                        while (rs.next()) {
                            out.append(rs.getString(1)).append('\n');
                        }
                        return CallToolResult.builder().addTextContent(out.toString()).build();
                    } catch (SQLException e) {
                        // MISTAKE: thrown out of the handler, so the SDK answers with a JSON-RPC
                        // error the model may never see, and the message leaks table names.
                        throw new IllegalStateException(e.getMessage(), e);
                    }
                })
                .build();

        McpServer.sync(new StdioServerTransportProvider(json))
                .serverInfo("orders", "1.0.0")
                .capabilities(ServerCapabilities.builder().tools(false).build())
                .validateToolInputs(false) // MISTAKE: turns off the SDK's schema check
                .tools(runSql)
                .build();
    }
}
