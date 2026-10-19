# java-skills

**English** | [Português](README.pt-BR.md)

**Opinionated skills that teach coding agents to write current, secure, low-resource Java for backend services.**

Coding agents know Java syntax. They don't know *which* way to do things on today's JDK and Spring Boot. Left alone, they leave the heap at the JVM's 25% default inside a container, pool virtual threads, call the broker inside `@Transactional`, ship a pgJDBC version with known CVEs because the Spring Boot BOM pins it, and accept JSON request bodies of any size.

Each skill here covers **one design decision**, takes a clear position on it, shows a good example and a bad one, and gives the agent a checklist to review its own output. Every example runs in CI on **JDK 25**, the current LTS, and on **JDK 27**, the newest release.

Works with Claude Code, Cursor, Codex and any agent that reads `SKILL.md` files. Sister repositories: [go-skills](https://github.com/rafapeixer/go-skills) and [db-skills](https://github.com/rafapeixer/db-skills).

---

## Install

### Claude Code plugin (recommended)

```
/plugin marketplace add rafapeixer/java-skills
/plugin install java-skills@java-skills
```

Skills load automatically when the task matches, or by name: `/java-skills:virtual-threads`. A short note at session start ([`hooks/session-context.md`](hooks/session-context.md)) lists the traps and reminds the agent to load the matching skill. Updates arrive with each release.

### Claude Code, copying the files

```bash
git clone https://github.com/rafapeixer/java-skills.git /tmp/java-skills
mkdir -p .claude/skills
cp -r /tmp/java-skills/skills/* .claude/skills/
```

### Universal skills CLI

```bash
npx skills add rafapeixer/java-skills
```

### Any other agent

Copy the `skills/` folder into wherever your agent loads skills from. Each skill is a self-contained folder with a `SKILL.md` at its root.

---

## Skills

> v1, v1.1 and v2 are ready: 24 skills.

### v1

| Skill | Use when | Status |
|---|---|---|
| [project-layout](skills/project-layout/SKILL.md) | Creating a service or adding packages: package by feature, main class in the root package, package-private by default, no Service/Impl pairs | Ready |
| [exceptions](skills/exceptions/SKILL.md) | Throwing, catching or wrapping exceptions, handling InterruptedException, or mapping errors to HTTP responses (ProblemDetail) | Ready |
| [timeouts-and-deadlines](skills/timeouts-and-deadlines/SKILL.md) | Adding timeouts to blocking calls (HttpClient, Future.get, JDBC) or passing a request deadline down to every call | Ready |
| [virtual-threads](skills/virtual-threads/SKILL.md) | Running blocking work concurrently, enabling virtual threads in Spring Boot, or limiting concurrent calls to a dependency | Ready |
| [http-server](skills/http-server/SKILL.md) | Building a Spring Boot MVC/Tomcat service: body limits, problem+json errors, timeouts, probes, graceful shutdown, virtual threads, API versioning | Ready |
| [postgres-hikari-pool](skills/postgres-hikari-pool/SKILL.md) | Configuring HikariCP for PostgreSQL: pool size from the connection budget, fixed-size pool, maxLifetime, fail-fast connectionTimeout, virtual threads, pgJDBC settings | Ready |
| [secure-defaults](skills/secure-defaults/SKILL.md) | Generating tokens, hashing passwords, deserializing, parsing XML, configuring TLS or reading secrets | Ready |
| [testing](skills/testing/SKILL.md) | Writing JUnit/Mockito/Testcontainers tests, testing time-dependent code or splitting unit and integration tests | Ready |

### v1.1

| Skill | Use when | Status |
|---|---|---|
| [sql-and-transactions](skills/sql-and-transactions/SKILL.md) | Writing JDBC/JdbcClient SQL and @Transactional code: parameters only, batch inserts, keyset pagination, proxy self-invocation, no remote calls in transactions, retry on 40001/40P01 | Ready |
| [db-migrations](skills/db-migrations/SKILL.md) | Adding Flyway migrations for PostgreSQL: run as a separate job before rollout, never edit applied files, expand/contract, lock_timeout, CREATE INDEX CONCURRENTLY outside a transaction | Ready |
| [logging-structured](skills/logging-structured/SKILL.md) | Adding logs to a Java/Spring Boot service: ECS/Logstash JSON, SLF4J placeholders and addKeyValue, request id in MDC, no secrets, log once, levels | Ready |
| [ci-guardrails](skills/ci-guardrails/SKILL.md) | Writing a CI pipeline or a pom build section: checksummed Maven wrapper, enforcer, -Xlint:all with failOnWarning, a dependency check that can fail, SHA-pinned actions | Ready |
| [container-runtime-tuning](skills/container-runtime-tuning/SKILL.md) | Writing a Dockerfile or Kubernetes manifest for a Java service: heap with MaxRAMPercentage=75 instead of -Xmx, container support on, a CPU limit, G1 by name, JAVA_TOOL_OPTIONS with an exec-form ENTRYPOINT, non-root | Ready |
| [overload-protection](skills/overload-protection/SKILL.md) | Deciding what a service does above capacity, adding a concurrency limit, or creating an executor for request or background work | Ready |
| [outbound-http-client](skills/outbound-http-client/SKILL.md) | Calling another service over HTTP (HttpClient, RestClient), adding retries, or reading response bodies | Ready |
| [java-version-policy](skills/java-version-policy/SKILL.md) | Choosing the Java version to compile for, test on or deploy: --release 25 floor, test on 25 and 27, LTS runtime with current updates, no preview features | Ready |

### v2

| Skill | Use when | Status |
|---|---|---|
| [observability-otel](skills/observability-otel/SKILL.md) | Adding OpenTelemetry traces, metrics or log correlation to a Java/Spring Boot service: OTel agent by default, parent-based sampling, bounded span names and metric tags | Ready |
| [profiling-jfr](skills/profiling-jfr/SKILL.md) | A Java service is slow, CPU- or memory-hungry, or pins virtual threads: measure first with an always-on JFR recording, dump it with jcmd JFR.dump, read it with jfr view | Ready |
| [fast-startup-aot-cache](skills/fast-startup-aot-cache/SKILL.md) | A Java or Spring Boot service starts too slowly: train an AOT cache (-XX:AOTCacheOutput / -XX:AOTCache) in the runtime image on the same JDK and jars; native image or CRaC only with a reason | Ready |
| [background-jobs-outbox](skills/background-jobs-outbox/SKILL.md) | A write must publish an event or start background work: outbox row in the same transaction, relay with FOR UPDATE SKIP LOCKED, idempotent consumers | Ready |
| [json-jackson3](skills/json-jackson3/SKILL.md) | Reading or writing JSON with Jackson 3 or migrating from Jackson 2: one shared JsonMapper, read limits, strict unknown properties, records, no class-name type ids | Ready |
| [scoped-values](skills/scoped-values/SKILL.md) | Carrying request context (request id, tenant, user) down the call stack or replacing a ThreadLocal | Ready |
| [null-safety-jspecify](skills/null-safety-jspecify/SKILL.md) | Adding nullness annotations, choosing null vs Optional, migrating off JSR 305 or setting up NullAway | Ready |
| [mcp-server-java](skills/mcp-server-java/SKILL.md) | Writing an MCP server in Java with the official SDK or Spring AI: narrow tools, strict input schemas, nothing on stdout with STDIO, timeouts, OAuth audience checks on HTTP | Ready |

---

## Does it change what agents write?

Measured with `claude plugin eval` ([`evals/`](evals/)): each task runs with and without the plugin, **10 times per side**, and regex graders check the file the agent writes. October 2026.

| | Without plugin | With plugin |
|---|---|---|
| **All 11 tasks, 110 runs** | **58 / 110 (53%)** | **107 / 110 (97%)** |

Where the agent failed without the plugin:

| Task | Without | With | The failure |
|---|---|---|---|
| `pom.xml` for a Spring Boot 4.1 service on PostgreSQL | 0 / 10 | **10 / 10** | Kept the pgJDBC 42.7.13 the Boot BOM pins, which has two CVEs fixed in 42.7.14 |
| HikariCP pool for 6 replicas (8 during deploys) and 200 connections | 0 / 10 | **10 / 10** | `minimumIdle` set to the maximum and the 30 s default `connectionTimeout`, so overload queues instead of failing fast |
| Maven setup for Mockito with final classes on JDK 25 | 0 / 10 | **10 / 10** | JUnit 5 instead of JUnit 6 |
| `pom.xml` for a new service on the Java floor | 0 / 10 | **10 / 10** | No `release 25`, no enforcer rule for the Java version |
| Dockerfile for a Java service in a 1 CPU / 1 GiB pod | 3 / 10 | **10 / 10** | G1 not named, so JDK 25 silently runs Serial GC in that container |
| Flyway migration with `CREATE INDEX CONCURRENTLY` | 5 / 10 | **7 / 10** | Flyway's transactional lock left on, so the index build waits on Flyway's own lock |

Fan-out with virtual threads, request deadlines, Jackson 3 on untrusted input, the JSON body limit and the transactional outbox came out 10 / 10 on both sides: the model already gets those right, and the skills keep it that way. The skill loaded in 110 of 110 runs.


**Under load:** [BENCHMARKS.md](BENCHMARKS.md) runs the service the agent wrote, with and without these skills, in AWS t4g.micro-sized containers, 3 times per scenario. Without the skills it collapsed under overload (96.5% errors at 4,000 req/s) and never recovered; generated with java-skills v3 it held 5,000 req/s with 0% errors, rejected the excess at 4,000 req/s with 1.6% fast 503s, recovered at once, and matched a version hand-tuned under JFR. Serving 10,000 req/s costs US$ 112 a month in EC2 instead of US$ 178.

---

## What a skill looks like

Every `SKILL.md` has the same shape:

1. **The decision**: what choice this skill is about
2. **The rule**: the position this repo takes, as numbered one-line rules
3. **Why**: the reasoning and the cost of doing it differently
4. **Good example**: runnable code in `examples/good/`
5. **Bad example**: the real mistake, in `examples/bad/`, with each error commented
6. **Checklist**: yes/no items the agent applies when reviewing
7. **When not to apply**: the exceptions, because every rule has them

---

## Principles

- **One decision per skill.** No catch-all "best practices".
- **A position, with reasons.** "It depends" is only allowed when followed by "on what, and here is what to do in each case".
- **Use what the runtime gives you.** Virtual threads, container-aware ergonomics, the AOT cache, TLS 1.3 and, on JDK 27, post-quantum key exchange by default. Most production bugs come from switching a good default off or never turning it on.
- **Bound everything.** Timeouts, pool sizes, queue sizes, concurrency, request bodies.
- **Current Java.** Examples compile with `--release 25` (the newest LTS) and run on JDK 25 and 27. Preview features are named as preview, never taught as the default. Facts come only from openjdk.org, docs.oracle.com, docs.spring.io and each library's own documentation.
- **Examples run.** Good examples run their own checks or `mvn verify`, many against a real PostgreSQL in Testcontainers; bad examples compile.

---

## Contributing

Disagree with a rule? Open an issue and make the case. Rules change when someone shows what breaks, not when someone prefers something else.

Want to add a skill? Start from [templates/SKILL.template.md](templates/SKILL.template.md), follow [CLAUDE.md](CLAUDE.md), one skill per PR. See [CONTRIBUTING.md](CONTRIBUTING.md).

---

## License

MIT
