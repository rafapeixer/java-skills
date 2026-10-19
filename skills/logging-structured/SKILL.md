---
name: logging-structured
description: Use when writing or reviewing logging in a Java or Spring Boot service: adding log statements with SLF4J, configuring logback or logging.* properties, choosing log levels, switching to JSON logs (ECS, Logstash, GELF), adding a request id or trace id to every line, logging exceptions, or logging objects that may hold secrets or personal data. Key rule: set logging.structured.format.console=ecs (or logstash, gelf) so each event is one JSON line; log with SLF4J placeholders or the fluent addKeyValue API, never string concatenation or System.out; put the request id in the MDC with try-with-resources (MDC.putCloseable) so it is removed; never log secrets, tokens, card numbers or personal data, and give records that hold them a toString that masks them; log each event once, where it is handled, never log-and-rethrow; ERROR only for failures someone must act on, WARN for expected client-side failures. Spring Boot 4.1, SLF4J 2.
metadata:
  verified: 2026-10-19
---

# Structured logging

## The decision

Every Java service logs, and in Spring Boot the library is already chosen (SLF4J over Logback), so the decision is what a log line contains and in what shape. The defaults point the wrong way: Boot writes human-readable text, which the log platform has to parse with a regex and which splits a stack trace into thirty unrelated lines; string concatenation compiles exactly like placeholders; a record's generated `toString` prints every field, card numbers included; and nothing stops a method from logging an exception and rethrowing it to a caller that logs it again. The failures show up when the logs are needed: an incident where no line carries the request id, a query on `order.id` that matches nothing because the id is inside the message text, an alert storm of ERROR lines for declined cards, or a card number found in the log store during an audit. This skill fixes the output format, how fields and correlation ids get onto each line, what never goes in, and how many times one event is logged. Where an exception is handled at all is `exceptions`; traces and metrics are `observability-otel`.

## The rule

1. Set `logging.structured.format.console` to `ecs` (or `logstash` or `gelf`, whichever your platform ingests) so every event is one JSON object per line on stdout.
2. Log through an SLF4J `Logger` with `{}` placeholders or the fluent `atInfo().addKeyValue(...)` API, never string concatenation, `System.out`, `System.err` or `printStackTrace()`.
3. Put the request id (and any other per-request context) in the MDC at the boundary with `try (var ignored = MDC.putCloseable(...))`, never with a bare `MDC.put`.
4. Never log secrets, tokens, passwords, card numbers or personal data, and give every type that holds one a `toString` that masks it.
5. Log each event once, at the place that handles it; code that throws or rethrows does not log.
6. Use ERROR only for failures an operator must act on, WARN for expected failures caused by the client or a dependency, INFO for business events, and DEBUG off in production except for one logger at a time.

## Why

**Rule 1.** Spring Boot writes [ECS, GELF or Logstash JSON](https://docs.spring.io/spring-boot/reference/features/logging.html) when `logging.structured.format.console` (or `.file`) names the format, and "adds every key value pair contained in the MDC to the JSON object", as well as pairs added with SLF4J's `addKeyValue`. A JSON line keeps a multi-line stack trace in one event and makes each field queryable without parsing. ECS output nests dotted keys, so `request.id` becomes `{"request":{"id":"req-1"}}`, as the example's test reads it. `logging.structured.ecs.service.name` defaults to `spring.application.name`. The same page notes that full stack traces "may be costly to process by your log ingestion system"; `logging.structured.json.stacktrace.max-length` bounds them. Keep the text format for local development with a profile, not in production. The JSON encoder is not a cost to design around: measured in benchmarks/orders-api (TUNING.md), with no per-request log line and only WARNs for failed calls, logging was 0.2% of the service's CPU under overload.

**Rule 2.** The [SLF4J FAQ](https://www.slf4j.org/faq.html) says the placeholder form "will outperform the first form by a factor of at least 30, in case of a disabled logging statement", because concatenation builds the string before the level check. The bigger cost is that `"order " + id` buries the id in text: with `addKeyValue("order.id", id)` the value is its own JSON member, which is what dashboards and queries need. When the last argument is a `Throwable` it is logged as the exception with its stack trace; anywhere else it is just formatted with `toString`. `System.out.println` bypasses the logging system completely: no level, no JSON, no MDC, no way to turn it off.

**Rule 3.** The [Logback MDC](https://logback.qos.ch/manual/mdc.html) is "per thread", and the manual says each `put()` should be paired with a `remove()`, because "a server that recycles threads might lead to false information contained in the MDC". `MDC.putCloseable` returns an `AutoCloseable` that removes the key, so try-with-resources cannot forget. A child thread "does not automatically inherit" the MDC: when you hand work to an executor, copy `MDC.getCopyOfContextMap()` into the task and call `setContextMap` first in it. Inside your own code, pass request context with a `ScopedValue` (final in JDK 25, [JEP 506](https://openjdk.org/jeps/506)), which `StructuredTaskScope` children inherit (`scoped-values`); the MDC is only the carrier into the log line. With Micrometer Tracing, Boot puts [`traceId` and `spanId` in the MDC](https://docs.spring.io/spring-boot/reference/actuator/tracing.html), so structured lines carry them without extra code.

**Rule 4.** Logs are copied to more systems, kept longer and readable by more people than the database they describe. A card number or token in a log line is a breach to report and cannot be deleted from every backup. The risky lines are not the explicit ones but the ones that log a whole object: a Java record's generated `toString` prints every component, and a DEBUG line that prints a request DTO ships whatever the client sent. Mask at the type (the example's `Card.toString` prints only the last four digits) so that every log line, exception message and debugger view is safe by construction; the test turns on DEBUG and checks the number and holder never appear.

**Rule 5.** A method that logs an exception and rethrows it produces one line per layer for the same event, each with a stack trace, and the reader cannot tell one failure from three. Throw with context (`exceptions`) and log once where the exception stops: the controller advice, the message listener, the job runner. The example's test checks that a declined payment is exactly one line.

**Rule 6.** Levels decide who gets paged. If a declined card or a 404 from a dependency is ERROR, error-rate alerts fire on normal traffic and real failures drown. DEBUG in production for `root` floods the pipeline with framework internals and multiplies the chance of logging a payload. Spring Boot logs ERROR, WARN and INFO by default; raise one logger at runtime (`logging.level.<logger>`, or the actuator `loggers` endpoint) while investigating, then lower it again.

## Good example

See [`examples/good/`](examples/good/).

```properties
logging.structured.format.console=ecs
logging.level.root=info
logging.level.org.springframework=warn
logging.structured.json.stacktrace.max-length=4096
```

```java
try (MDC.MDCCloseable ignored = MDC.putCloseable("request.id", requestId)) {
try {
authorize(card, amountCents);
} catch (DeclinedException e) {
log.atWarn()
.setMessage("payment declined")
.addKeyValue("order.id", orderId)
.addKeyValue("decline.reason", e.getMessage())
.log();
return false;
}
```

```java
@Override
public String toString() {
return "Card[last4=" + last4() + "]";
}
```

`PaymentsTest` captures the real console output of a Spring Boot context and checks that every line is JSON, that an authorized and a declined payment produce exactly one line each, that `request.id` (from the MDC) and `order.id` and `amount.cents` (from `addKeyValue`) are JSON members, that the decline is WARN, that the MDC is empty afterwards, and that with DEBUG on the placeholder message shows `Card[last4=1111]` and never the card number or the holder's name.

## Bad example

See [`examples/bad/`](examples/bad/).

```java
// MISTAKE: the record's generated toString prints every component, so any log line that
// includes a Card writes the full card number and the holder's name.
public record Card(String number, String holder) {}
```

```java
// MISTAKE: put without remove. The next task on this thread (a pooled platform thread,
// an executor worker) logs with this request's id.
MDC.put("requestId", requestId);
```

```java
// MISTAKE: log and rethrow. The caller logs it again, so one decline is two
// ERROR lines with two stack traces, and this one prints the card number.
log.error("authorization declined for " + card, e);
throw e;
```

The class also prints with `System.out.println`, concatenates the order id and amount into the message, logs the decline as ERROR, and its configuration keeps text output with `logging.level.root=debug`. All of it compiles and looks fine in a local console.

## Checklist

When reviewing code under this skill, confirm:

- [ ] `logging.structured.format.console` (or `.file`) is set to the format the log platform ingests in every deployed profile.
- [ ] No `System.out`, `System.err` or `printStackTrace()` outside `main` methods of command-line tools.
- [ ] No log call builds its message with `+` or `String.format`; values are `{}` arguments or `addKeyValue` fields, and the exception is the last argument.
- [ ] Every `MDC.put` is a `MDC.putCloseable` in try-with-resources (or has a `remove` in `finally`), and tasks handed to executors copy the MDC explicitly.
- [ ] No log line includes passwords, tokens, API keys, full card numbers or personal data, and records holding them override `toString`.
- [ ] No `catch` block both logs and rethrows; each exception is logged once where it is handled.
- [ ] ERROR is used only for failures that need action; client and expected dependency failures are WARN or INFO.
- [ ] `logging.level.root` is INFO or higher in production, and DEBUG is enabled per logger, temporarily.

## When not to apply

- **Local development.** Human-readable text is easier to read in a terminal; leave `logging.structured.format.console` unset in a `local` profile only.
- **A log platform that needs a format Boot does not ship.** Implement `StructuredLogFormatter` and set its class name as the format, instead of hand-writing JSON in a Logback pattern.
- **Command-line tools** whose output is the product. Write results to `System.out` and diagnostics through the logger to stderr.
- **Audit logs** that must record who changed what, including personal data, under a legal requirement. Write them to a dedicated audit store with its own retention and access control, not to the application log.
- **Libraries.** Depend on the SLF4J API only, never on Logback or Boot's structured logging, and leave format and levels to the application.
- **Very hot paths** (per-item logging inside a loop of millions). Log a summary per batch, or sample, instead of one line per item, even at DEBUG.
