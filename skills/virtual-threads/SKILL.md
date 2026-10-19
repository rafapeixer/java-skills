---
name: virtual-threads
description: Use when writing or reviewing Java code that runs blocking work concurrently (fan-out over a list, one task per request, calls to HTTP or JDBC from many threads), enabling virtual threads in Spring Boot, sizing a thread pool, limiting concurrent calls to a dependency, or seeing pinned virtual threads. Key rule: one virtual thread per task with Executors.newVirtualThreadPerTaskExecutor() in try-with-resources, never pool virtual threads; bound access to a scarce resource with a Semaphore (or the connection pool itself), not a thread pool size; on JDK 25+ synchronized no longer pins (JEP 491), but blocking in class initializers and native frames still does, detect it with the JFR event jdk.VirtualThreadPinned; share immutable objects instead of ThreadLocal caches, and build HttpClient with a virtual-thread executor; in Spring Boot 4 set spring.threads.virtual.enabled=true and spring.main.keep-alive=true; Structured Concurrency is still preview, never the default.
metadata:
  verified: 2026-10-19
---

# Virtual threads

## The decision

Most Java backend code is blocking: it waits on a socket, a JDBC query or an HTTP call. The decision is how to run many of those waits at once. The old answer was a fixed pool of platform threads, which caps throughput at pool size divided by latency (200 threads at 1 s per task is 200 tasks/s) and doubles as an accidental concurrency limit for every dependency. Virtual threads, final since JDK 21 ([JEP 444](https://openjdk.org/jeps/444)), remove the first problem: they are cheap enough to create one per task. They do not remove the second; code that used the pool size as its only limit now sends unbounded concurrency to the database or a partner API. This skill fixes how tasks get threads, where the concurrency limit moves to, and which old habits (pools, `ThreadLocal` caches, slow class initializers) turn into bugs.

## The rule

1. Run each concurrent task in its own virtual thread with `Executors.newVirtualThreadPerTaskExecutor()` opened in try-with-resources, so `close()` waits for every task.
2. Never pool virtual threads: no `newFixedThreadPool(n, Thread.ofVirtual().factory())`, no reuse, no thread-count tuning.
3. Bound access to a scarce resource with a `Semaphore` around the call (or the resource's own pool, such as HikariCP's `maximumPoolSize`), sized from what the resource can serve, not with the size of a thread pool.
4. Share immutable objects (`DateTimeFormatter`, an `HttpClient` built with `.executor(Executors.newVirtualThreadPerTaskExecutor())`, `ObjectMapper`) across threads instead of caching one per thread in a `ThreadLocal`.
5. Keep blocking I/O out of static initializers and native callbacks, the cases that still pin a carrier on JDK 24+, and watch the JFR event `jdk.VirtualThreadPinned` in production.
6. In Spring Boot 4, enable them with `spring.threads.virtual.enabled=true` together with `spring.main.keep-alive=true`, and delete the thread-pool properties that no longer apply.
7. Treat Structured Concurrency (`StructuredTaskScope`) as a preview API: use it only behind `--enable-preview` on the exact JDK it targets, never in code that must run on the floor JDK 25 unchanged.

## Why

**Rule 1.** [JEP 444](https://openjdk.org/jeps/444) reports that 10,000 tasks that each sleep one second finish in about one second on virtual threads, about 10,000 tasks/s, against 200 tasks/s for a pool of 200 platform threads. The good example runs that experiment and fails if it takes more than 30 s. The executor's `close()` waits for all submitted tasks, so the try-with-resources block is the lifetime of the work: nothing leaks past it, and an exception in the block still waits for the tasks before propagating. Virtual threads are always daemon threads (JEP 444), so a task submitted to an executor nobody closes simply dies when `main` returns.

**Rule 2.** The [Oracle virtual threads guide](https://docs.oracle.com/en/java/javase/25/core/virtual-threads.html) says it plainly: "virtual threads aren't scarce and therefore should never be pooled". A pool of virtual threads keeps the cost of a pool (a shared queue, a size to tune, tasks waiting behind unrelated tasks) and gives back nothing, because creating a virtual thread is cheaper than handing work to a pooled one. When a pool of virtual threads appears in a review, it is almost always rule 3 written the wrong way.

**Rule 3.** With platform threads, a pool of 20 threads also meant at most 20 concurrent calls to whatever those threads touched. With virtual threads there is no such ceiling, so 10,000 requests become 10,000 concurrent calls to a pricing API that serves 50. The guide's answer is a `Semaphore` around the call; only callers of that one resource wait, while the rest of the application runs unthrottled. The guide also notes that a database connection pool is already a semaphore, so do not add a second one in front of HikariCP; size the pool itself (see `postgres-hikari-pool`; HikariCP 7.1 includes fixes for spinning under virtual threads). Size the permits from the dependency's capacity and Little's law (see `overload-protection`), and write the number's origin in a comment. A blocking `acquire()` is right for batch fan-out; at a request edge where callers have deadlines, use `tryAcquire` with a timeout and reject (also `overload-protection`).

**Rule 4.** A `ThreadLocal` used as a per-thread cache assumed few long-lived threads. Virtual threads are never reused by unrelated tasks, so every task builds its own copy: a million concurrent requests mean a million `SimpleDateFormat` instances, plus their garbage. The guide calls this use "fundamentally at odds with virtual threads" and recommends an immutable shared object instead (`DateTimeFormatter` for dates). For request context such as a user or trace id, use `ScopedValue` (see `scoped-values`). A shared JDK `HttpClient` still runs its own response handling on a default pool of platform threads unless you give it an executor; measured in benchmarks/orders-api (TUNING.md), 66% of the client's CPU samples were on that pool, and a virtual-thread executor cut CPU per request by 16% (see [`outbound-http-client`](../outbound-http-client/SKILL.md)).

**Rule 5.** A pinned virtual thread blocks its carrier platform thread, and there are only about as many carriers as CPUs, so a few pinned threads stall everything. Before JDK 24, blocking inside `synchronized` pinned. [JEP 491](https://openjdk.org/jeps/491) (JDK 24) removed that, so on the floor JDK 25 you do not need to rewrite `synchronized` into `ReentrantLock` for virtual threads; the good example blocks 10,000 threads inside `synchronized` and still finishes in about a second. Pinning remains when a virtual thread blocks during class loading or inside a class initializer, waits for another thread to initialize a class, or blocks in Java code called back from native code (JNI, or a foreign function upcall). JEP 491 also removed the `jdk.tracePinnedThreads` system property; setting it does nothing. Detection is JFR: `jdk.VirtualThreadPinned` is enabled by default with a 20 ms threshold, so a normal recording (`jcmd <pid> JFR.start`) shows each long pin with its stack (see `profiling-jfr`). `jcmd <pid> Thread.dump_to_file -format=json <file>` lists virtual threads too.

**Rule 6.** Per the [Spring Boot 4.1 reference](https://docs.spring.io/spring-boot/reference/features/spring-application.html), `spring.threads.virtual.enabled=true` requires Java 21 or later, and "Java 24 or later is strongly recommended" (the JEP 491 fix). Once enabled, "properties which configure thread pools don't have an effect anymore", so remove `server.tomcat.threads.max` and similar settings instead of leaving tuning that lies. Because virtual threads are daemon threads, a JVM whose only work is `@Scheduled` jobs on virtual threads can exit; Spring recommends `spring.main.keep-alive=true` to keep it running. Enabling virtual threads changes nothing about limits: the dependencies that the Tomcat pool used to protect now need rule 3.

**Rule 7.** Structured Concurrency is still a preview API in every release this repository supports: fifth preview in JDK 25 ([JEP 505](https://openjdk.org/jeps/505)), sixth in JDK 26 ([JEP 525](https://openjdk.org/jeps/525)), seventh in JDK 27 ([JEP 533](https://openjdk.org/jeps/533)), which changed the API again (a third type parameter, `open(UnaryOperator)`, `timeout()`). The final version is integrated for JDK 28 ([JEP 543](https://openjdk.org/jeps/543)). Preview code compiles only with `--enable-preview --release N` for the running JDK, so it breaks on every upgrade. Until JDK 28 is the floor, write fan-out with the executor in try-with-resources and `Future.get()`, and cancel the remaining futures yourself when one fails.

The cost: a `Semaphore` is one more number to size, and the work that a thread pool silently throttled now reaches dependencies at full speed until you size it. Virtual threads help only with blocking work; CPU-bound tasks still need about one platform thread per core.

## Good example

See [`examples/good/`](examples/good/).

```java
try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
    for (int i = 0; i < 10_000; i++) {
        executor.submit(() -> {
            Thread.sleep(Duration.ofSeconds(1));
            return null;
        });
    }
} // close() waits for every task, so nothing outlives this block
```

Ten thousand blocking tasks, one thread each, and the block ends only when all are done. The concurrency limit for the dependency lives next to the call:

```java
// The pricing service accepts at most 10 concurrent calls per replica. That limit
// is a Semaphore around the call, not the size of a thread pool.
static final int PRICING_MAX_CONCURRENT = 10;
static final Semaphore pricingPermits = new Semaphore(PRICING_MAX_CONCURRENT);

static String quote(int productId) throws InterruptedException {
    pricingPermits.acquire();
    try {
        return callPricingService(productId);
    } finally {
        pricingPermits.release();
    }
}
```

The example checks its own outcome: both batches of 10,000 one-second tasks (one of them blocking inside `synchronized`) finish within a generous 30 s, 1,000 quotes never put more than 10 calls on the pricing service at once, and no permit leaks.

## Bad example

See [`examples/bad/`](examples/bad/).

```java
// MISTAKE: pooling virtual threads. Virtual threads are cheap and should never be
// pooled; the pool size here is really a concurrency limit for the pricing service,
// hidden in a thread factory and shared by every other task that uses this pool.
static final ExecutorService PRICING_POOL =
        Executors.newFixedThreadPool(10, Thread.ofVirtual().factory());

// MISTAKE: caching an expensive object per thread. Virtual threads are never reused,
// so every task builds its own SimpleDateFormat; with a million threads that is a
// million formatters on the heap. Share one immutable DateTimeFormatter instead.
static final ThreadLocal<SimpleDateFormat> FORMAT =
        ThreadLocal.withInitial(() -> new SimpleDateFormat("yyyy-MM-dd"));
```

```java
// MISTAKE: the executor is never closed. Nothing waits for these tasks, and the
// JVM exits under them because virtual threads are daemon threads.
ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
```

```java
// MISTAKE: blocking in a class initializer still pins the carrier on JDK 24+,
// and so does every virtual thread that waits for this class to finish
// initializing. Load slow data lazily in a normal method, not in <clinit>.
Thread.sleep(1_000);
```

Each of these is what a migration from platform threads produces when it changes only the thread factory: the old pool survives with a virtual factory, the old per-thread cache survives, and a fire-and-forget executor that used to be kept alive by non-daemon pool threads now loses work at shutdown.

## Checklist

When reviewing code under this skill, confirm:

- [ ] Concurrent blocking tasks run on `Executors.newVirtualThreadPerTaskExecutor()` (or `Thread.ofVirtual()`), and every executor is closed by try-with-resources or an owner that waits for it.
- [ ] No executor combines a fixed or cached pool with a virtual thread factory, and no code tunes a virtual thread count.
- [ ] Every scarce dependency reached from virtual threads has an explicit bound: a `Semaphore` sized in a commented constant, or the dependency's own pool (no extra semaphore in front of HikariCP).
- [ ] No `ThreadLocal` caches an expensive or mutable object (`SimpleDateFormat`, buffers, parsers); shared immutable instances are used instead, and a shared `HttpClient` has a virtual-thread executor.
- [ ] No static initializer or native callback performs blocking I/O or long waits.
- [ ] Production runs keep JFR on, and `jdk.VirtualThreadPinned` events are checked after enabling virtual threads; no config still sets `jdk.tracePinnedThreads`.
- [ ] Spring Boot apps set `spring.threads.virtual.enabled=true` and `spring.main.keep-alive=true`, on JDK 25+, with stale thread-pool properties removed.
- [ ] `StructuredTaskScope` appears only in code built with `--enable-preview` for one specific JDK, never in code that must build on the floor.

## When not to apply

- **CPU-bound work** (image processing, compression, large in-memory sorts): virtual threads add nothing; use a pool of about one platform thread per core, or a parallel stream, and keep it bounded.
- **Heavy native or JNI work on the request path**: frames called back from native code still pin. Run that work on a small dedicated platform-thread pool and keep virtual threads for the I/O around it.
- **Batch fan-out with a durable queue behind it**: a blocking `Semaphore.acquire()` is the right bound there, because nobody is waiting on a deadline; at a request edge, reject instead (see `overload-protection`).
- **A library you cannot change that caches in `ThreadLocal`**: measure heap with many concurrent tasks before switching; if it grows per thread, cap the concurrency that reaches that library with a `Semaphore`.
- **Experiments on a single JDK**: Structured Concurrency behind `--enable-preview` is fine in a spike or internal tool pinned to one release, as long as nobody copies it into floor-JDK code.
