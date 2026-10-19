---
name: observability-otel
description: Use when adding or reviewing OpenTelemetry tracing, metrics or log correlation in a Java or Spring Boot service, attaching the OTel Java agent, choosing between the agent, Spring Boot 4's spring-boot-starter-opentelemetry and the OTel Spring Boot starter, configuring sampling, service.name or OTLP export, creating spans or Micrometer timers by hand, choosing span names or metric tags, or when the metrics bill grows. Key rule: the OTel Java agent (2.32.0) is the default and application code uses opentelemetry-api only, never its own SDK; Boot 4's starter only for native images or where no agent is allowed, never both; set otel.service.name; sample parentbased_traceidratio, never a bare ratio; fixed span names, ids only as span attributes; RED metrics as Micrometer Timers tagged only from closed sets (method, route template, outcome), never user ids or raw URIs, plus a MeterFilter.maximumAllowableTags backstop; end spans in finally with recordException and ERROR status; trace_id and span_id on every log line.
metadata:
  verified: 2026-10-18
---

# Observability with OpenTelemetry

## The decision

Once a Java service calls other services, logs cannot say where a slow request spent its time, so you add OpenTelemetry. The decision is how it gets into the process and what you let into names and labels. Java offers three ways in and they do not mix: the OpenTelemetry Java agent, Spring Boot 4's own `spring-boot-starter-opentelemetry` (Micrometer Observation exported through the OTel SDK), and the OTel project's `opentelemetry-spring-boot-starter`. The expensive mistakes are easy: two of them active at once, an SDK built by hand in a service that already runs the agent, no `service.name` so every service is `unknown_service:java`, a sampler that ignores the caller and breaks traces, and a metric tag taken from the request that turns into one time series per user. This skill picks the agent as the default, says when to take Boot's starter instead, and fixes what is allowed into span names, metric tags and log lines. How logs are written belongs to [`logging-structured`](../logging-structured/SKILL.md); HTTP server timeouts and shutdown to [`http-server`](../http-server/SKILL.md).

## The rule

1. Attach the OpenTelemetry Java agent (2.32.0) with `-javaagent` or `JAVA_TOOL_OPTIONS`, and let application code depend on `opentelemetry-api` only, never on an SDK it builds itself.
2. Use Spring Boot 4's `spring-boot-starter-opentelemetry` instead only for a native image or where no agent may be attached, and never run it, the OTel Spring Boot starter and the agent together.
3. Set `otel.service.name` and the deployment's resource attributes in config for every service, and treat `unknown_service:java` in the backend as a bug.
4. Sample with `parentbased_traceidratio` and an explicit ratio (`management.tracing.sampling.probability` on Boot's starter), never a bare ratio sampler.
5. Name spans with fixed strings or route templates, and put ids only in span attributes, never in span names or metric tags.
6. Record rate, errors and duration with one Micrometer `Timer` per operation whose tags come from closed sets (method, route template, status, outcome), and install `MeterFilter.maximumAllowableTags` as a backstop.
7. Start manual spans with `makeCurrent()` in try-with-resources, and in `catch` call `recordException` and `setStatus(ERROR)`, and `end()` in `finally`.
8. Print `trace_id` and `span_id` on every log line (`traceId`/`spanId` with Boot's starter), so a log line leads to its trace.

## Why

**Versions.** OpenTelemetry Java instrumentation 2.32.0 (October 2026) bundles the OTel Java SDK and API 1.66.0; the example pins `opentelemetry-bom` 1.66.0 so the API the code compiles against matches what the agent provides. Spring Boot 4.1.1 manages Micrometer 1.17.1, Micrometer Tracing 1.7.1 and OpenTelemetry 1.62.0 for its own starter.

**Rule 1.** The OpenTelemetry docs say "the default choice for instrumenting Spring Boot applications is the OpenTelemetry Java agent", which has "more out of the box instrumentation" than the starter ([Spring Boot starter](https://opentelemetry.io/docs/zero-code/java/spring-boot-starter/)). It instruments the HTTP server and clients, JDBC, Kafka and executors by bytecode, propagates `traceparent` on every hop and exports traces, metrics and logs over OTLP (`http/protobuf` by default in agent 2.x) with no code. Your code adds business spans through the API: `GlobalOpenTelemetry` returns the agent's SDK, and with no agent the API is a no-op, so tests and local runs need nothing. Building an `OpenTelemetrySdk` in application code next to the agent creates a second pipeline with its own sampler and no exporter or resource unless you rebuild all of it, which is what the bad example does. Download the jar from the [instrumentation releases](https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases) and pin its version in the image like any other dependency.

**Rule 2.** The agent cannot run in a GraalVM native image, adds startup time, and may conflict with another vendor's agent; those are the three cases the OTel docs give for a starter. On Boot 4, prefer Boot's own `spring-boot-starter-opentelemetry`: the Boot 4.0 release notes say it "brings in all necessary dependencies to export metrics and traces over OTLP" and auto-configures the OpenTelemetry SDK, and it reuses the Micrometer Observation instrumentation Spring already ships. The OTel project's `opentelemetry-spring-boot-starter` documents support for Spring Boot 2.6+ and 3.1+ and native images ([getting started](https://opentelemetry.io/docs/zero-code/java/spring-boot-starter/getting-started/)), so it is the no-agent path for Boot 3 services. Boot 4 renamed `management.tracing.enabled` to `management.tracing.export.enabled` ([4.0 release notes](https://github.com/spring-projects/spring-boot/wiki/Spring-Boot-4.0-Release-Notes)); configuration copied from Boot 3 silently does nothing. Running two of these at once records every HTTP request twice, as two unrelated server spans and two sets of metrics.

**Rule 3.** `service.name` is how every backend groups telemetry. The agent's default is `unknown_service:java` ([SDK configuration](https://opentelemetry.io/docs/languages/java/configuration/)), so ten services without it are one service in the UI and their metrics collide. Set `otel.service.name` (it takes precedence over `service.name` in `otel.resource.attributes`) and add `service.namespace`, `service.version` and `deployment.environment.name` through `otel.resource.attributes` or `OTEL_RESOURCE_ATTRIBUTES` from the deployment. The agent reads a properties file named by `otel.javaagent.configuration-file`; system properties and environment variables override it ([agent configuration](https://opentelemetry.io/docs/zero-code/java/agent/configuration/)), so the file holds defaults and the deployment holds per-environment values. On Boot's starter, set `management.opentelemetry.resource-attributes`, which is merged with `OTEL_SERVICE_NAME` and `OTEL_RESOURCE_ATTRIBUTES` and wins over them ([Boot observability](https://docs.spring.io/spring-boot/reference/actuator/observability.html)).

**Rule 4.** The agent's default sampler is `parentbased_always_on`: it records every trace, which is a bill decision nobody made. A bare ratio sampler decides for itself whether to keep a span, ignoring the caller's `sampled` flag, so a trace kept upstream loses its spans here and a trace dropped upstream gets orphan spans here. `parentbased_traceidratio` follows the caller when there is one and applies the ratio only to new traces, so a trace is either complete or absent; the example's test shows a ratio of 0 still keeps a child of a sampled caller. Boot's starter already defaults to `parent-based-trace-id-ratio` with probability 0.1 ([Boot tracing](https://docs.spring.io/spring-boot/reference/actuator/tracing.html)); write the value down anyway so it is a decision. Keeping every error and slow trace needs tail sampling in an OpenTelemetry Collector, with export at ratio 1 to that Collector.

**Rule 5.** Backends index span names to group operations: `charge customer-42` and `charge customer-43` are two operations, and p99 per endpoint cannot be computed. The agent names HTTP server spans from the route template (`GET /orders/{id}`), not the raw path; manual spans should do the same with a fixed name. Span attributes are stored per span, not aggregated, so `customer.id` on a span is fine and is how you find one customer's traces. Boot's observability docs draw the same line: low-cardinality key values go to metrics and traces, high-cardinality ones "will only be added to traces".

**Rule 6.** Every distinct combination of tag values is its own time series, and a timer is a histogram with many buckets per series. Micrometer's docs warn to "beware of the potential for tag values coming from user-supplied sources to blow up the cardinality of a metric" and to "normalize and add bounds to user-supplied input" ([naming](https://docs.micrometer.io/micrometer/reference/concepts/naming.html)). A customer id tag multiplies storage by the number of customers; a raw URI tag lets a scanner create thousands of series in minutes. One timer with `method`, `outcome` (`approved`, `declined`, `error`) and, for HTTP, the route template gives rate (count), errors (count where outcome is error) and duration from a bounded set; the example's test makes 1,000 charges for 1,000 customers and still has one series. Spring's `http.server.requests` already uses the route template as `uri`, "falling back to `REDIRECTION` for 3xx responses, `NOT_FOUND` for 404 responses" ([Spring Framework observability](https://docs.spring.io/spring-framework/reference/integration/observability.html)), so never replace it with the raw path. `maximumAllowableTags` "places an upper bound on the number of tags produced by the matching series" ([meter filters](https://docs.micrometer.io/micrometer/reference/concepts/meter-filters.html)); with `MeterFilter.deny()` as the action, new series past the cap are dropped instead of growing the bill.

**Rule 7.** A span that is never ended is never exported, so the request that failed is exactly the one missing from the trace. `makeCurrent()` makes the span the parent of the agent's JDBC and HTTP client spans inside the block, and try-with-resources closes the scope on every path. `recordException` adds an `exception` event with type, message and stack trace; `setStatus(ERROR)` is what error-rate queries and tail sampling look for. Use a low-cardinality description such as the exception class.

**Rule 8.** The agent injects `trace_id`, `span_id` and `trace_flags` into the MDC of Logback, Log4j 2 and Log4j 1 ([logger MDC instrumentation](https://github.com/open-telemetry/opentelemetry-java-instrumentation/blob/main/docs/logger-mdc-instrumentation.md)), but the default Boot pattern does not print those keys, so set `logging.pattern.correlation` (JSON structured logging includes MDC on its own). Boot's starter with Micrometer Tracing puts `traceId` and `spanId` in the MDC and prints them by default. The agent also exports logs over OTLP (`otel.logs.exporter` defaults to `otlp`); keep stdout logs as well, so a broken OTLP pipeline does not leave you with none.

The cost: the agent adds startup time and a jar to manage, sampling means most traces are not kept, and bounded tags mean some questions are answered from traces and logs, not metrics. Each is cheaper than a telemetry bill driven by user ids or traces that stop at the first hop.

## Good example

See [`examples/good/`](examples/good/).

```properties
otel.service.name=checkout
otel.resource.attributes=service.namespace=shop,deployment.environment.name=production
otel.traces.sampler=parentbased_traceidratio
otel.traces.sampler.arg=0.1
```

```java
        Span span = tracer.spanBuilder("payment.charge")
                .setSpanKind(SpanKind.INTERNAL)
                .setAttribute("payment.method", method.name().toLowerCase())
                .setAttribute("customer.id", customerId)
                .startSpan();
        Timer.Sample sample = Timer.start(registry);
        String outcome = "error";
        try (Scope ignored = span.makeCurrent()) {
            Receipt receipt = gateway.charge(customerId, cents);
            outcome = receipt.approved() ? "approved" : "declined";
            return receipt;
        } catch (RuntimeException e) {
            span.recordException(e);
            span.setStatus(StatusCode.ERROR, e.getClass().getSimpleName());
            throw e;
        } finally {
```

```java
            sample.stop(Timer.builder("payment.charge.duration")
                    .tag("method", method.name().toLowerCase())
                    .tag("outcome", outcome)
                    .register(registry));
            span.end();
```

```java
                .meterFilter(MeterFilter.maximumAllowableTags("http.server.requests", "uri", 100, MeterFilter.deny()))
```

```properties
logging.pattern.correlation=[%X{trace_id:-},%X{span_id:-}]
```

The service takes an `OpenTelemetry` (the agent's, through `GlobalOpenTelemetry`, in production) and a `MeterRegistry`, so the tests pass an SDK with an `InMemorySpanExporter` and a `SimpleMeterRegistry`. They check the span's name, attributes and error status, that 1,000 customers make one timer series, that the guard stops a leaked `uri` tag at 100 values, that a parent-based sampler keeps a child of a sampled caller even at ratio 0, and that the agent's properties file names the service and samples by ratio.

## Bad example

See [`examples/bad/`](examples/bad/).

```java
    // MISTAKE: a second SDK built in application code while the agent is attached. Its spans
    // bypass the agent's exporter and configuration (this one has no exporter at all), and its
    // default Resource names the service unknown_service:java.
    private static final Tracer TRACER = OpenTelemetrySdk.builder()
            .setTracerProvider(SdkTracerProvider.builder()
                    // MISTAKE: ratio without parentBased. This service drops spans of traces the
                    // caller kept and keeps spans of traces it dropped: broken traces either way.
                    .setSampler(Sampler.traceIdRatioBased(0.1))
```

```java
        registry.timer("payment.charge.duration",
                        "customer", customerId, "uri", requestUri, "method", method)
                .record(System.nanoTime() - start, java.util.concurrent.TimeUnit.NANOSECONDS);
        // MISTAKE: end() only on the happy path. An exception from the gateway leaves the span
        // open: never exported, no error status, and the failure is invisible in the trace.
        span.end();
```

The file also names the span `"charge " + customerId`. Everything here works in a demo with ten users and no errors; the bill and the broken traces arrive with production traffic.

## Checklist

When reviewing code under this skill, confirm:

- [ ] Exactly one of the agent, Boot's `spring-boot-starter-opentelemetry` or the OTel Spring Boot starter is active, and the agent is the one unless the service is a native image or cannot take an agent.
- [ ] Application code depends on `opentelemetry-api` (and Micrometer) only, and no class builds an `OpenTelemetrySdk` outside tests.
- [ ] `otel.service.name` (or `management.opentelemetry.resource-attributes`) and the deployment environment are set for every service.
- [ ] The sampler is parent-based with an explicit ratio, and Boot 4 config uses `management.tracing.export.enabled`, not the Boot 3 name.
- [ ] No span name and no metric tag contains an id, an email, a raw path or a query string, and HTTP metrics use the route template.
- [ ] Each custom operation has one timer whose tags are closed sets including an outcome, and `maximumAllowableTags` guards tags that could grow.
- [ ] Every manual span is made current in try-with-resources, records exceptions with `ERROR` status, and ends in `finally`.
- [ ] Log lines carry the trace and span id, and stdout logging stays on next to OTLP log export.

## When not to apply

- **Native image or no agent allowed.** Use Boot 4's `spring-boot-starter-opentelemetry` (or the OTel Spring Boot starter on Boot 3) and remove `-javaagent`; rules 3 to 8 still apply through Boot's `management.*` properties.
- **A vendor agent already runs.** If the platform requires another APM agent, follow its docs instead of adding the OTel agent; two bytecode agents instrumenting the same libraries can conflict.
- **Plain JDK service without Spring.** The agent still works; if you cannot attach it, build the SDK once at startup with `opentelemetry-sdk-extension-autoconfigure`, register it globally, and close it on shutdown so the last batch is flushed.
- **Low-traffic internal tools.** A ratio of 1.0 is fine when volume is small; decide it explicitly instead of inheriting `parentbased_always_on`.
- **Debugging one customer's problem.** Search traces by the `customer.id` span attribute or logs by the id; never add it as a metric tag, not even temporarily.
