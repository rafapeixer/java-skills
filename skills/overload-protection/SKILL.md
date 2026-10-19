---
name: overload-protection
description: Use when writing or reviewing Java code that decides what a service does above capacity, adds a concurrency limit or bulkhead, creates an ExecutorService or ThreadPoolExecutor for request or background work, enables virtual threads on a server, or sees 503s, latency or memory climb under load. Key rule: cap in-flight work with a Semaphore at the edge, tryAcquire (no wait, a few ms at most), 503 with Retry-After at once, no queueing; size every limit by Little's law from the measured rate, rate × (normal latency + longest expected stall), with the arithmetic in a comment; count each limit's rejections and attribute every 503 to its limit before tuning; never Executors.newFixedThreadPool or newSingleThreadExecutor for work that can outrun it (unbounded queue): use ThreadPoolExecutor with an ArrayBlockingQueue and AbortPolicy and shed on RejectedExecutionException; Spring Framework 7: use @ConcurrencyLimit with policy = REJECT (the default BLOCK is a queue) and @EnableResilientMethods; one bound per dependency.
metadata:
  verified: 2026-10-19
---

# Overload protection

## The decision

Every service eventually receives more work than it can finish in time: a traffic spike, a deploy that halves capacity, a dependency that slows from 20 ms to 2 s. The decision is what the code does at that moment. Java's defaults all answer "wait": `Executors.newFixedThreadPool` queues without limit, `Semaphore.acquire()` parks the caller, Spring's `@ConcurrencyLimit` blocks by default, and with virtual threads the server no longer has a thread pool that caps anything. Waiting looks safe in a test, but under overload it means the service spends its capacity on requests whose clients have already given up, memory grows with the backlog, and the slowdown outlasts the spike that caused it. This skill fixes the shape that prevents it: bound what is in flight, size the bound from arithmetic, and reject the excess fast and cheaply.

## The rule

1. Cap in-flight requests at the edge with a `Semaphore` taken by `tryAcquire()` (no wait, or a timeout of a few milliseconds at most) and answer `503` with `Retry-After` at once when it is full.
2. Size every limit by Little's law from the rate measured at capacity, in flight = rate × (normal latency + the longest stall you expect), never from an estimate, and write that arithmetic in a comment next to the constant.
3. Never hand work that can arrive faster than it is processed to `Executors.newFixedThreadPool` or `newSingleThreadExecutor`; build a `ThreadPoolExecutor` with an `ArrayBlockingQueue` of explicit capacity and `AbortPolicy`, and turn `RejectedExecutionException` into a fast rejection.
4. In Spring Framework 7, enable `@EnableResilientMethods` and declare `@ConcurrencyLimit(limit = N, policy = ConcurrencyLimit.ThrottlePolicy.REJECT)` on methods behind a request (7.0.3+), mapping `InvocationRejectedException` to `503`.
5. Give each dependency its own bound (a `Semaphore`, or its own connection pool) so one slow backend cannot take every slot in the process.
6. Reject before doing expensive work: admission is the first thing a request does, and health checks are outside it.
7. Make every limit's rejections countable (a counter per limit, or a sampled log line), and before changing any limit attribute each `503` of a load test to the limit that produced it.

## Why

**Rule 1.** A limit whose acquire waits is a queue with a different name. Once it is full, every new request parks a thread on it, holds its socket and its request body in memory, and adds its wait to every later request's latency, while the client times out and often retries. `tryAcquire()` turns the excess into a `503` that costs microseconds and holds nothing, and the requests already admitted stay fast. The good example proves the property that matters: with all 4 slots stuck on a slow dependency, 50 more requests all get `503` while the admitted ones are still blocked, so none of them waited for a slot. HTTP defines `503 Service Unavailable` as a temporary overload and `Retry-After` as the hint for when to come back ([RFC 9110, sections 15.6.4 and 10.2.3](https://www.rfc-editor.org/rfc/rfc9110)); use `429 Too Many Requests` ([RFC 6585](https://www.rfc-editor.org/rfc/rfc6585)) only when the limit is a per-client quota. If you must absorb microbursts, `tryAcquire(5, TimeUnit.MILLISECONDS)` is the most to wait; a timeout near the request deadline is a hidden queue again.

With virtual threads this rule is no longer optional. The [Spring Boot reference](https://docs.spring.io/spring-boot/reference/features/spring-application.html) says that once `spring.threads.virtual.enabled=true`, "properties which configure thread pools don't have an effect anymore", so the Tomcat thread maximum that used to cap concurrency is gone, and the [Oracle virtual threads guide](https://docs.oracle.com/en/java/javase/25/core/virtual-threads.html) tells you to limit concurrency with a `Semaphore` instead (see `virtual-threads`).

**Rule 2.** Little's law: the average number of requests in flight equals the arrival rate times the time each spends in the system. A replica that serves 400 req/s at 50 ms holds 20 in flight on average. The headroom is not a multiplier picked by feel: every stall (a GC pause, a JIT compilation, a second of saturated CPU) keeps admitting work at the full rate while nothing finishes, so it adds rate × stall. Size the limit as rate × (normal latency + longest expected stall): at 400 req/s, a 50 ms stall adds 20 more, so 40. Take the rate from the peak the replica sustains in a load test, not from average traffic or a design estimate, and the latency from the p99 of the healthy service, not from the timeout. Measured in benchmarks/orders-api (TUNING.md): a pricing bulkhead of 200 had been sized from an estimate of 30 quotes/s per replica; the measured rate at capacity was 900 quotes/s (2,700 pricing calls/s at 30 ms, about 80 in flight), each millisecond of stall added 2.7 calls, and 200 filled in bursts, losing 2-11% of requests at 5,000-6,000 req/s with CPU to spare. Re-sized to 512 (80 plus a 100 ms stall, rounded up) the errors at those rates went to 0-2.8%, with the slow-dependency scenario unchanged because the other bounds still held. A limit below the number turns ordinary bursts into errors while CPU sits idle; a limit far above it admits work that will miss its deadline. The comment keeps the inputs visible, so when traffic or latency changes, the next person redoes the arithmetic instead of guessing a new round number.

**Rule 3.** The [`Executors.newFixedThreadPool`](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/util/concurrent/Executors.html) documentation says its threads work "off a shared unbounded queue", and the [`ThreadPoolExecutor`](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/util/concurrent/ThreadPoolExecutor.html) documentation warns that an unbounded queue "admits the possibility of unbounded work queue growth when commands continue to arrive on average faster than they can be processed". A bounded `ArrayBlockingQueue` with finite threads makes the executor reject when saturated, and the default `AbortPolicy` throws `RejectedExecutionException`, which the caller turns into a `503` or a negative acknowledgement to the message broker. Size the queue as the backlog you can finish within the callers' deadline: workers × deadline ÷ task time, not "large to be safe". Avoid `CallerRunsPolicy` on a request thread: it hides the rejection by running the task on the very thread that was supposed to answer quickly.

**Rule 4.** Spring Framework 7 added [`@ConcurrencyLimit`](https://docs.spring.io/spring-framework/reference/core/resilience.html), processed when `@EnableResilientMethods` is declared on a `@Configuration` class. Its [Javadoc](https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/resilience/annotation/ConcurrencyLimit.html) documents `policy` (since 7.0.3) with default `BLOCK`, where "further concurrent invocations wait", and `REJECT`, which throws `InvocationRejectedException`, a `RejectedExecutionException`. With the default, the annotation is rule 1's blocking semaphore. Use `limitString = "${orders.max-in-flight}"` to keep the Little's-law number in configuration, and an `@ExceptionHandler(InvocationRejectedException.class)` that returns `503` with `Retry-After`. The annotation works through a proxy, so it does not apply to calls from inside the same bean.

**Rule 5.** One process-wide limit is not enough when there are several dependencies. If the search service slows to 10 s, its calls hold every admitted slot, and checkout, which never touches search, starts returning `503`s too. A separate bound per dependency, sized by rule 2 from that dependency's call rate and latency and kept below what it can serve, caps how much of the process one backend can hold. For the database, HikariCP's pool is that bound; the virtual threads guide notes a connection pool already acts as a semaphore, so do not add another in front of it, and keep its connection timeout short (see `postgres-hikari-pool`). For outbound HTTP, see `outbound-http-client`.

**Rule 6.** A rejected request is cheap only if nothing ran before the rejection: parsing a 2 MB body, opening a transaction or calling a dependency and then returning `503` wastes exactly the capacity the limit protects. A health check behind the limit fails exactly when the replica is busy, so the orchestrator restarts healthy pods when capacity is shortest; mount it outside.

**Rule 7.** A service with an admission limit, a pool timeout and a per-dependency bulkhead answers `503` from three places, and a load test that only counts `503`s cannot tell which limit is too small, or whether any is. Measured in benchmarks/orders-api (TUNING.md): of about 18,800 `503`s, the logs showed 584 pool borrow timeouts and 12 bulkhead rejections; the admission filter logged nothing, so roughly 97% came from it, and it was full because requests waited behind 5 connections (`waiting=230`) on a replica at 200% CPU. Raising the admission limit would have admitted more work into the same queue; the fix was the CPU per request (one statement per read, see [`sql-and-transactions`](../sql-and-transactions/SKILL.md)), which emptied every queue at once. Give each limit a counter named after it (a Micrometer counter in Spring, a `LongAdder` in plain Java) or a sampled log line, and read them next to CPU and pool metrics before touching a number. The good example counts its admission rejections and checks the count.

The cost: some requests get a `503` that a patient server might eventually have answered. That is the trade: a service that loses a fraction of its traffic during overload instead of all of it, and recovers when the trigger ends instead of working through a backlog nobody is waiting for.

## Good example

See [`examples/good/`](examples/good/).

```java
// Little's law, from the load test, not an estimate: the replica peaks at 40 req/s
// and a request takes 50 ms, so 40 × 0.05 = 2 are in flight on average; the longest
// stall we expect (a GC pause, JIT, a CPU-saturated moment) is 50 ms and adds
// 40 × 0.05 = 2 more. 40 × (0.05 + 0.05) = 4; above that the excess is rejected at once.
static final int MAX_IN_FLIGHT = 4;
static final Semaphore admission = new Semaphore(MAX_IN_FLIGHT);
// Every limit counts its rejections (export it as a metric), so a load test can tell
// which limit produced each 503.
static final LongAdder admissionRejected = new LongAdder();
```

```java
if (!admission.tryAcquire()) {
    admissionRejected.increment();
    exchange.getResponseHeaders().set("Retry-After", "1");
    exchange.sendResponseHeaders(503, -1); // costs microseconds, holds nothing
    return;
}
try {
```

Background work gets a bounded queue that rejects:

```java
var executor = new ThreadPoolExecutor(2, 2, 0, TimeUnit.SECONDS,
        new ArrayBlockingQueue<>(10), new ThreadPoolExecutor.AbortPolicy());
```

The example runs a real `HttpServer` on virtual threads, fills every slot with requests stuck on a slow dependency, and checks that 50 more requests all get `503` with `Retry-After` while the admitted ones are still blocked and that the admission counter saw exactly 50, that the admitted ones succeed once the dependency recovers, and that no permit leaks. It then submits 100 blocked tasks to the bounded executor and checks that exactly 2 run, 10 wait and 88 are rejected. No check depends on timing.

## Bad example

See [`examples/bad/`](examples/bad/).

```java
// MISTAKE: newFixedThreadPool has an unbounded queue. Above 200 concurrent
// requests every new one waits in memory, behind work its client has already
// abandoned, until the heap or the latency budget runs out. Nothing is rejected.
static final ExecutorService REQUESTS = Executors.newFixedThreadPool(200);
```

```java
// MISTAKE: a blocking acquire turns the limit into a queue. When it is
// full, requests park here with no deadline and no 503, and the server
// keeps accepting work it cannot finish.
IN_FLIGHT.acquireUninterruptibly();
```

Both look responsible in review: there is a pool size and there is a limit. Under overload they are two queues in a row, one unbounded, and the guessed `new Semaphore(100)` has no arithmetic to say whether it is too high or too low and counts nothing when it rejects. The Spring equivalent of the second mistake is `@ConcurrencyLimit(10)` without `policy = REJECT`.

## Checklist

When reviewing code under this skill, confirm:

- [ ] The request edge caps in-flight work with `tryAcquire()` (no wait or a few milliseconds), and a full limit answers `503` with `Retry-After` at once.
- [ ] No semaphore, pool or limiter in the request path is acquired with `acquire()`, `acquireUninterruptibly()` or a timeout close to the request deadline.
- [ ] Every limit constant has its Little's-law arithmetic (measured rate × (latency + longest stall)) in a comment, or comes from configuration with that comment.
- [ ] Every limit's rejections are countable (a counter or sampled log per limit), and a tuning change names which limit produced the `503`s it addresses.
- [ ] No `Executors.newFixedThreadPool`, `newSingleThreadExecutor` or `LinkedBlockingQueue` without capacity receives work that can arrive faster than it is processed.
- [ ] Every `ThreadPoolExecutor` has a bounded queue and a rejection policy that surfaces the rejection (not `CallerRunsPolicy` on a request thread, not `DiscardPolicy`).
- [ ] `@ConcurrencyLimit` on a request path uses `policy = ConcurrencyLimit.ThrottlePolicy.REJECT`, `@EnableResilientMethods` is present, and `InvocationRejectedException` maps to `503`.
- [ ] Each dependency has its own bound, and the database pool is not wrapped in an extra semaphore.
- [ ] Admission happens before body parsing, transactions or outbound calls, and health checks are outside it.

## When not to apply

- **Batch jobs and queue consumers.** Work pulled from a durable queue has no client waiting; bound its concurrency, but block on the semaphore or let the bounded executor's caller wait, and let the broker's redelivery handle retries.
- **A single-user internal tool.** A fixed `tryAcquire` limit is plenty; skip per-dependency bounds until there is more than one dependency that can slow down.
- **When an upstream proxy already enforces the limit.** If a load balancer or mesh caps concurrency per replica and returns `503`, keep the per-dependency bounds and rule 6, and do not add a second edge limit with a different number.
- **Per-tenant fairness.** One noisy tenant can still fill the global limit; when that matters, keep one bound per tenant or per client and answer `429` for a client over its quota.
- **Limits that must adapt.** A fixed limit is wrong after the next instance size or latency change; adaptive (AIMD) limiters fix that but are not in the JDK or Spring. Start with the fixed, commented limit and re-derive it after each load test.
