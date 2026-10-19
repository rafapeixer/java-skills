---
name: json-jackson3
description: Use when writing or reviewing Java code that reads or writes JSON with Jackson (ObjectMapper, JsonMapper, readValue, @JsonTypeInfo), migrating from Jackson 2 (com.fasterxml.jackson) to Jackson 3 (tools.jackson), configuring spring.jackson.* in Spring Boot 4, deciding whether unknown JSON properties should fail, mapping records or java.time, or handling parse errors. Key rule: build one immutable JsonMapper with JsonMapper.builder() and share it (in Boot, inject its bean); set StreamReadConstraints (document length is unlimited by default); enable FAIL_ON_UNKNOWN_PROPERTIES (off by default in Jackson 3) for your own API contract and mark payloads you do not own @JsonIgnoreProperties(ignoreUnknown = true); records with validating compact constructors; no default typing or Id.CLASS on untrusted input, use Id.NAME with @JsonSubTypes; catch the unchecked JacksonException once and answer 400, never null; remove Jackson 2 databind annotations, which Jackson 3 silently ignores. Jackson 3.1 LTS, Java 17+.
metadata:
  verified: 2026-10-18
---

# JSON with Jackson 3

## The decision

Spring Boot 4 made Jackson 3 the default, and Jackson 3 is not a rename of Jackson 2. The packages and group ids moved to `tools.jackson` (only `jackson-annotations` stays under `com.fasterxml.jackson.annotation`), mappers are immutable and built with a builder, every exception is unchecked, and several defaults flipped: unknown properties no longer fail, dates are ISO strings instead of timestamps, nulls for primitives fail, trailing tokens fail, properties are sorted. The decisions left to you are how many mappers the service has and where they are built, how strict reading is for which payloads, how input size is bounded, how polymorphic types are named, and what a parse error turns into. Get them wrong and the result is quiet: a misspelled field dropped, an annotation that compiles and does nothing, a 50 MB string in the heap, or a class name in the request that the server instantiates. Request body size limits at the HTTP layer are `http-server`; general exception handling is `exceptions`.

## The rule

1. Build one `JsonMapper` per service with `JsonMapper.builder()` and share it; in Spring Boot inject the auto-configured `JsonMapper` and change it with `spring.jackson.*` or a `JsonMapperBuilderCustomizer`, never `new ObjectMapper()` in a method.
2. Set `StreamReadConstraints` on every mapper that reads untrusted input: a maximum document length (unlimited by default), a maximum string length far below the 100,000,000-char default, and a nesting depth well below 500.
3. Enable `DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES` for the JSON your service defines, and annotate DTOs for payloads you do not own with `@JsonIgnoreProperties(ignoreUnknown = true)`.
4. Model DTOs as records and validate in the compact constructor, so an object that exists is valid.
5. Never enable default typing or `@JsonTypeInfo(use = Id.CLASS)` on input you do not control; name subtypes with `Id.NAME` and a closed `@JsonSubTypes` list.
6. Catch `JacksonException` (unchecked in Jackson 3) once, at the boundary, and turn it into a 400, never into `null` or a log line.
7. Import only `tools.jackson.*` and `com.fasterxml.jackson.annotation.*` in Jackson 3 code, and remove Jackson 2 `jackson-databind` from the classpath when the migration is done.

## Why

**Rule 1.** Jackson 3 [made mappers immutable](https://github.com/FasterXML/jackson/blob/main/jackson3/MIGRATING_TO_JACKSON_3.md): configuration happens on `JsonMapper.builder()`, `rebuild()` derives a changed copy, and the setters and `copy()` are gone. The [databind README](https://github.com/FasterXML/jackson-databind/blob/3.x/README.md) says this was done "to make `ObjectMapper` instances immutable and fully thread-safe", so one instance serves every thread. A mapper caches the serializers and deserializers it builds by introspecting each class; a mapper per call throws that work away on every request. In Spring Boot 4 [a `JsonMapper` bean is auto-configured](https://docs.spring.io/spring-boot/reference/features/json.html) and used by Spring MVC and `RestClient`; a second hand-built mapper silently diverges from the `spring.jackson.*` settings the team thinks are in force.

**Rule 2.** The [jackson-core README](https://github.com/FasterXML/jackson-core/blob/3.x/README.md) lists the defaults: document length "Unlimited (-1)", string value length "100_000_000 (100 million) (since 3.1; was 20 million in 2.x/3.0)", nesting depth "500 levels (3.x)", number length 1000, property name length 50,000. A string limit of 100 million chars lets one field occupy 100 to 200 MB of heap as a Java `String`. Set limits that match the API: the example uses 1 MB per document, 100,000 chars per string and 64 levels, and its test checks that each limit throws `StreamConstraintsException`. In Spring Boot use `spring.jackson.factory.constraints.read.max-document-length`, `max-string-length` and `max-nesting-depth`. Bound the body at the HTTP layer as well; this limit protects every other source (queues, files, upstream responses).

**Rule 3.** `FAIL_ON_UNKNOWN_PROPERTIES` is ["disabled in 3.0"](https://github.com/FasterXML/jackson/blob/main/jackson3/MIGRATING_TO_JACKSON_3.md) (it was enabled in plain Jackson 2; Spring Boot already disabled it). Off is right for someone else's payload: an upstream that adds a field must not break you. It is wrong for your own request bodies: a client that sends `"paymnet"` instead of `"payment"` gets a 200 and a missing payment, and nobody notices. Turn it on for the mapper (in Boot: `spring.jackson.deserialization.fail-on-unknown-properties=true`) and opt each foreign DTO out with `@JsonIgnoreProperties(ignoreUnknown = true)`; the example's tests check both directions. This changes behavior for clients that send extra fields today, so roll it out with the next API version or after checking traffic.

**Rule 4.** Records bind through their canonical constructor without annotations, are immutable, and get `equals` for free in tests. The compact constructor runs on every deserialization, so a range check there cannot be skipped, and Jackson reports its exception as a `ValueInstantiationException`. Jackson 3 enables `FAIL_ON_NULL_FOR_PRIMITIVES`, and the example's test shows an absent `int` component now fails with `MismatchedInputException` instead of arriving as 0; a value that is present but out of range (0) still needs the constructor check. `java.time` support is built into Jackson 3 databind (no `JavaTimeModule`), and `WRITE_DATES_AS_TIMESTAMPS` is "disabled in 3.0", so an `Instant` is written as `"2026-10-18T12:00:00Z"`. Clients that expect epoch numbers need a deliberate `DateTimeFeature` setting, not a surprise.

**Rule 5.** With class names as type ids, the [Jackson polymorphic deserialization docs](https://github.com/FasterXML/jackson-docs/wiki/JacksonPolymorphicDeserialization) warn, "you may expose a security hole in case untrusted source manages to specify a class that is accessible through class loader", the "deserialization gadget" class of vulnerabilities, and "use of "default typing" is not encouraged in general". A validator built with `allowIfBaseType(Object.class)` allows every class and restores the hole. `Id.NAME` with `@JsonSubTypes` maps a fixed set of names to classes, so `"type":"java.lang.ProcessBuilder"` fails with `InvalidTypeIdException`, as the example's test checks.

**Rule 6.** In Jackson 3 ["all Jackson exceptions are now `RuntimeException`s"](https://github.com/FasterXML/jackson/blob/main/jackson3/MIGRATING_TO_JACKSON_3.md), `JsonProcessingException` is now `JacksonException`, and `JsonMappingException` is `DatabindException`. Nothing forces a `catch`, so the right place is one boundary handler that maps it to a 400 (Spring MVC already turns a failed `@RequestBody` read into a 400 `HttpMessageNotReadableException`). Returning `null` converts a client error into a `NullPointerException` somewhere else. `FAIL_ON_TRAILING_TOKENS` is on in Jackson 3, so `{...} {}` fails too.

**Rule 7.** Jackson 3 reads only Jackson 3 databind annotations. The migration guide warns that 2.x annotations such as `@JsonSerialize` are silently ignored when Jackson 2 databind is still on the classpath; the code compiles because the old jar is there. The bad example's `@JsonNaming(SnakeCaseStrategy.class)` from `com.fasterxml.jackson.databind.annotation` does nothing: Jackson 3 writes `unitPrice`. Spring Boot 4 still ships a deprecated `spring-boot-jackson2` module and `spring.jackson.use-jackson2-defaults` to ease migration; both are temporary.

## Good example

See [`examples/good/`](examples/good/).

```java
public static final JsonMapper MAPPER = JsonMapper.builder(JsonFactory.builder()
.streamReadConstraints(StreamReadConstraints.builder()
.maxDocumentLength(1_000_000L) // default: unlimited
.maxStringLength(100_000) // default: 100,000,000 chars
.maxNestingDepth(64) // default: 500
.build())
.build())
// Off by default in Jackson 3. Our own contract: a misspelled field is a client bug.
.enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
.build();
```

```java
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
@JsonSubTypes.Type(value = Payment.Card.class, name = "card"),
@JsonSubTypes.Type(value = Payment.Pix.class, name = "pix")
})
public sealed interface Payment {
```

```java
/** Another service's payload: we do not own it, so new fields must not break us. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record UpstreamPrice(String sku, BigDecimal amount) {}
```

`JsonTest` checks that records round-trip with ISO dates, that a misspelled field in the service's own `Order` fails while an extra field in `UpstreamPrice` is ignored, that an absent or zero quantity and a missing date are rejected, that a class name as type id is rejected, that a 200,000-char string, 100 levels of nesting and a 2 MB document each hit a limit, and that malformed JSON, trailing content and non-JSON all throw the one unchecked `JacksonException`.

## Bad example

See [`examples/bad/`](examples/bad/).

```java
// MISTAKE: the Jackson 2 annotation (com.fasterxml.jackson.databind.annotation) on a type
// read by Jackson 3. It compiles and is silently ignored: output says "unitPrice", and
// input "unit_price" is an unknown property.
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
```

```java
// MISTAKE: default typing with a validator that allows any subtype of Object.
// Untrusted JSON now names the class to instantiate in "metadata": a gadget chain.
.activateDefaultTyping(BasicPolymorphicTypeValidator.builder()
.allowIfBaseType(Object.class)
.build(), DefaultTyping.NON_FINAL)
```

```java
} catch (JacksonException e) {
// MISTAKE: every parse error becomes null; the caller answers 200 with an empty
// order, or fails later with a NullPointerException far from the cause.
return null;
```

The parser also builds a new mapper on every call, sets no read constraints, and keeps Jackson 2 databind on the classpath, which is what lets the dead annotation compile.

## Checklist

When reviewing code under this skill, confirm:

- [ ] No `new ObjectMapper()`, `new JsonMapper()` or `JsonMapper.builder()...build()` inside a method that runs per request; there is one shared mapper (the Boot bean in Spring).
- [ ] Every mapper that reads untrusted input sets maximum document length, string length and nesting depth (or `spring.jackson.factory.constraints.read.*`).
- [ ] `FAIL_ON_UNKNOWN_PROPERTIES` is enabled for the service's own request DTOs, and DTOs for foreign payloads carry `@JsonIgnoreProperties(ignoreUnknown = true)`.
- [ ] DTOs are records, and required fields and ranges are checked in the compact constructor.
- [ ] No `activateDefaultTyping`, no `@JsonTypeInfo(use = Id.CLASS)` or `Id.MINIMAL_CLASS`, and no `allowIfBaseType(Object.class)` on input from outside the service.
- [ ] `JacksonException` is handled once at the boundary as a 400, and no code returns `null` or an empty object on a parse error.
- [ ] No `com.fasterxml.jackson.databind` or `com.fasterxml.jackson.core` imports remain in code read by Jackson 3, and the Jackson 2 databind dependency is gone after migration.
- [ ] Date output is ISO-8601 on purpose; any client that needs epoch numbers has an explicit `DateTimeFeature` setting and a test.

## When not to apply

- **Mid-migration services** that must keep Jackson 2 for a library that has not moved. Keep Jackson 2 and 3 code in separate packages, set `spring.http.converters.preferred-json-mapper=jackson2` only if MVC must still use it, and track the removal; Boot's Jackson 2 support is deprecated.
- **Both sides under your control** (a cache or queue written and read by the same service). Default typing with a narrow `BasicPolymorphicTypeValidator` (`allowIfSubType("com.example.events.")`) is acceptable there; still never accept it from clients.
- **Consumer-side tolerant parsing** of a public API you do not own, such as webhooks. Leave `FAIL_ON_UNKNOWN_PROPERTIES` off for that mapper or DTO, and validate only the fields you use.
- **Streaming very large documents** (exports, bulk imports). Read with `JsonParser` token by token and raise the document limit for that reader only, keeping per-string and nesting limits.
- **Libraries** should not pick a service-wide configuration: accept a `JsonMapper` from the caller instead of building one.
