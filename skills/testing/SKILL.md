---
name: testing
description: Use when writing or reviewing Java tests (JUnit, Mockito, @SpringBootTest, Testcontainers), choosing H2 or a real database for repository tests, testing code that reads the time or waits, splitting fast and slow tests (surefire, failsafe), fixing flaky or order-dependent tests, or seeing "A Java agent has been loaded dynamically". Key rule: JUnit 6 (junit-jupiter.version 6.1.3 over Spring Boot 4.1.1's 6.0.3) and Testcontainers 2 (testcontainers-postgresql, org.testcontainers.postgresql.PostgreSQLContainer); repository tests on the production engine and version (postgres:17), never H2 or a mocked repository; static @Container @ServiceConnection in Spring Boot tests; inject java.time.Clock and use Clock.fixed, never Thread.sleep; each test owns its data, no @TestMethodOrder dependence; *Test in surefire without Docker, *IT in failsafe; Mockito as -javaagent via maven-dependency-plugin properties and argLine @{argLine} -javaagent:${org.mockito:mockito-core:jar}.
metadata:
  verified: 2026-10-18
---

# Testing

## The decision

A Java service's tests decide whether a green build means the code works in production. The common answers are the ones that are quickest to set up: H2 "in PostgreSQL mode" instead of PostgreSQL, `Instant.now()` and `Thread.sleep` instead of a controllable clock, tests that depend on the row an earlier test inserted, and Mockito left to attach itself to the JVM. Each one costs nothing on day one and later produces a build that is green while production fails, or red while nothing is wrong. This skill fixes the stack (JUnit 6, Testcontainers 2, Mockito 5 as an agent), what runs against a real database, how time is controlled, and how fast and slow tests are split in Maven. The database itself, its pool and its queries are [`postgres-hikari-pool`](../postgres-hikari-pool/SKILL.md) and [`sql-and-transactions`](../sql-and-transactions/SKILL.md); CI wiring is [`ci-guardrails`](../ci-guardrails/SKILL.md).

## The rule

1. Use JUnit 6 and Testcontainers 2, and in a Spring Boot 4.1.1 build set `junit-jupiter.version` to 6.1.3, because the Boot BOM resolves 6.0.3.
2. Test SQL and repositories against the production database engine and major version in a Testcontainers container (`postgres:17`), never against H2, an embedded substitute or a mocked repository.
3. In Spring Boot tests declare the container as a `static` field with `@Container` and `@ServiceConnection`, and write no datasource properties for it by hand.
4. Inject a `java.time.Clock` wherever code reads the time and pass `Clock.fixed` in tests, and never `Thread.sleep` to wait for time to pass or for work to finish.
5. Make every test create the data it needs and remove what it leaves, with no `@TestMethodOrder` dependence and no shared mutable static state.
6. Name unit tests `*Test` and run them with surefire without Docker or a Spring context, and name tests that start containers or contexts `*IT` and run them with failsafe in `mvn verify`.
7. Load Mockito as a `-javaagent` in surefire and failsafe, through `maven-dependency-plugin`'s `properties` goal and `<argLine>@{argLine} -javaagent:${org.mockito:mockito-core:jar}</argLine>`.

## Why

**Rule 1.** "JUnit requires Java 17 (or higher) at runtime" ([JUnit 6.1.3 user guide](https://docs.junit.org/current/user-guide/)); one version number now covers the Platform, Jupiter and Vintage, and Vintage "is deprecated and should only be used temporarily while migrating". Spring Boot 4.1.1 manages `junit-jupiter.version` 6.0.3, so a Boot build that does not override it tests on an older line than the rest of the repository. Testcontainers 2 renamed the modules to `testcontainers-*` artifacts and moved the classes to `org.testcontainers.<module>` packages, so `org.testcontainers.containers.PostgreSQLContainer` from 1.x answers no longer compiles; the [PostgreSQL module](https://java.testcontainers.org/modules/databases/postgres/) is `org.testcontainers:testcontainers-postgresql:2.0.5`, the version Boot 4.1.1 manages.

**Rule 2.** H2's PostgreSQL mode is a compatibility layer, not PostgreSQL: H2's own [documentation](https://h2database.com/html/features.html) says "only a small subset of the differences between databases are implemented", and its list for this mode is about seventeen items (`ON CONFLICT DO NOTHING` is supported, other `ON CONFLICT` forms are not listed; `UPDATE` with `FROM` is "partially supported"). Everything else (types, functions, locking, isolation, SQLSTATE codes) is H2's own behavior, so a test passes on a query PostgreSQL rejects, or the other way round, and the bug the test was supposed to find ships. A mocked repository tests nothing about the SQL at all. A container with the same major version as production runs the real parser, planner and constraints; in the good example `delete ... returning guest` and the `<=` boundary on `timestamptz` are exactly what a substitute gets subtly wrong. The cost is Docker on every machine that runs integration tests and a few seconds per container start; rule 6 keeps that cost out of the fast loop.

**Rule 3.** `@ServiceConnection` makes Spring Boot create the connection details from the running container, so the test needs no `spring.datasource.*` properties, no `@DynamicPropertySource` and no copied port; it needs the `spring-boot-testcontainers` module on the test classpath ([Spring Boot Testcontainers](https://docs.spring.io/spring-boot/reference/testing/testcontainers.html)). With the JUnit extension, containers in `static` fields are stopped after the test class has run, and instance fields after each test method; a non-static field therefore starts a new PostgreSQL per test. Spring caches the application context across test classes with the same configuration, so keep that configuration identical where you can.

**Rule 4.** Code that calls `Instant.now()` can be tested only by waiting for the real clock, so a 15-minute expiry becomes either a 15-minute test or a shortened constant that differs from production. `Clock` is the JDK's injectable time source: production passes `Clock.systemUTC()` from one bean, tests pass `Clock.fixed(...)` and check the exact boundary (one millisecond before expiry, and at expiry) in microseconds. `Thread.sleep` in a test is either too long, which slows every build, or too short on a loaded 2-CPU CI runner, which makes the test flaky; for asynchronous work, wait on the result itself (`CompletableFuture.get(timeout)`, `CountDownLatch.await(timeout)`), which returns as soon as the work is done.

**Rule 5.** A test that only passes after another test is not a test of one behavior; it fails when run alone from the IDE, when JUnit's order changes, or when parallel execution is turned on. Static mutable state does the same across classes. Clearing the tables a test uses in `@BeforeEach`, as the good example's `ReservationRepositoryIT` does, makes each test start from a known state; asserting only on the rows a test created works when tests must share a database.

**Rule 6.** Surefire's default includes `*Test`, failsafe's default includes `**/IT*.java`, `**/*IT.java` and `**/*ITCase.java` ([failsafe](https://maven.apache.org/surefire/maven-failsafe-plugin/integration-test-mojo.html)) and runs them in `integration-test` and `verify`, after packaging. That gives two loops: `mvn test` runs the unit tests in seconds with no Docker, for every save; `mvn verify` runs everything, for every push. The Spring Boot parent already binds failsafe's `integration-test` and `verify` goals, so declaring the plugin is enough.

**Rule 7.** Mockito 5's default inline mock maker instruments classes through a Java agent, and without one it attaches itself while the tests run. [JEP 451](https://openjdk.org/jeps/451) (JDK 21) makes the JVM print "WARNING: A Java agent has been loaded dynamically" and says dynamic loading "will be disallowed by default in a future release"; agents loaded at startup with `-javaagent` are unaffected. The [Mockito javadoc](https://javadoc.io/doc/org.mockito/mockito-core/latest/org.mockito/org/mockito/Mockito.html) (section 0.3, "Explicitly setting up instrumentation for inline mocking (Java 21+)") gives the Maven setup: the `properties` goal of `maven-dependency-plugin` exposes each dependency's jar path as `${groupId:artifactId:type}`, and the argLine passes it to the forked JVM. `@{argLine}` keeps what other plugins (JaCoCo) add, and must resolve, so define an empty `<argLine/>` property; otherwise surefire passes the literal text and the forked JVM fails to start. `-XX:+EnableDynamicAgentLoading` only hides the warning. Mock what you do not own and cannot run locally, such as the mail provider; never mock the repository in a test that is about the repository.

The cost: containers make the integration suite slower (about 10 seconds here) and need Docker in CI, and an injected `Clock` is one more constructor parameter. Both are cheaper than one production bug the substitute hid.

## Good example

See [`examples/good/`](examples/good/).

```java
    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17");
```

```java
    @BeforeEach
    void emptyTable() {
        jdbc.sql("delete from reservation").update();
    }
```

```java
        var justBefore = Clock.fixed(NOW.plus(HoldService.HOLD).minusMillis(1), ZoneOffset.UTC);
        assertEquals(0, new HoldService(reservations, mailer, justBefore).releaseExpired());
        var atExpiry = Clock.fixed(NOW.plus(HoldService.HOLD), ZoneOffset.UTC);
        assertEquals(1, new HoldService(reservations, mailer, atExpiry).releaseExpired());
```

```xml
<junit-jupiter.version>6.1.3</junit-jupiter.version>
<argLine/>
<artifactId>maven-dependency-plugin</artifactId>
<goal>properties</goal>
<artifactId>maven-surefire-plugin</artifactId>
<argLine>@{argLine} -javaagent:${org.mockito:mockito-core:jar}</argLine>
<artifactId>maven-failsafe-plugin</artifactId>
```

`HoldServiceTest` runs in surefire with Mockito mocks (including the final `Mailer` class) and a fixed clock, and asserts that Mockito arrived as `-javaagent`. `ReservationRepositoryIT` runs in failsafe against `postgres:17`, checks the expiry boundary at `NOW` exactly, and passes the 15-minute hold in no time at all.

## Bad example

See [`examples/bad/`](examples/bad/).

```java
// MISTAKE: an in-memory H2 URL replaces the real database for the whole test run.
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:test;MODE=PostgreSQL")
// MISTAKE: the second test needs the row the first one inserted; run it alone or in parallel and it fails.
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
```

```java
        // MISTAKE: sleeping for real time; HOLD would mean a 15-minute test, so someone shortens it and it flakes on a slow CI runner.
        Thread.sleep(Duration.ofSeconds(2));
```

```java
        // MISTAKE: reads the wall clock directly, so a test can only check expiry by waiting for it.
        Instant expiresAt = Instant.now().plus(HOLD);
```

The POM adds H2 as the test database, has no `-javaagent` for Mockito and no failsafe, and keeps Boot's JUnit 6.0.3. The test also compares against a second `Instant.now()`. It compiles; run against PostgreSQL-specific SQL it would pass or fail depending on H2's dialect, the clock and the method order rather than on the code.

## Checklist

When reviewing code under this skill, confirm:

- [ ] The resolved JUnit is 6.x at the repository's version (`mvn dependency:tree`), and Testcontainers is 2.x with `testcontainers-*` artifacts and `org.testcontainers.<module>` imports.
- [ ] Every test of SQL, a repository or a migration runs against the production engine and major version in a container; no H2, HSQLDB or Derby dependency exists for tests.
- [ ] Spring Boot tests use a `static` `@Container` with `@ServiceConnection`, with no hand-written datasource URL, user or password.
- [ ] No production code calls `Instant.now()`, `LocalDateTime.now()` or `System.currentTimeMillis()` for business logic; it takes a `Clock`.
- [ ] No test calls `Thread.sleep`; asynchronous results are awaited with a timeout on the result itself.
- [ ] Each test passes when run alone and in any order: no `@TestMethodOrder` for data dependence, no shared mutable statics, tables cleared or rows scoped per test.
- [ ] Unit tests (`*Test`) need neither Docker nor a Spring context; anything that does is an `*IT` run by failsafe.
- [ ] Surefire and failsafe both pass `-javaagent:${org.mockito:mockito-core:jar}` with `@{argLine}`, the dependency plugin's `properties` goal is bound, and an empty `argLine` property exists.
- [ ] Mocks stand in only for collaborators the service does not own (mail, payment, other services), never for the code under test or its repository in an integration test.

## When not to apply

- **No Docker available.** Some CI runners and developer machines cannot run containers. Keep the `*IT` tests and run them where Docker exists (a separate CI job); do not replace them with H2 to make them run everywhere.
- **Pure functions.** Code that reads no time, I/O or randomness needs no clock, container or mock; a plain JUnit test with values in and values out is the right size.
- **Tests that are about time itself.** A test of a scheduler or a timeout mechanism may need a real, short wait. Bound it with `@Timeout` or `assertTimeoutPreemptively`, keep the margin generous for a 2-CPU runner, and keep such tests few.
- **Libraries without Spring.** Use Testcontainers' JUnit extension or start the container in `@BeforeAll` as the `postgres-hikari-pool` example does; `@ServiceConnection` is a Spring Boot feature.
- **Gradle builds.** The rules hold, but the agent setup differs: resolve the Mockito jar through a dedicated configuration and pass it as `-javaagent` in `jvmArgs` of the `Test` task.
