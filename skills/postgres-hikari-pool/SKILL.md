---
name: postgres-hikari-pool
description: Use when configuring a PostgreSQL connection pool in Java (HikariCP or spring.datasource.hikari in Spring Boot), sizing maximumPoolSize for several replicas, adopting virtual threads, putting PgBouncer or a proxy in front of Postgres, or seeing "too many clients" or "Connection is not available, request timed out". Key rule: maximumPoolSize = connection budget / (max replicas + ceil(maxSurge, Kubernetes default 25%)), e.g. 80 / (8 + 2) = 8, not 80 / (8 + 8), never the default 10 unchecked nor one per thread; leave minimumIdle unset (fixed-size pool); maxLifetime ~30 s shorter than any database, PgBouncer or load balancer limit, keepaliveTime 2 min; connectionTimeout of a few hundred ms so a full pool fails fast as 503; with virtual threads the pool bounds concurrency; HikariCP 7.1+ and pgJDBC 42.7.14+ (override Spring Boot 4.1.1's managed versions); pgJDBC ApplicationName, reWriteBatchedInserts, tcpKeepAlive, socketTimeout; prepareThreshold=0 only behind a pooler without prepared statements.
metadata:
  verified: 2026-10-19
---

# PostgreSQL pool with HikariCP

## The decision

A Java service reaches PostgreSQL through one HikariCP pool (Spring Boot picks HikariCP whenever it is on the classpath), and the pool's settings decide how the service fails under load. PostgreSQL runs one server process per connection and caps them with `max_connections`, so the number that matters is the sum over every pod, not one pod's pool. Get it wrong and the failures arrive at the worst moment: "too many clients" in the middle of a rolling deploy, a request thread stuck 30 seconds waiting for a connection while the database is already saturated, a connection a load balancer cut an hour ago handed to a request, a pool sized for 200 Tomcat threads that becomes 1,000 once virtual threads are on. This skill fixes the pool's size, lifetime settings, borrow timeout and the pgJDBC properties. How the budget per service is computed on the database side is db-skills [`pg-connections`](https://github.com/rafapeixer/db-skills/blob/main/skills/pg-connections/SKILL.md); how queries and transactions use the pool is [`sql-and-transactions`](../sql-and-transactions/SKILL.md).

## The rule

1. Use HikariCP 7.1+ and pgJDBC 42.7.14+, and in a Spring Boot 4.1.1 build set the `hikaricp.version` and `postgresql.version` properties, because the Boot BOM resolves 7.0.2 and 42.7.13.
2. Set `maximumPoolSize` to the service's connection budget divided by the most pods that can run at once, the autoscaler maximum plus your Deployment's `maxSurge` rounded up (25% by default, not 100%), never the default 10 unchecked and never one connection per request thread.
3. Leave `minimumIdle` unset so the pool is fixed-size.
4. Set `maxLifetime` about 30 seconds shorter than the shortest connection limit imposed by PostgreSQL, PgBouncer, a proxy or a load balancer, and keep `keepaliveTime` at two minutes with pgJDBC `tcpKeepAlive=true`.
5. Set `connectionTimeout` to a few hundred milliseconds, well under the request deadline, and answer 503 when `getConnection` throws `SQLTransientConnectionException`.
6. With virtual threads, let the pool be the concurrency bound for database work: never derive `maximumPoolSize` from the thread count.
7. Pass pgJDBC settings as data source properties: `ApplicationName`, `reWriteBatchedInserts=true` and a `socketTimeout` above the role's `statement_timeout`; set `prepareThreshold=0` only behind a transaction pooler that does not support prepared statements.
8. Borrow connections only in try-with-resources or through `JdbcClient`/`JdbcTemplate`, so every connection goes back to the pool.

## Why

**Rule 1.** HikariCP 7.1.0 is the current release for Java 11+ ([README](https://github.com/brettwooldridge/HikariCP)). pgJDBC 42.7.14 (2026-10-07) is a security release: CVE-2026-107314, where 42.7.11 to 42.7.13 ignored a `requireAuth` that excludes every method and accepted any authentication the server asked for, including cleartext password; and CVE-2026-107315, where 42.7.4 to 42.7.13 padded short values with bytes from earlier messages on the same connection, so stored data could contain SQL text and parameters of other statements ([release notes](https://jdbc.postgresql.org/changelogs/2026-10-07-42.7.14-release/)). Spring Boot 4.1.1's dependency management pins `hikaricp.version` 7.0.2 and `postgresql.version` 42.7.13, so a Boot service that only adds `org.postgresql:postgresql` without a version runs the vulnerable driver. Override the two properties in the POM (or the Gradle equivalent) until a Boot release manages them.

**Rule 2.** The pool is the only thing between your fleet and `max_connections` (100 by default). The budget is what the database owner gives this service after `superuser_reserved_connections`, admin sessions, migrations and other services. Divide it by the peak pod count, not today's: during a rolling update the new pods open their pools before the old ones close, and the autoscaler can add pods during the same deploy. The surge is your Deployment's `maxSurge`, which Kubernetes "calculated from the percentage by rounding up. The default value is 25%" ([Deployments](https://kubernetes.io/docs/concepts/workloads/controllers/deployment/#max-surge)): 8 replicas run at most 8 + 2 = 10 pods, not 16. Doubling the replicas "to be safe" is not safe, it moves the queue into the pool. Measured in benchmarks/orders-api (TUNING.md): with 80 connections for 8 replicas, 80 / (8 + 8) = 5 left the pool as the queue (Hikari reported `total=5, active=5, idle=0, waiting=230`), and 80 / (8 + 2) = 8 cut the errors of a 4,000 req/s spike from 21% to 9.5% with peak `pg_stat_activity` still inside the budget. Write the arithmetic next to the setting. The HikariCP default of 10 is not a budget: 30 pods with the default open 300 connections. One connection per thread is worse. HikariCP's [About Pool Sizing](https://github.com/brettwooldridge/HikariCP/wiki/About-Pool-Sizing) gives `connections = ((core_count * 2) + effective_spindle_count)` as the starting point for the database server (9 or 10 for a 4-core server) and says "You want a small pool of a few dozen connections at most", with the remaining threads waiting for a connection. So the budget itself should be in the range of a few connections per database core across the whole fleet; a bigger pool moves the queue into PostgreSQL, where every waiting query is a process competing for CPU and locks. If the arithmetic gives fewer than two connections per pod, add PgBouncer instead of shrinking further; the good example's `poolSize` refuses that case.

**Rule 3.** `minimumIdle` defaults to `maximumPoolSize`, and HikariCP recommends "not setting this value and instead allowing HikariCP to act as a fixed size connection pool". With `minimumIdle` below the maximum the pool closes connections after `idleTimeout` (10 minutes) and has to open them again during the next burst, when the database is busiest and each new connection costs a fork, TLS and authentication. A fixed pool also makes the fleet's connection count a constant you can check against the budget.

**Rule 4.** `maxLifetime` defaults to 30 minutes, and the README says it "should be several seconds shorter than any database or infrastructure imposed connection time limit". If PgBouncer's `server_lifetime`, a cloud proxy, an NLB idle timeout or a firewall closes connections before HikariCP retires them, the pool hands out a dead socket and the request fails with an I/O error. HikariCP retires a connection only when it is not in use, so the margin covers a borrow in progress. `0` means no limit, which is never right in production. `keepaliveTime` (default 2 minutes, minimum 30 seconds, less than `maxLifetime`) tests idle connections so a dropped one is evicted before a request finds it; the README also recommends OS-level TCP keepalive, which for PostgreSQL is `tcpKeepAlive=true`.

**Rule 5.** `connectionTimeout` defaults to 30 seconds (minimum 250 ms). When every connection is busy, the default makes each new request hold its thread and its memory for up to 30 seconds while the database gets no relief, and clients retry on top. A short timeout turns a full pool into a fast `SQLTransientConnectionException`; map it to `503 Service Unavailable` so load balancers and clients back off. `validationTimeout` (default 5 seconds) must stay below it. The good example's test holds all three connections of a pool of three, sees exactly three in `pg_stat_activity` for its `ApplicationName`, and gets the exception in about 500 ms.

**Rule 6.** Virtual threads remove the thread pool that used to cap concurrency, so the connection pool becomes the bound. That is the right place for it: 10,000 virtual threads can wait for a connection cheaply, while 10,000 connections would take down PostgreSQL. Keep the budget from rule 2, and keep `connectionTimeout` short so the waiting does not grow without limit; shed load at the edge (see `overload-protection`). The test runs 200 virtual threads against a pool of four and checks they used at most four server backends. Holding a connection across a slow remote call starves every other thread, so never call another service while you hold one (see [`sql-and-transactions`](../sql-and-transactions/SKILL.md)).

**Rule 7.** HikariCP recommends data source properties over parameters in the URL, and they keep secrets and tuning out of the connection string ([README](https://github.com/brettwooldridge/HikariCP)). The pgJDBC values ([connection parameters](https://jdbc.postgresql.org/documentation/use/)): `ApplicationName` (default "PostgreSQL JDBC Driver") shows up in `pg_stat_activity`, which is how you check rule 2 in production; `reWriteBatchedInserts` (default false) rewrites a batch of single-row inserts into multi-row inserts, which the docs put at 2-3x faster; `socketTimeout` (seconds, default 0, disabled) closes a connection whose read takes longer, so a dead network peer cannot hold a thread forever; keep it above `statement_timeout` so the server cancels slow queries first. `prepareThreshold` (default 5) switches a statement to a server-side prepared statement on its fifth execution, and `prepareThreshold=0` disables that. PgBouncer in transaction mode tracks protocol-level prepared statements when `max_prepared_statements` is above zero (200 by default) on 1.21 and later ([PgBouncer config](https://www.pgbouncer.org/config.html)); only with that disabled or an older PgBouncer set `prepareThreshold=0`. On a direct connection it throws away parse and plan caching for nothing.

**Rule 8.** A connection that is not closed never returns to the pool. After `maximumPoolSize` leaks, every borrow waits `connectionTimeout` and fails, which looks like an overloaded database while PostgreSQL is idle. `JdbcClient`, `JdbcTemplate` and Spring transactions close for you; plain JDBC needs try-with-resources on the connection, statement and result set. `leakDetectionThreshold` (default 0, disabled, minimum 2000 ms) logs the borrower's stack trace when a connection is held too long; turn it on in staging when you hunt a leak.

The cost: a fixed share per pod means one pod cannot burst past it even when the database has room, and a short borrow timeout turns some slow requests into 503s. That is the trade for a database that stays up while the service sheds load.

## Good example

See [`examples/good/`](examples/good/).

```java
    public static int poolSize(int connectionBudget, int maxReplicas, int maxSurgePercent) {
        int surge = Math.ceilDiv(maxReplicas * maxSurgePercent, 100);
        int peakPods = maxReplicas + surge;
        int size = connectionBudget / peakPods;
```

```java
        config.setMaximumPoolSize(poolSize);
        // minimumIdle stays unset, so the pool is fixed-size: no connection churn under bursts.
        config.setMaxLifetime(infraLimit.minusSeconds(30).toMillis());
        config.setKeepaliveTime(Duration.ofMinutes(2).toMillis());
        // A full pool fails the borrow in 500 ms instead of queueing requests for 30 s.
        config.setConnectionTimeout(500);
        config.setValidationTimeout(250);
        config.addDataSourceProperty("ApplicationName", "orders-api");
        config.addDataSourceProperty("reWriteBatchedInserts", "true");
        config.addDataSourceProperty("tcpKeepAlive", "true");
```

The same pool in Spring Boot, bound by a test with Boot's own `Binder` so the property names are checked:

```properties
# most 8 replicas; the Deployment's maxSurge is the default 25%, rounded up to 2
# pods: 80 / (8 + 2) = 8 per pod.
spring.datasource.hikari.maximum-pool-size=8
# minimum-idle is left unset: a fixed-size pool.
# 30 s below the proxy's 30-minute connection limit.
spring.datasource.hikari.max-lifetime=1770000
spring.datasource.hikari.keepalive-time=120000
spring.datasource.hikari.connection-timeout=500
spring.datasource.hikari.data-source-properties.ApplicationName=orders-api
spring.datasource.hikari.data-source-properties.reWriteBatchedInserts=true
```

```xml
<hikaricp.version>7.1.0</hikaricp.version>
<postgresql.version>42.7.14</postgresql.version>
```

`DatabaseTest` starts `postgres:17` with Testcontainers and proves the behavior instead of trusting the settings: the pool size comes from the budget and the surge (`poolSize(80, 8, 25)` is 8), a full pool caps what `pg_stat_activity` sees and fails the next borrow in well under two seconds, 200 virtual threads share at most four backends, the Boot properties bind to the same values, and the driver on the classpath is 42.7.14.

## Bad example

See [`examples/bad/`](examples/bad/).

```java
        // MISTAKE: "virtual threads are cheap, so give each one a connection". Connections
        // are PostgreSQL processes; past a few per core they add contention, not throughput.
        config.setMaximumPoolSize(1000);
        // MISTAKE: minimumIdle below the maximum turns the fixed-size pool into a
        // flexible one that opens connections during the burst that needs them.
        config.setMinimumIdle(5);
        // MISTAKE: 0 means no lifetime limit, so the pool never replaces a connection
        // before the database, PgBouncer or a load balancer closes it.
        config.setMaxLifetime(0);
```

```properties
# MISTAKE: one connection per Tomcat thread. 200 per pod x 8 pods during a deploy
# is 1,600 connections against a budget of 200: "too many clients" mid-rollout.
spring.datasource.hikari.maximum-pool-size=200
```

The example also disables `keepaliveTime`, sets `prepareThreshold=0` on a direct connection, waits 60 seconds for a connection, leaks the connection in `countOrders`, and leaves Boot's managed pgJDBC 42.7.13 in place. Each of these works with one pod on a laptop; the failures need a deploy, an hour of uptime, a firewall or a full pool.

## Checklist

When reviewing code under this skill, confirm:

- [ ] The resolved versions are HikariCP 7.1+ and pgJDBC 42.7.14+ (`mvn dependency:tree`), with the Boot BOM properties overridden where needed.
- [ ] `maximumPoolSize` is set explicitly and documented as budget / (max replicas + ceil(maxSurge)), with the surge taken from the Deployment (25% by default), and the fleet total fits the database's budget for this service.
- [ ] `minimumIdle` is not set (or equals `maximumPoolSize`).
- [ ] `maxLifetime` is set and shorter, by tens of seconds, than every database, PgBouncer, proxy and load balancer connection limit on the path.
- [ ] `keepaliveTime` is positive and below `maxLifetime`, and `tcpKeepAlive=true` is set.
- [ ] `connectionTimeout` is under a second or two and below the request deadline, and `SQLTransientConnectionException` maps to 503.
- [ ] Nothing derives the pool size from the number of threads, virtual or platform.
- [ ] `ApplicationName` is set per service, and `prepareThreshold=0` appears only behind a pooler without prepared statement support.
- [ ] Every `getConnection()` is in try-with-resources, or the code uses `JdbcClient`/`JdbcTemplate`.

## When not to apply

- **One pod that owns the database.** With a single replica and no autoscaling, the budget is the whole of `max_connections` minus reserved and admin slots; still set `maximumPoolSize` explicitly and keep it small, because the sizing formula is about the database's cores, not the service's.
- **Batch jobs and migration runners.** A one-shot job with a single statement stream needs one connection and no pool; Flyway opens its own (see [`db-migrations`](../db-migrations/SKILL.md)). A long batch job that waits for a connection instead of returning 503 can use a longer `connectionTimeout`, with its own small share of the budget.
- **Behind PgBouncer in transaction mode.** The pool size is then bounded by PgBouncer's client limit, not `max_connections`, so the per-pod size can be larger; the database-side pool lives in PgBouncer's `default_pool_size`. Keep everything else, and never rely on session state (`SET`, `LISTEN`, session advisory locks) through it.
- **Managed proxies with their own rules.** RDS Proxy and similar products pin sessions on some statements and close idle client connections on their own timer; read their documentation and set `maxLifetime` below their limit.
- **Several pools per service on purpose.** A separate small pool for a background worker, so it cannot starve request traffic, is a good reason to have two; both come out of the same budget.
