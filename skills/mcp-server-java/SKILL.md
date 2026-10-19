---
name: mcp-server-java
description: Use when writing or reviewing a Model Context Protocol (MCP) server in Java that exposes a service's data or actions as tools to coding agents and LLM clients, with the official MCP Java SDK (io.modelcontextprotocol.sdk) or Spring AI's MCP server starters, choosing STDIO or Streamable HTTP, writing tool input schemas, seeing a client fail to parse the server's output, or securing an HTTP MCP endpoint. Key rule: the official SDK (mcp 2.1.0) or Spring AI 2.0 starters; narrow intent-level tools (search_orders), never run_sql or run_command; a JSON schema with enum, bounds, required and additionalProperties false, validateToolInputs left on; never write to System.out with STDIO (logs to stderr, banner and console logging off); tool work under a timeout that cancels it and a fail-fast concurrency cap; failures as isError results with sanitized text, never thrown; HTTP behind an OAuth 2.1 resource server checking the token audience, plus Origin and Host validation.
metadata:
  verified: 2026-10-18
---

# MCP server in Java

## The decision

An MCP server turns parts of your service into tools that a model decides to call. The decisions are how much of the service the tools expose, how their arguments are checked, which transport carries them, how long a call may run, and who may call them. The fast path is the dangerous one: a `run_sql` tool with an empty schema, a `System.out.println` that corrupts the STDIO stream, a handler with no timeout that a retrying model piles up, an exception text with table names sent back to the model, and an HTTP endpoint with no authentication because "only our agent calls it". The model is steered by whatever text it read last, so every argument is attacker-influenced input. This skill fixes the server's shape on the official MCP Java SDK and says where Spring AI fits. Timeouts in general belong to [`timeouts-and-deadlines`](../timeouts-and-deadlines/SKILL.md), shedding load to [`overload-protection`](../overload-protection/SKILL.md), and TLS and secrets to [`secure-defaults`](../secure-defaults/SKILL.md).

## The rule

1. Build the server on the official MCP Java SDK (`io.modelcontextprotocol.sdk:mcp`, 2.1.0), or on Spring AI 2.0's MCP server starters in a Spring Boot application, and expose narrow, intent-level tools such as `search_orders`, never a tool that runs SQL, a shell command or an arbitrary path or URL.
2. Give every tool an input schema with `type`, `enum`, `minimum`/`maximum`, `required` and `"additionalProperties": false`, keep the SDK's `validateToolInputs` on, and check in the handler what a schema cannot say.
3. Never write to `System.out` in a STDIO server: log through SLF4J to stderr, and in Spring Boot set `spring.main.banner-mode=off` and `logging.console.enabled=false`.
4. Use STDIO only for a local server the client starts as a subprocess, and Streamable HTTP for anything reached over the network.
5. Run each tool's work with a deadline that cancels it on expiry and behind a semaphore that answers "busy" at once instead of queueing.
6. Return every failure the model can act on (bad input, busy, timeout, dependency down) as a result with `isError(true)` and a short sanitized message, and never let an exception escape the handler.
7. Put an HTTP server behind an OAuth 2.1 resource server that rejects tokens not issued for this server's URI, serves protected resource metadata, validates `Origin` and `Host`, and passes the authenticated subject to the tools.

## Why

**Rule 1.** The Java SDK is maintained in the MCP project's own GitHub organization; its server passes the MCP conformance suite (40/40 server checks, per the [repository](https://github.com/modelcontextprotocol/java-sdk)). The `mcp` artifact is `mcp-core` (STDIO, servlet SSE and Streamable HTTP transports) plus `mcp-json-jackson3` (Jackson 3 and a JSON schema validator); `mcp-bom` pins them together. Version 2.1.0 (October 2026) supports protocol revisions up to 2025-11-25 (`ProtocolVersions`); the newest spec revision is 2026-07-28, which this SDK version does not speak yet. In a Spring Boot application, Spring AI 2.0.1's `spring-ai-starter-mcp-server` (STDIO), `spring-ai-starter-mcp-server-webmvc` and `-webflux` wrap the same SDK (2.0.0) and register `@McpTool` methods; `spring.ai.mcp.server.protocol` selects `STREAMABLE` or `STATELESS` (`SSE` is deprecated) ([Spring AI MCP server](https://docs.spring.io/spring-ai/reference/api/mcp/mcp-server-boot-starter-docs.html)). The harder part is tool design. A `run_sql` tool makes input validation and access control impossible, because the allowed input is "anything the database user can do"; one prompt injection in a README, a ticket or an earlier tool result becomes `delete from orders` with the service's credentials. A tool named after an intent has a small, checkable input that maps to one parameterized query. When a workflow needs more, add another narrow tool.

**Rule 2.** The SDK checks every `tools/call` against the tool's `inputSchema` before the handler runs (`validateToolInputs` defaults to true) and answers a violation with an `isError` result naming the field: in the example's test, `limit: 500`, `status: "lost"`, an invented `owner` key and a missing `status` all fail with messages such as "/limit: must have a maximum value of 50", and the store is never called. An empty schema (`{"type": "object"}`) passes anything, so the handler casts a `Map<String, Object>` it cannot trust. The schema is also what the model reads, so an `enum` both blocks bad values and tells the model which values exist. Rules a schema cannot express, such as "this order belongs to the caller", stay in the handler. Turning `validateToolInputs` off, as the bad example does, removes the only check between the model and the handler.

**Rule 3.** The STDIO transport spec says the server "MUST NOT write anything to its `stdout` that is not a valid MCP message" and "MAY write UTF-8 strings to `stderr` for any logging purposes" ([stdio transport](https://modelcontextprotocol.io/specification/2026-07-28/basic/transports/stdio)). Messages are newline-delimited JSON on stdout, so one `println`, a startup banner or a console log line is read as a broken message: the client drops it, fails the response or closes the connection, and the cause is invisible because stdout is the channel you would debug with. The SDK logs through SLF4J; `slf4j-simple` writes to `System.err` by default ([SimpleLogger](https://www.slf4j.org/api/org/slf4j/simple/SimpleLogger.html)), while Logback's `ConsoleAppender`, which Spring Boot uses, targets `System.out` by default ([Logback appenders](https://logback.qos.ch/manual/appenders.html)), which is why a Boot STDIO server needs `logging.console.enabled=false` (log to a file instead) and `spring.main.banner-mode=off`. The example's test replaces `System.out` during the session and fails if a single byte lands there, and parses every line the server writes as JSON-RPC.

**Rule 4.** STDIO has no network surface: the client launches the process and owns both pipes, and the authorization spec says STDIO implementations "SHOULD NOT" use OAuth and should "retrieve credentials from the environment" instead ([authorization](https://modelcontextprotocol.io/specification/2025-11-25/basic/authorization)). Anything shared by several users or reached over a network needs Streamable HTTP: `HttpServletStreamableServerTransportProvider` in plain servlet code, or the Spring AI webmvc starter. Behind a load balancer without session affinity, use the stateless transport (`HttpServletStatelessServerTransport`, or `protocol=STATELESS` in Spring AI), which keeps no session in memory, so any replica can answer any request.

**Rule 5.** The server's `requestTimeout` bounds requests the server sends to the client (sampling, elicitation), not your tool's work, so a slow query holds its call for as long as the query runs, and a model that retries piles calls up until the pool is exhausted. The sync server runs handlers on Reactor's bounded elastic scheduler; the example submits the work to a virtual thread and waits with `Future.get(timeout)`, then calls `cancel(true)`, which interrupts the query instead of letting it run on unobserved. The test checks that a 10 s store call ends after 300 ms and that the store saw the interrupt. A `Semaphore.tryAcquire()` without a timeout caps calls per process and returns "busy" immediately, which the model can act on; queueing would just move the timeout somewhere less visible. Size it from what the dependency behind the tool can serve, not from what clients send.

**Rule 6.** MCP has two error channels. A tool result with `isError: true` goes to the model, which can correct its arguments, wait or give up. A JSON-RPC error is a protocol failure that clients may not show to the model at all. In the SDK, an exception thrown from a handler becomes a JSON-RPC error carrying the exception's message, so `throw new IllegalStateException(e.getMessage())` sends `relation "orders_v2" does not exist` to the client and teaches the model nothing useful. Catch inside the handler, log the details to stderr, and return a message written for the model: "order store unavailable, retry later", "search timed out after 300 ms, narrow the query".

**Rule 7.** Authorization is optional in the spec, but an HTTP MCP server without it is a remote API that runs its tools for anyone who reaches the port; Spring AI's docs warn that its HTTP transports serve an unauthenticated endpoint until you add Spring Security in front of it. When present, the MCP server is an OAuth 2.1 resource server: it "MUST implement OAuth 2.0 Protected Resource Metadata (RFC9728)", "MUST validate that access tokens were issued specifically for them as the intended audience", answers 401 for invalid or expired tokens and 403 for missing scopes, and "MUST NOT pass through the token it received" to upstream APIs. With Spring Security 7, `oauth2ResourceServer` validates the JWT, `spring.security.oauth2.resourceserver.jwt.audiences` rejects tokens minted for another service, and `protectedResourceMetadata(...)` serves the RFC 9728 document ([Spring Security](https://docs.spring.io/spring-security/reference/servlet/oauth2/resource-server/protected-resource-metadata.html)). Spring AI's `mcp-server-security` module is a community project marked work in progress, and its audience check (`validateAudienceClaim`) is off by default, so turn it on if you use it. The SDK's `DefaultServerTransportSecurityValidator` rejects requests whose `Origin` or `Host` is not on the allow-list, which closes DNS rebinding against a server on localhost. A `contextExtractor` copies the authenticated subject into the `McpTransportContext`, where tools read it from the exchange; a `customer_id` argument is whatever the model was told to send.

The cost: narrow tools mean more of them, a strict schema rejects calls a lax one would have tried, and a timeout fails slow calls that might have finished. Each is cheaper than a tool that runs arbitrary SQL or a client that drops the connection on the first log line.

## Good example

See [`examples/good/`](examples/good/).

```java
    static final String SEARCH_SCHEMA = """
            {"type": "object",
             "properties": {
               "status": {"type": "string", "enum": ["pending", "shipped", "delivered", "cancelled"]},
               "limit": {"type": "integer", "minimum": 1, "maximum": 50}},
             "required": ["status", "limit"],
             "additionalProperties": false}""";
```

```java
        if (!inFlight.tryAcquire()) {
            return error("busy: too many concurrent searches, retry shortly");
        }
        try {
            Future<List<String>> work = workers.submit(() -> store.search(status, limit));
            try {
                List<String> ids = work.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
```

```java
            } catch (TimeoutException e) {
                work.cancel(true); // interrupts the query instead of letting it run on
                return error("search timed out after " + timeout.toMillis() + " ms, narrow the query");
            } catch (ExecutionException e) {
                log.warn("search_orders failed", e.getCause()); // details go to stderr only
                return error("order store unavailable, retry later");
```

```java
    private static CallToolResult error(String message) {
        return CallToolResult.builder().addTextContent(message).isError(true).build();
    }
```

```xml
    <!-- slf4j-simple writes to System.err by default: stdout belongs to the protocol. -->
```

```java
                .securityValidator(DefaultServerTransportSecurityValidator.builder()
                        .allowedOrigins(List.of("https://agent.example.com"))
                        .allowedHosts(List.of("mcp.example.com"))
                        .build())
```

The test drives the real STDIO transport in process through piped streams, speaking JSON-RPC lines as a client would: `initialize`, then `tools/call` with valid arguments (structured content back), four invalid ones (rejected by the schema before the handler), a slow call (timed out and interrupted) and a failing store (a sanitized tool error). Every line from the server must parse as JSON-RPC, and `System.out` must stay empty. The HTTP transport factory compiles against the SDK and shows where `Origin`/`Host` validation and the authenticated subject plug in.

## Bad example

See [`examples/bad/`](examples/bad/).

```java
        // MISTAKE: a print on stdout with the STDIO transport. The client reads this line as a
        // JSON-RPC message, fails to parse it and drops the connection or the response.
        System.out.println("orders MCP server starting...");
```

```java
        // MISTAKE: a tool that runs whatever SQL the model sends, with an empty schema.
        // One prompt injection in a README or a ticket becomes "delete from orders".
        Tool tool = Tool.builder()
                .name("run_sql")
```

```java
                    } catch (SQLException e) {
                        // MISTAKE: thrown out of the handler, so the SDK answers with a JSON-RPC
                        // error the model may never see, and the message leaks table names.
                        throw new IllegalStateException(e.getMessage(), e);
                    }
```

```java
                .validateToolInputs(false) // MISTAKE: turns off the SDK's schema check
```

The handler also has no timeout and no cap. It all works in a demo where the developer types the prompts; it fails when the model reads a hostile document, retries a slow query twenty times, or the first log line reaches stdout.

## Checklist

When reviewing code under this skill, confirm:

- [ ] The server uses `io.modelcontextprotocol.sdk` (via `mcp-bom`) or Spring AI's MCP server starters, not a hand-written JSON-RPC loop.
- [ ] Every tool is a narrow intent with a fixed operation behind it; none takes SQL, a shell command, a file path or a URL to fetch.
- [ ] Every input schema lists `required`, bounds numbers, enumerates closed sets and sets `"additionalProperties": false`, and `validateToolInputs` is not turned off.
- [ ] Nothing in a STDIO server writes to `System.out`: logging goes to stderr or a file, and a Boot server sets `spring.main.banner-mode=off` and `logging.console.enabled=false`.
- [ ] Each tool call runs under a deadline that cancels the work, and a fail-fast concurrency cap sized from the dependency.
- [ ] Handlers catch failures and return `isError(true)` results with short messages, and no SQL error, stack trace or internal name reaches the result.
- [ ] An HTTP endpoint requires a bearer token whose audience is this server, serves protected resource metadata, validates `Origin` and `Host`, and never forwards the client's token upstream.
- [ ] Tools take the caller's identity from the authenticated context, never from an argument.

## When not to apply

- **A local developer tool over STDIO.** It needs no OAuth: the spec says STDIO servers read credentials from the environment, so pass a scoped API key through the client's server config, and keep rules 1 to 3, 5 and 6.
- **Spring Boot application already on Spring AI.** Use its starters and `@McpTool`; the same rules apply through its properties, and its `McpSyncServer` bean is the same SDK object.
- **A tool that must be broad by design.** A sandboxed code runner for a single trusted developer can accept code, but then the sandbox (container, no network, no credentials, CPU and time limits) is the control, and the server must never run with production credentials.
- **Long-running work.** A tool that starts a job taking minutes should enqueue it and return an id with a second tool to poll status ([`background-jobs-outbox`](../background-jobs-outbox/SKILL.md)), instead of raising the timeout.
- **The 2026-07-28 protocol revision.** SDK 2.1.0 implements revisions up to 2025-11-25; if a client requires the newer, sessionless revision, check the SDK's release notes before choosing it, and do not hand-roll protocol changes.
