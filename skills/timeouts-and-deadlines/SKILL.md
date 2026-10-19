---
name: timeouts-and-deadlines
description: Use when adding a timeout to a Java call, writing code that blocks (HttpClient.send, Future.get, CompletableFuture.join, JDBC executeQuery, BlockingQueue, locks), seeing a request hang or threads pile up behind a slow dependency, or deciding how long a downstream call may take inside a request. Key rule: every blocking call has a bound, and the bound comes from the request's deadline, not a constant; create one Deadline at the edge (System.nanoTime + budget), pass it down, and give each call min(its cap, remaining time), failing before the call when nothing is left. HttpClient needs connectTimeout on the client and HttpRequest.timeout per request (it covers the response body only on JDK 26+, so on JDK 25 bound sendAsync with Future.get(timeout) and cancel(true) on timeout); JDBC needs setQueryTimeout in whole seconds rounded up (0 means no limit); never Future.get() without a timeout; never retry past the deadline.
metadata:
  verified: 2026-10-18
---

# Timeouts and deadlines

## The decision

Every request a Java service answers has a point after which the answer is useless: the load balancer gave up, the client's own timeout fired, the user left. The decision is how that point reaches every blocking call the request makes, so that an HTTP call, a query and a wait on a future all stop when it passes. The JDK's defaults are the opposite: `HttpRequest` without `timeout` blocks forever, `HttpClient` without `connectTimeout` waits for the operating system's TCP timeout, `Statement` has no query limit, and `Future.get()` waits as long as the task does. None of this shows in tests against a healthy dependency. It shows the first time a dependency stalls: every request thread (or virtual thread, with its connection and pool slot) waits on it, the pool drains, and the service stops answering requests that never touch that dependency. Fixed per-call timeouts are only half a fix: three calls with a 5 s timeout each inside a 2 s request still work for 13 s after the caller left. This skill fixes where the budget is set, how it shrinks as the request proceeds, and how each kind of blocking call is bounded by it. How the HTTP client itself is built and retried is `outbound-http-client`; the pool's own `connectionTimeout` is `postgres-hikari-pool`.

## The rule

1. Give every blocking call an explicit bound: no `HttpRequest` without `timeout`, no `HttpClient` without `connectTimeout`, no `Future.get()` or `join()` without a timeout, no JDBC statement without `setQueryTimeout`.
2. Create one deadline per request at the edge (`System.nanoTime()` plus the budget) and pass it to every method that makes a blocking call.
3. Compute each call's timeout as the smaller of that call's own cap and the time left on the deadline, so the budget shrinks as the request proceeds.
4. When the time left is below what any call can use, throw a deadline exception before sending the call.
5. On JDK 25, bound an HTTP call's body as well as its headers: send with `sendAsync` and wait with `Future.get(timeout)`, because `HttpRequest.timeout` stops once headers arrive before JDK 26.
6. When a `Future.get(timeout)` times out, call `cancel(true)` on the future so the work stops instead of running on unobserved.
7. Convert the remaining time to JDBC whole seconds by rounding up and never pass 0, which means no limit.
8. Never retry after the deadline has passed, and give a retry only the time that is left, never a fresh full timeout.

## Why

**Rule 1.** The defaults are documented, and they are all "unbounded". [`HttpRequest.Builder.timeout`](https://docs.oracle.com/en/java/javase/25/docs/api/java.net.http/java/net/http/HttpRequest.Builder.html#timeout(java.time.Duration)): "The effect of not setting a timeout is the same as setting an infinite Duration, i.e. block forever." [`Statement.setQueryTimeout`](https://docs.oracle.com/en/java/javase/25/docs/api/java.sql/java/sql/Statement.html#setQueryTimeout(int)): "By default there is no limit on the amount of time allowed for a running statement to complete." [`Future.get()`](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/util/concurrent/Future.html) waits "if necessary for the computation to complete". The two HTTP timeouts are not the same thing: [`HttpClient.Builder.connectTimeout`](https://docs.oracle.com/en/java/javase/25/docs/api/java.net.http/java/net/http/HttpClient.Builder.html#connectTimeout(java.time.Duration)) only bounds establishing a new connection ("if a connection can be reused from a previous request, then this timeout duration has no effect"), and the request timeout bounds the response. You need both: a dead host is a connect problem, a slow host is a response problem. Virtual threads make waiting cheap, not free: each stalled call still holds a connection, a pool slot and memory for the request it serves.

**Rule 2.** A deadline is a point in time; a timeout is a duration. Passing the point lets every layer compute what is left without knowing what ran before it. Use `System.nanoTime()`: it is monotonic, while `Instant.now()` follows the wall clock, which NTP can move. Pass it as a parameter (the example's `Deadline`); a `ScopedValue` (final in JDK 25, see `scoped-values`) works when many layers sit between the edge and the calls. Set the budget a little below whatever gives up first upstream (the load balancer's idle timeout, the caller's own timeout), so your service fails with a clear error instead of being cut off mid-response.

**Rule 3.** Fixed timeouts add up; deadlines do not. With a 2 s budget, a 5 s cap on each of three calls lets one request run for 15 s; with `min(cap, remaining)`, the whole request ends by 2 s whatever each call does. The cap is still useful: it says "this dependency answers in 200 ms normally, so 1 s means it is broken", which keeps one slow dependency from eating the whole budget when other calls follow it.

**Rule 4.** A call sent with 3 ms left cannot succeed; it only adds load to a dependency that may already be struggling and returns an error a few milliseconds later. Failing before sending, with an exception that names the operation, turns that into zero load and a precise log line. The example's `Deadline.timeoutFor` throws below 10 ms; pick the floor from the fastest call you make.

**Rule 5.** Before JDK 26, the JDK HttpClient's request timeout ran only until the response headers were received; a server that sends headers and then trickles the body was not bounded by it. [JDK-8208693](https://bugs.openjdk.org/browse/JDK-8208693) (fixed in 26) extended it, and the JDK 26 [`timeout` javadoc](https://docs.oracle.com/en/java/javase/26/docs/api/java.net.http/java/net/http/HttpRequest.Builder.html#timeout(java.time.Duration)) says the built-in implementation now measures "to the instant the response body is consumed". The floor is JDK 25, so code must not rely on it: `sendAsync(...).get(timeout)` completes only after the body handler finishes, so it bounds the whole exchange on every JDK. The example's test checks both behaviors: on JDK 25 a plain `send` with a 500 ms timeout returns a body that took 2 s; on JDK 27 it throws `HttpTimeoutException`.

**Rule 6.** `Future.get(timeout)` stops your wait, not the work. Without `cancel(true)`, the HTTP exchange or task keeps running and keeps its connection. For futures from `HttpClient.sendAsync`, the [javadoc](https://docs.oracle.com/en/java/javase/25/docs/api/java.net.http/java/net/http/HttpClient.html) says `cancel(true)` "attempts to cancel the HTTP exchange in an effort to release underlying resources as soon as possible" (with HTTP/1.1 it may close the connection). For an `ExecutorService` task, `cancel(true)` interrupts the thread running it; that only helps if the task responds to interrupts (see `exceptions`).

**Rule 7.** `setQueryTimeout` takes `int` seconds and "zero means there is no limit". Integer division turns 800 ms into 0, which silently removes the bound exactly when the budget is nearly spent. Round up, minimum 1. The limit is enforced by the driver; pgJDBC sends a cancel request over a separate connection, bounded by `cancelSignalTimeout` (default 10 s, [pgJDBC docs](https://jdbc.postgresql.org/documentation/use/)). In Spring, `JdbcTemplate` applies the current transaction's timeout to each statement ([`DataSourceUtils.applyTimeout`](https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/jdbc/datasource/DataSourceUtils.html)), so `@Transactional(timeout = 2)` bounds every query in the transaction. Waiting for a pooled connection is a blocking call too: HikariCP's `connectionTimeout` defaults to 30 s ([HikariCP](https://github.com/brettwooldridge/HikariCP)), which is far longer than most request budgets.

**Rule 8.** The bad example retries a timed-out call with a fresh 5 s timeout and without cancelling the first: the request now has two calls in flight and a budget it no longer respects. A retry that takes `deadline.timeoutFor(...)` gets what is left, and gets an exception instead when nothing is.

The cost: a `Deadline` parameter on every method that blocks, and a little arithmetic per call. In return, a request ends when its caller stops waiting, and every timeout error says which operation ran out.

## Good example

See [`examples/good/`](examples/good/).

```java
public Duration timeoutFor(String operation, Duration cap) {
    Duration left = remaining();
    if (left.compareTo(MIN_USEFUL) < 0) {
        throw new Exceeded(operation, left);
    }
    return left.compareTo(cap) < 0 ? left : cap;
}
```

```java
Duration timeout = deadline.timeoutFor(op, cap); // throws before sending if the budget is spent
HttpRequest request = HttpRequest.newBuilder(uri).timeout(timeout).build();
CompletableFuture<String> call = client.sendAsync(request, BodyHandlers.ofString())
        .thenApply(r -> r.body());
try {
    return call.get(timeout.toNanos(), TimeUnit.NANOSECONDS);
} catch (TimeoutException e) {
    call.cancel(true); // abort the exchange so the connection and thread are released
    throw new Deadline.Exceeded(op, timeout, e);
```

```java
ps.setQueryTimeout(deadline.queryTimeoutSeconds("count orders", Duration.ofSeconds(2)));
```

`Main` starts a local `HttpServer` with a fast endpoint, one that delays headers and one that delays the body, and checks that a slow call inside a 1 s budget is cut at about 1 s (not at its 5 s cap or the server's 2 s), that the second call's timeout is what the first left, that a spent budget fails without sending a request, that a slow body is cut on every JDK, which JDK versions cover the body with the request timeout alone, and that 1.2 s and 0.3 s budgets become query timeouts of 2 s and 1 s.

## Bad example

See [`examples/bad/`](examples/bad/).

```java
// MISTAKE: no connectTimeout; a SYN to a dead host waits for the OS TCP timeout
// (often more than a minute) while the request it belongs to was abandoned long ago.
static final HttpClient client = HttpClient.newHttpClient();
```

```java
// MISTAKE: a fixed 5 s per call inside a 2 s request; the caller is gone after 2 s
// and this call keeps a connection busy for 3 more seconds doing useless work.
HttpRequest request = HttpRequest.newBuilder(URI.create("http://stock.internal/stock/" + sku))
        .timeout(Duration.ofSeconds(5))
        .build();
```

```java
            // MISTAKE: the remaining budget in milliseconds divided down to seconds; 800 ms
            // becomes 0, and 0 means "no limit" in JDBC.
            ps.setQueryTimeout((int) (remainingMillis / 1000));
```

The file also sends a request with no timeout at all, waits on `Future.get()` with no bound, retries a timed-out call with a fresh timeout without cancelling the first, and runs a query with no query timeout. It compiles and works while every dependency is healthy.

## Checklist

When reviewing code under this skill, confirm:

- [ ] Every `HttpClient` is built with `connectTimeout`, and every `HttpRequest` has `timeout`.
- [ ] No `Future.get()`, `CompletableFuture.join()` or `get()`, `BlockingQueue.take()` or `Lock.lock()` on a request path without a timeout variant.
- [ ] A deadline is created once per request at the edge from `System.nanoTime()` and passed down, not recreated per call.
- [ ] Each call's timeout is `min(cap, remaining)`, and a spent deadline throws before the call is sent.
- [ ] On JDK 25 code paths, response bodies are bounded too (`sendAsync` plus `get(timeout)`), not only headers.
- [ ] Every timed-out future is cancelled with `cancel(true)`.
- [ ] Every JDBC statement has `setQueryTimeout` (or a Spring transaction timeout), computed by rounding up, never 0.
- [ ] Retries use the remaining deadline and stop when it is spent.
- [ ] Timeout errors name the operation and the timeout that fired.

## When not to apply

- **Background jobs and batch work** have no caller waiting. Give them a per-step timeout and a per-run limit instead of a request deadline, and still bound every call: a job stuck on a dead socket is as bad as a request stuck on one.
- **Streaming responses** (server-sent events, downloads, long polling) are meant to stay open. Bound the connect and the time to first byte, then use an idle timeout between chunks instead of a total deadline.
- **Calls whose result must not be lost** (a payment capture, an outbox write after the response): do not cut them at the request deadline. Run them under their own timeout outside the request, with an owner that waits for them (see `background-jobs-outbox`).
- **Structured concurrency** (`StructuredTaskScope`) gives fan-out a scope-wide timeout and cancels the other subtasks when one fails, but it is a preview API through JDK 27. Do not use it in floor code; use `Future.get(timeout)` plus `cancel(true)` until it is final.
- **Local, in-memory work** (parsing, computing a price from loaded data) does not block. Check the deadline between expensive steps instead of wrapping each one in a future.
