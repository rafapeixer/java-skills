---
name: outbound-http-client
description: Use when writing or reviewing Java code that calls another service over HTTP: creating a java.net.http.HttpClient, RestClient, WebClient or RestTemplate, an HTTP interface client (@HttpExchange, @ImportHttpServices), wrapping a third-party API, adding retries, or reading response bodies. Key rule: build one HttpClient (or RestClient) per target service at startup and share it, never one per call, with .executor(Executors.newVirtualThreadPerTaskExecutor()); set connectTimeout on the client and a timeout on every request; check the status code yourself (HttpClient does not throw on 4xx/5xx); read bodies through a size limit, always close streamed bodies; retry only idempotent requests (GET, PUT, DELETE, or POST with an idempotency key), only on 429/502/503/504 and connect or timeout failures, at most 3 attempts, capped exponential backoff with full jitter, honor Retry-After. In Spring use RestClient or HTTP interface clients, not RestTemplate (deprecated in Framework 7.0). HTTP/3 is opt-in on JDK 26+ only.
metadata:
  verified: 2026-10-19
---

# Outbound HTTP client

## The decision

Every Java service that calls another service decides how its HTTP client is built, what happens when a call is slow or fails, and what it does with the response body. The short choices are the dangerous ones: `HttpClient.newHttpClient()` inside a method looks harmless and throws away its connection pool on every call; a client with no timeouts blocks forever on a stalled upstream; a `while (true)` around `send` turns one failing upstream into a retry storm and a `POST` into duplicate orders; `BodyHandlers.ofString()` trusts the upstream to send a sane amount; and nothing in `java.net.http` throws on a 500, so an error page flows on as data. None of this fails against a fast local test server. It fails under load (handshakes, threads), on a slow upstream (requests that never end) or on a bad day (duplicated writes). This skill fixes how the client for one upstream is built, which calls may be retried and how, and how each response is read. How long each call may take inside a request is `timeouts-and-deadlines`; per-dependency concurrency limits and circuit breakers are `overload-protection`.

## The rule

1. Create one `HttpClient` (or one Spring `RestClient`) per upstream service at startup, built with `.executor(Executors.newVirtualThreadPerTaskExecutor())`, and share it across all requests, never one per call.
2. Set `connectTimeout` on the client and `timeout` on every `HttpRequest` (in Spring, `spring.http.clients.connect-timeout` and `read-timeout`, or per service group).
3. Check the status code of every response yourself, and turn anything unexpected into an exception that names the upstream, path and status.
4. Read bodies through a size limit, and close every streamed body (`ofInputStream`, `ofLines`, `ofPublisher`) in try-with-resources on every path, error paths included.
5. Retry only idempotent requests (GET, HEAD, PUT, DELETE, or a POST that carries an idempotency key), only on connect failures, timeouts and 429/502/503/504, and at most 3 attempts in total.
6. Wait between attempts with capped exponential backoff and full jitter, and wait longer when the server sends `Retry-After`.
7. In Spring, use `RestClient` built from the injected `RestClient.Builder`, or an HTTP interface client with `@ImportHttpServices`, never `RestTemplate` in new code.
8. Keep the default redirect policy `NEVER` for service-to-service calls, and opt into HTTP/3 only on JDK 26+ with `HttpClient.Version.HTTP_3`.

## Why

**Rule 1.** The [`HttpClient` javadoc](https://docs.oracle.com/en/java/javase/25/docs/api/java.net.http/java/net/http/HttpClient.html): "An HttpClient instance typically manages its own pools of connections... Connection pools are typically not shared between HttpClient instances. Creating a new client for each operation, though possible, will usually prevent reusing such connections." A client per call pays a TCP handshake and, for HTTPS, a TLS handshake on every call (often more CPU than the request), leaves sockets in `TIME_WAIT`, and each client also creates its own executor and selector thread. A client per upstream, rather than one for the whole process, lets each upstream have its own timeouts and makes the dependency an explicit field. The example's test sends 20 calls, half of them 404s, and checks that the server saw one client connection. `HttpClient` is `AutoCloseable` since JDK 21; close it at shutdown, not after each call. In Spring, a `RestClient` is "safe to use in multiple threads" once built ([Spring Framework reference](https://docs.spring.io/spring-framework/reference/integration/rest-clients.html)). Give the client a virtual-thread executor: without one it "uses a thread pool, with a custom thread factory" ([`HttpClient.Builder.executor`](https://docs.oracle.com/en/java/javase/25/docs/api/java.net.http/java/net/http/HttpClient.Builder.html)), and that pool of platform threads runs the response parsing and completes the future before handing back to the caller's (virtual) thread. Measured in benchmarks/orders-api (TUNING.md): with 15% of requests making three pricing calls, the client was 16-22% of the service's CPU and 66% of those samples ran on the default pool's workers; `.executor(Executors.newVirtualThreadPerTaskExecutor())` cut CPU per request by 16% (0.427 to 0.358 ms at 3,000 req/s) with timeouts and behavior unchanged. For a Spring `RestClient` on the JDK client, pass such an `HttpClient` to `JdkClientHttpRequestFactory`. The example checks that the client's executor runs tasks on virtual threads.

**Rule 2.** Neither timeout has a default. Without `connectTimeout`, a connect to a dead host waits for the operating system; without a request `timeout`, the javadoc says the request will "block forever". They bound different things: `connectTimeout` only applies "in the case where a new connection needs to be established", and the request timeout bounds the response (only until headers arrive before JDK 26; see `timeouts-and-deadlines` for bounding the body on JDK 25 and for deriving the value from the request deadline). In Spring Boot 4, [`spring.http.clients.connect-timeout` and `spring.http.clients.read-timeout`](https://docs.spring.io/spring-boot/reference/io/rest-client.html) apply to every auto-configured client, and `spring.http.serviceclient.<group>.connect-timeout` / `read-timeout` per HTTP service group. On a hand-built `JdkClientHttpRequestFactory`, `setReadTimeout` sets the request timeout.

**Rule 3.** `HttpClient.send` returns normally for a 404 or a 503; only I/O failures throw. A caller that reads `response.body()` without checking parses an HTML error page as a price, or worse, succeeds with it. Spring's `RestClient.retrieve()` does throw on 4xx/5xx by default, which is one reason to prefer it over the raw JDK client inside Spring.

**Rule 4.** `ofString()` and `ofByteArray()` read the whole body into memory with no limit; a buggy or hostile upstream that sends 2 GB takes the heap with it. Read `ofInputStream()` with `readNBytes(max + 1)`, which tells "exactly at the limit" from "over it", and fail when it is over. Streamed bodies must be closed: the [javadoc](https://docs.oracle.com/en/java/javase/25/docs/api/java.net.http/java/net/http/HttpClient.html) says a caller "must eventually obtain the streaming response body and close, cancel, or read the returned streams to exhaustion", and that failing to do so "may stop delivery of data while leaving the request open, and stall an orderly shutdown". The usual leak is an `if (status != 200) throw` before the try-with-resources.

**Rule 5.** A timeout does not mean the request did not happen: the upstream may have created the order and lost only the response. Retrying a non-idempotent `POST` can create it twice. [RFC 9110 section 9.2.2](https://www.rfc-editor.org/rfc/rfc9110#section-9.2.2) defines the idempotent methods (PUT, DELETE and the safe methods GET, HEAD, OPTIONS, TRACE); a `POST` becomes safe to retry only when the upstream deduplicates it by a key you send. Retry only failures that say nothing about the request itself: a connect failure, a timeout, 429 and 502/503/504. A 400 or 404 will fail the same way every time, and a 500 often means the request itself triggers a bug. Bound attempts: with 3 attempts per layer and three layers of services, one user request becomes 27 calls on the deepest service.

**Rule 6.** Retries without a wait arrive while the upstream is still failing; retries with the same fixed wait from every client arrive together. Exponential backoff with a cap spaces them out, and full jitter (a random wait between 0 and the current ceiling) spreads clients apart. `Retry-After` ([RFC 9110 section 10.2.3](https://www.rfc-editor.org/rfc/rfc9110#section-10.2.3)) is the upstream telling you when it can take traffic again; waiting less only adds load. The example caps the server's value at the backoff cap so one header cannot hold a request for minutes. In Spring Framework 7, `@Retryable(includes = ..., maxRetries = 2, delay = 50, multiplier = 2, jitter = 25, maxDelay = 1000)` with `@EnableResilientMethods` does the same on a method ([resilience features](https://docs.spring.io/spring-framework/reference/core/resilience.html)); its default is to retry any exception 3 times with 1 s delay, so narrow `includes` and put it only on idempotent calls.

**Rule 7.** The Spring Framework reference: "As of Spring Framework 7.0, `RestTemplate` is deprecated in favor of `RestClient` and will be removed in a future version" (the class itself is not yet annotated `@Deprecated` in 7.0.x). Spring Boot creates a prototype `RestClient.Builder` with message converters and a request factory; inject it, call `baseUrl(...)`, and build one `RestClient` per upstream. HTTP interface clients declared with `@HttpExchange` and imported with `@ImportHttpServices(group = "pricing", types = PricingApi.class)` share one client setup per group, "typically one group per host", configured under `spring.http.serviceclient.pricing.*`.

**Rule 8.** `HttpClient` defaults to `Redirect.NEVER`. `ALWAYS` follows a redirect from `https` to `http` and to any host the response names; between services a redirect usually means a misconfigured URL, which should fail loudly. HTTP/3 ([JEP 517](https://openjdk.org/jeps/517), JDK 26) is opt-in with `.version(HttpClient.Version.HTTP_3)` on the client or request, and transparently falls back to HTTP/2 or HTTP/1.1 unless `HTTP_3_URI_ONLY` discovery is set. `Version.HTTP_3` does not exist in the JDK 25 API, so floor code cannot reference it; use it only in services that run on 26+ and talk to an upstream that serves HTTP/3.

## Good example

See [`examples/good/`](examples/good/).

```java
public PricingClient(URI base) {
    this.base = base;
    this.http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(1)) // bounds only TCP (and TLS) setup
            .followRedirects(HttpClient.Redirect.NEVER) // the default; stated so nobody "fixes" it
            // The default executor is a platform thread pool that runs the client's
            // response handling; virtual threads keep that work on the caller's scheduler.
            .executor(Executors.newVirtualThreadPerTaskExecutor())
            .build();
}
```

```java
for (int attempt = 1; ; attempt++) {
    boolean last = !idempotent || attempt == MAX_ATTEMPTS;
    try {
        HttpResponse<InputStream> response = http.send(request, BodyHandlers.ofInputStream());
        if (last || !RETRYABLE.contains(response.statusCode())) {
            return response;
        }
        response.body().close(); // release the exchange before waiting
        Thread.sleep(backoff(attempt, retryAfter(response)));
```

```java
try (InputStream in = response.body()) {
    byte[] bytes = in.readNBytes(MAX_BODY + 1);
    if (bytes.length > MAX_BODY) {
        throw new IOException("pricing response larger than " + MAX_BODY + " bytes");
    }
```

`PricingClient` is built once and holds the only `HttpClient` for the pricing service. `Main` runs it against a local `HttpServer` and checks that 20 calls, half of them 404s, use one connection; that the client's executor runs on virtual threads; that a GET failing twice with 503 succeeds on the third attempt; that an upstream that stays down gets exactly 3 attempts; that a POST answered with 503 is sent once; that `Retry-After: 1` delays the retry by a second; that a 1 MiB body fails instead of being read; that three timed-out attempts end in under 2 s; and that every backoff stays between 0 and its ceiling.

## Bad example

See [`examples/bad/`](examples/bad/).

```java
// MISTAKE: a new HttpClient per call; its connection pool dies with it, so every call
// pays a TCP and TLS handshake, and each client starts its own selector thread.
// MISTAKE: no connectTimeout and no request timeout; a stalled upstream blocks forever.
HttpClient client = HttpClient.newBuilder()
```

```java
// MISTAKE: retries a POST, forever, with no delay. A timeout does not mean the quote
// was not created, so each retry can create another; and when the upstream is down,
// every caller hammers it in a tight loop, which keeps it down.
while (true) {
```

The file also leaves the client on its default platform-thread executor, follows every redirect with `Redirect.ALWAYS`, reads bodies with no size limit, returns a 404 or 500 error page as if it were a price, and throws on an error status before closing a streamed body. All of it compiles and works against a healthy upstream.

## Checklist

When reviewing code under this skill, confirm:

- [ ] No `HttpClient.newHttpClient()`, `HttpClient.newBuilder()...build()`, `RestClient.create()` or `builder.build()` inside a method that runs per request or per call.
- [ ] Every shared `HttpClient` is built with `.executor(Executors.newVirtualThreadPerTaskExecutor())`, not the default platform thread pool.
- [ ] Every `HttpClient` sets `connectTimeout`, and every `HttpRequest` sets `timeout` (or the Spring client has connect and read timeouts configured).
- [ ] Every response's status code is checked before its body is used.
- [ ] Every body is read through a size limit, and every streamed body is closed in try-with-resources before any status check can throw.
- [ ] Retries apply only to idempotent requests or POSTs with an idempotency key, and only on connect failures, timeouts and 429/502/503/504.
- [ ] Attempts are capped (3 total or fewer), with capped exponential backoff, jitter and `Retry-After` honored.
- [ ] No `RestTemplate` in new Spring code; `RestClient` comes from the injected `RestClient.Builder` or an `@ImportHttpServices` group.
- [ ] Redirects are not `ALWAYS`, and `HttpClient.Version.HTTP_3` appears only in code that requires JDK 26+.

## When not to apply

- **Existing `RestTemplate` code** keeps working in Framework 7.0. Do not rewrite it in a feature PR; migrate it on its own, following the reference guide "Migrating to RestClient", and give it the same timeouts and retry rules meanwhile.
- **Streaming responses** (server-sent events, large downloads). A whole-body size limit does not apply; read the stream incrementally, close it when done, and bound the time between chunks instead of the total (see `timeouts-and-deadlines`).
- **Calls that cannot be made idempotent** (a payment provider with no idempotency key). Do not retry them at all; on a timeout, reconcile by querying the upstream for the outcome, or hand the work to an outbox (see `background-jobs-outbox`).
- **Vendor SDKs** that build their own HTTP client. Configure their timeouts and retries through the SDK's options, create the SDK client once, and make sure its retries and yours do not multiply.
- **One-off CLIs and scripts** that make a few calls and exit gain nothing from retries; still set both timeouts and check status codes.
- **Reactive stacks** already on `WebClient` keep it; the same rules apply through its connector's timeouts and `retryWhen` with backoff.
