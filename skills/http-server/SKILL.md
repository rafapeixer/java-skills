---
name: http-server
description: Use when writing or reviewing a Spring Boot HTTP service on Spring MVC and Tomcat (spring-boot-starter-webmvc): setting server.tomcat.* timeouts or backlog, limiting body or upload size, returning REST errors, writing @ExceptionHandler/@ControllerAdvice, enabling virtual threads, adding liveness/readiness checks, versioning an API, or tuning graceful shutdown. Key rule: Tomcat has no size limit for JSON bodies (max-swallow-size and max-http-form-post-size do not apply): bound every body yourself, answer 413; set spring.mvc.problemdetails.enabled=true (application/problem+json), no catch-all handler turning 4xx into 500; keep connection-timeout low and keep-alive-timeout above the load balancer idle timeout; server.tomcat.accept-count ~1024, not 100, for bursty load (p99 near 1 s); preStop sleep + timeout-per-shutdown-phase must fit the grace period; liveness never checks the database; version with the mapping version attribute and spring.mvc.apiversion.*, not /v1 copies. Spring Boot 4.1, Tomcat 11.
metadata:
  verified: 2026-10-19
---

# HTTP server

## The decision

Every Spring Boot service that serves HTTP accepts the defaults of Tomcat and Spring MVC unless someone decides otherwise, and several of those defaults only hurt in production. A JSON body has no size limit at all, whatever the properties named `max-swallow-size` or `max-http-form-post-size` suggest. Errors come back in Boot's own `/error` JSON shape, or in whatever shape a hand-written catch-all handler picked, not in a standard format. Tomcat waits 60 s for a silent client. A connection timeout lowered on its own also lowers the keep-alive timeout below the load balancer's, which shows up as random 502s. The shutdown phase timeout and the Kubernetes grace period are both 30 s by default, so with a preStop sleep the pod is killed mid-request. And a liveness probe that checks the database restarts every instance during a database outage. This skill fixes how a Spring MVC server is bounded, how it reports errors, how it is probed and how it stops. Timeouts for calls the service makes are `timeouts-and-deadlines` and `outbound-http-client`; shedding load is `overload-protection`; JSON mapping rules are `json-jackson3`.

## The rule

1. Bound every request body in the application (a filter that answers 413 above a fixed size), because Tomcat and Spring MVC put no size limit on `application/json`; set the multipart and form limits explicitly as well.
2. Set `spring.mvc.problemdetails.enabled=true`, report your own errors as `ErrorResponseException` or `ProblemDetail`, and never write an `@ExceptionHandler(Exception.class)` that turns framework 4xx errors into 500s or sends exception messages to clients.
3. Set `server.tomcat.connection-timeout` to a few seconds (5 s), and always set `server.tomcat.keep-alive-timeout` with it, longer than the idle timeout of the load balancer in front (65 s behind a 60 s ALB).
4. Keep graceful shutdown on (the default), and make preStop sleep plus `spring.lifecycle.timeout-per-shutdown-phase` fit inside the orchestrator's grace period with a margin (10 s + 15 s inside 30 s).
5. Point the orchestrator's liveness probe at `/actuator/health/liveness` (or `/livez`) and readiness at `/actuator/health/readiness` (or `/readyz`), and never add external systems such as the database to the liveness group.
6. Turn on virtual threads with `spring.threads.virtual.enabled=true` (JDK 21+) and stop tuning `server.tomcat.threads.max`; bound concurrency per dependency instead.
7. Version an API with the `version` attribute on the mapping and `spring.mvc.apiversion.*` (Framework 7), not with copied `/v1` and `/v2` controllers.
8. Raise `server.tomcat.accept-count` from its default of 100 to about 1024 (below the kernel's `net.core.somaxconn`) when a proxy or bursty clients open connections faster than Tomcat accepts them.

## Why

**Rule 1.** Tomcat's [HTTP connector reference](https://tomcat.apache.org/tomcat-11.0-doc/config/http.html) says `maxPostSize` (Boot's `server.tomcat.max-http-form-post-size`, 2 MB) "is **not** a general limit on request body size for POST requests": it only applies to `application/x-www-form-urlencoded` and `multipart/form-data` parameters. `maxSwallowSize` (`server.tomcat.max-swallow-size`, 2 MB) is "the maximum number of request body bytes ... that will be swallowed by Tomcat for an aborted upload", so it only decides whether Tomcat drains a body it ignores or closes the connection. Neither one stops a controller with `@RequestBody` from reading a 2 GB JSON document into the heap. Multipart is bounded by Boot (`spring.servlet.multipart.max-file-size` 1 MB and `max-request-size` 10 MB by default); JSON is not. The example's filter rejects a declared `Content-Length` above 64 KiB before reading a byte, counts bytes for chunked bodies, and throws an `ErrorResponseException` with status 413 while the controller reads, so Spring MVC renders it like any other error. A proxy in front can enforce a limit too, but the service should not depend on it. Keep the rejected size under `max-swallow-size`'s 2 MB or the client may see a reset instead of the 413.

**Rule 2.** With [`spring.mvc.problemdetails.enabled`](https://docs.spring.io/spring-boot/reference/web/servlet.html) (default `false`), Boot registers a `ResponseEntityExceptionHandler` that renders Spring MVC's own exceptions (malformed JSON, unsupported media type, unknown route, invalid API version, `ErrorResponseException`) as [RFC 9457](https://www.rfc-editor.org/rfc/rfc9457) `application/problem+json` with the right status. Clients get one parseable format with `type`, `title`, `status` and `detail`. Boot's handler is ordered `@Order(0)` and backs off when you declare your own `ResponseEntityExceptionHandler` subclass, which is the way to customize it. A catch-all `@ExceptionHandler(Exception.class)` takes every exception that handler does not see: all of them while problem details are off (the default, as in the bad example), all of them when the advice is ordered `HIGHEST_PRECEDENCE`, and every exception of your own otherwise. A client's malformed body then becomes a 500, monitoring counts client mistakes as server failures, and `e.getMessage()` sends parser and SQL details to the caller. Map the exceptions you own to a status, and leave the rest to the framework.

**Rule 3.** [`connectionTimeout`](https://tomcat.apache.org/tomcat-11.0-doc/config/http.html) is how long Tomcat waits for the request line after accepting a connection (Tomcat's default is 60 s; Boot sets none) and, by default, between packets while reading a body. At 60 s, slow or silent clients hold connections up to the `max-connections` limit (8192). `keepAliveTimeout` "defaults to the value that has been set for the connectionTimeout attribute", so lowering one lowers both. AWS's [ALB documentation](https://docs.aws.amazon.com/elasticloadbalancing/latest/application/edit-load-balancer-attributes.html) recommends an application idle timeout "larger than the idle timeout configured for the load balancer" (60 s by default), or the load balancer may send a request on a connection the server has just closed and return a 502. The example's test opens a socket, sends nothing, and checks that Tomcat closes it.

**Rule 4.** [Graceful shutdown](https://docs.spring.io/spring-boot/reference/web/graceful-shutdown.html) "is enabled by default" with Tomcat (`server.shutdown=graceful`): Tomcat stops accepting new requests and in-flight ones may finish for `spring.lifecycle.timeout-per-shutdown-phase`, 30 s by default. Kubernetes' [grace period](https://kubernetes.io/docs/concepts/containers/container-lifecycle-hooks/) (30 s by default) "begins before the PreStop hook is executed" and covers both the hook and the shutdown. Spring Boot's [Kubernetes guidance](https://docs.spring.io/spring-boot/how-to/deployment/cloud.html) is a preStop sleep so endpoints stop routing to the pod before SIGTERM. Add the numbers: 10 s sleep + 30 s default phase = 40 s, and the pod is SIGKILLed after 30. Lower the phase (15 s) or raise `terminationGracePeriodSeconds`; do not set `server.shutdown=immediate`.

**Rule 5.** Liveness and readiness groups are on by default in Boot 4 (`management.endpoint.health.probes.enabled=true`), and `management.endpoint.health.probes.add-additional-paths=true` also serves them at `/livez` and `/readyz` on the main port, which the [actuator reference](https://docs.spring.io/spring-boot/reference/actuator/endpoints.html) recommends so the probe checks the port that serves traffic. The same page warns that liveness "should not depend on health checks for external systems": if it does, a database outage makes Kubernetes restart every instance and turns a partial outage into a full one. Readiness may include dependencies the instance cannot serve without.

**Rule 6.** With `spring.threads.virtual.enabled=true`, Tomcat runs each request on a virtual thread, and `server.tomcat.threads.max` (200) "doesn't have an effect if virtual threads are enabled". The thread pool no longer limits concurrency, so a slow dependency is no longer protected by it: put a per-dependency limit in front of it (`overload-protection`) and size the database pool deliberately (`postgres-hikari-pool`). The example's test checks that the handler runs on a virtual thread. Pinning and `ThreadLocal` caveats are in `virtual-threads`.

**Rule 7.** Spring Framework 7 [maps versions natively](https://docs.spring.io/spring-framework/reference/web/webmvc-versioning.html): `@PostMapping(path = "/orders", version = "2")`, with the version read from a header, query parameter, path segment or media type parameter, configured through `spring.mvc.apiversion.*`. An unsupported version raises `InvalidApiVersionException` (400), and `ApiVersionDeprecationHandler` can send `Deprecation` and `Sunset` headers. Copied controllers under `/v1` and `/v2` accept any version string the router happens to match, drift apart, and double the code for every fix.

**Rule 8.** `acceptCount` is the listen backlog: "When this queue is full, the operating system may actively refuse additional connections or those connections may time out. The default value is 100" ([Tomcat 11 HTTP connector](https://tomcat.apache.org/tomcat-11.0-doc/config/http.html)); Spring Boot sets it with `server.tomcat.accept-count`. When the application stalls for a moment, a proxy such as nginx opens new upstream connections in a burst, the backlog overflows, Linux drops the SYN, and the client retransmits it after about a second. The symptom is a p99 near 1 s plus a few client timeouts and 504s the application never logs; confirm it with `ListenOverflows` and `ListenDrops` in `/proc/net/netstat` inside the container. Measured in benchmarks/orders-api (TUNING.md): every overloaded run had a p99 near 1,040 ms and `ListenOverflows 95` after a spike; with 1024 the counter stayed at 0, the max latency at 4,000 req/s fell from 1,234 to 458 ms and client timeouts from 67 to 7. The kernel caps the backlog at `net.core.somaxconn` (4096 in that container), so a larger value has no effect. This does not replace admission control (see `overload-protection`): the backlog only stops the kernel from dropping connections before Tomcat can answer them, with a fast 503 if need be. The example's test reads the running connector's `acceptCount`.

## Good example

See [`examples/good/`](examples/good/).

```java
public ServletInputStream getInputStream() throws IOException {
if (getContentLengthLong() > MAX_BODY_BYTES) {
throw new BodyTooLargeException(); // declared too large: do not read a byte
}
return new LimitedInputStream(super.getInputStream()); // chunked: count while reading
}
```

```java
BodyTooLargeException() {
super(HttpStatus.CONTENT_TOO_LARGE);
getBody().setDetail("Request body is larger than " + MAX_BODY_BYTES + " bytes.");
}
```

```properties
spring.mvc.problemdetails.enabled=true
spring.threads.virtual.enabled=true
server.tomcat.connection-timeout=5s
server.tomcat.keep-alive-timeout=65s
server.tomcat.accept-count=1024
spring.lifecycle.timeout-per-shutdown-phase=15s
management.endpoint.health.probes.add-additional-paths=true
spring.mvc.apiversion.use.header=API-Version
```

```java
client.post().uri("/orders").contentType(MediaType.APPLICATION_JSON).body(big)
.exchange()
.expectStatus().isEqualTo(413)
.expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
.expectBody().jsonPath("$.status").isEqualTo(413);
```

`OrdersApplicationTest` starts the real Tomcat on a random port (`@SpringBootTest` with `@AutoConfigureRestTestClient`, from `spring-boot-resttestclient`) and checks that a 128 KiB JSON body gets a 413 as `application/problem+json`, that malformed JSON gets a 400 problem, that the handler runs on a virtual thread, that `API-Version: 2` selects the second handler and `API-Version: 9` gets a 400 problem, that `/livez` and `/readyz` answer on the main port, that a silent connection is closed by the server, and that the running connector's accept backlog is 1024.

## Bad example

See [`examples/bad/`](examples/bad/).

```properties
# MISTAKE: read as "max request body = 1MB". It is only how much of an ignored body
# Tomcat discards before closing the connection; JSON bodies stay unlimited.
server.tomcat.max-swallow-size=1MB
```

```properties
# MISTAKE: lowered without keep-alive-timeout, which defaults to it: idle connections now
# close after 5 s, before the load balancer's 60 s idle timeout, and reused ones fail with 502s.
server.tomcat.connection-timeout=5s

# MISTAKE: longer than the 30 s grace period; the pod is SIGKILLed mid-request.
spring.lifecycle.timeout-per-shutdown-phase=60s

# MISTAKE: liveness depends on the database; a database outage restarts every instance.
management.endpoint.health.group.liveness.include=livenessState,db
```

```java
// MISTAKE: a catch-all that turns malformed JSON (400), unknown routes (404) and
// 413s into 500s, in an ad hoc shape, with the raw exception message sent to clients.
@ExceptionHandler(Exception.class)
ResponseEntity<Map<String, String>> handle(Exception e) {
```

The project also leaves `server.tomcat.accept-count` at 100, tunes `server.tomcat.threads.max` with virtual threads on, copies the controller for `/v1/orders` and `/v2/orders`, and leaves `spring.mvc.problemdetails.enabled` at its `false` default. It all compiles, starts, and passes a happy-path test.

## Checklist

When reviewing code under this skill, confirm:

- [ ] Every endpoint that reads a body is behind an application-level size limit that answers 413, and nobody relies on `max-swallow-size` or `max-http-form-post-size` for JSON.
- [ ] `spring.servlet.multipart.max-file-size` and `max-request-size` are set explicitly when the service accepts uploads.
- [ ] `spring.mvc.problemdetails.enabled=true`, and a test asserts `application/problem+json` for at least one error.
- [ ] No `@ExceptionHandler(Exception.class)` or `Throwable.class` that maps everything to 500 or returns `e.getMessage()`.
- [ ] `server.tomcat.connection-timeout` is a few seconds, and `server.tomcat.keep-alive-timeout` is set next to it, above the load balancer's idle timeout.
- [ ] `server.shutdown` is not `immediate`, and preStop sleep plus `spring.lifecycle.timeout-per-shutdown-phase` is below `terminationGracePeriodSeconds`.
- [ ] Liveness and readiness probes point at the health groups, and the liveness group includes no database, cache or remote API.
- [ ] With virtual threads on, there is no `server.tomcat.threads.max` tuning and slow dependencies have their own concurrency limit.
- [ ] New API versions use `version = "..."` on the mapping with `spring.mvc.apiversion.*`, not copied controllers.
- [ ] `server.tomcat.accept-count` is raised (about 1024, below `net.core.somaxconn`) for a service behind a proxy or with bursty traffic, and a p99 near 1 s is checked against `ListenOverflows`.

## When not to apply

- **Streaming uploads** (large files to object storage). Do not buffer them under a 64 KiB limit; give that one endpoint its own larger limit, stream to the destination, and keep `connection-timeout` as the bound between packets.
- **A proxy that already enforces a body limit** (an API gateway or ingress with a configured maximum). Keep the application limit anyway; set it at or above the proxy's so the proxy's message wins in practice.
- **Existing clients that parse the Boot `/error` JSON shape.** Switching to problem details changes the response body; announce it, or enable it per API version.
- **Not on Kubernetes** (a VM behind a load balancer with its own drain delay). Use that platform's deregistration delay instead of a preStop sleep, and apply the same arithmetic.
- **Reactive services on WebFlux and Netty.** The properties differ (`spring.webflux.*`, `server.netty.*`, `spring.http.codecs.max-in-memory-size` bounds decoded bodies); the rules on problem details, probes and shutdown still apply.
- **Public versioned URLs already in use.** Keep `/v1` paths that clients depend on; configure `spring.mvc.apiversion.use.path-segment` so the versions come from the path but map to one controller.
