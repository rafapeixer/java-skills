# Tuning notes for java-tuned

`impl/java-tuned` started as a byte-for-byte copy of `impl/java-with` (the
service generated with java-skills: Spring Boot 4.1.1, Java 25, virtual
threads, HikariCP, `RequestLimitsFilter`, ECS logging, G1). Every change
below was measured with the go-skills harness
(`go-skills/benchmarks/orders-api/harness`) on the same host and the same
day, one stack at a time: one app replica with 2 vCPU and 1 GiB, Postgres
with 2 vCPU, 1 GiB and `max_connections=80`, nginx in front. The rules did
not change between runs: the contract tests pass in every run, the pool stays
inside the 80-connection budget for 8 replicas including surge, admission is
bounded and sheds with a fast 503, body limits, statement timeouts and the
pricing budgets are all still there, logging is unchanged, and nothing is
cached across requests.

SLO, as in the analyzer: p99 < 200 ms and < 1% errors (404 counts as OK).
"CPU ms/request" is app CPU (mean of the `docker stats` samples in a phase)
divided by the rate. Single-run numbers move by about ±5% for CPU and by
several points of error rate at the knee, so a kept change either moved CPU
by more than that, was repeated, or says below why it was kept anyway.

Scenarios: S1 ramp `200..3000 req/s`, S3 spike `800:20s,4000:30s,800:30s`,
S4 slow pricing `400:40s` at 1,500 ms, S7 warm high
`500:30s,1500:30s,3000:30s,4000:20s,5000:20s,6000:20s,8000:20s`. Two extra
shapes were used for A/B tests: "cpu" `1500:20s,3000:60s` (CPU averaged over
the middle 45 s of the 3,000 phase, about 12 samples, SD about 3%) and
"knee" `1500:20s,3000:20s,5000:30s,6000:30s`. Dev runs are in
`harness/results/jt-*`, profiling runs were made with a copy of the image
that adds JFR (`-XX:StartFlightRecording=settings=profile` plus
`jdk.CPUTimeSample#enabled=true`, the JDK 25 CPU-time sampler from JEP 509)
and copies the recording out before teardown.

## Result

Three repetitions of each scenario (`harness/results/t-*-java-tuned-r1..r3`)
against the recorded `j-*` runs of the two generated services, aggregated by
`harness/analyze` (medians, range in parentheses; java-without has two usable
S1 and S3 repetitions and one S4).

| | java-without | java-with | java-tuned |
|---|---|---|---|
| S1 capacity within SLO (ladder tops out at 3,000) | 3,000 | 2,500 | 3,000 (2,500-3,000) |
| S7 capacity within SLO | 3,000 | 0 (cold 500 req/s phase fails) | 5,000 (1,500-6,000) |
| S3 at 4,000 req/s: errors / p50 / p99 | 96.5% / 4,608 ms / 24.7 s | 26.7% / 55 ms / 1,111 ms | 1.2% (0-18.8) / 1.0 ms / 104 ms |
| S3 back at 800 req/s: errors / p50 | 28.4% / 6.9 ms (no recovery) | 0 / 1.6 ms | 0 / 1.3 ms |
| S7 errors at 4,000 / 5,000 / 6,000 req/s | 82.6% / 99.5% / 99.7% | 18.3% / 39.5% / 50.4% | 0 / 0 / 0 (max 0.16% / 0.88% / 12.9%) |
| S7 errors at 8,000 req/s (beyond capacity) | 99.0% | 80.8%, p99 2,002 ms | 32.8% fast 503, p99 371 ms |
| S4 slow pricing: errors / p99 | 14.7% / 2,002 ms | 14.7% / 1,003 ms | 15.2% / 1,003 ms |
| CPU ms per request, S1 at 1,200 req/s | 0.737 | 0.856 | 0.441 (−48% vs java-with) |
| CPU ms per request, S1 at 3,000 req/s | 0.605 | 0.636 (one run within SLO) | 0.348 (−45%) |
| Peak memory per replica (S1 / S7) | 296 / 717 MiB | 399 / 505 MiB | 661 / 746 MiB |
| Peak Postgres connections (pool + sampler) | 9 | 6 | 9 (pool 8 = 80 / 10 pods) |
| Startup ("Started ... in") | | 1.9-3.5 s | 0.94-1.24 s |

Every java-tuned run passed the contract. The r1 round (S1, S3 and S7 run
back to back) was slow across the board: CPU per request at 3,000 req/s was
0.525 ms against 0.335-0.362 in r2 and r3, and the 1,500 req/s phase of S7
cost 0.93 ms per request against 0.52-0.57, with no other container running
(the only extra container in `stats.csv` is the load generator). That round
holds every bad tuned number above (S3 18.8%, S7 capacity 1,500, S1 2,500).
Beyond capacity java-tuned only answers fast 503s: no client timeouts (status
0) and no 504s in any S7 phase up to 6,000 req/s, against thousands in
java-with.

## Where the time went before the changes

**Errors.** In a profiling run of the unchanged service (1,500, 3,000 and
4,000 req/s, recording `prof0`), `load.jsonl` reported about 18,800 503s. The app log had 584 `CannotCreateTransactionException`
(Hikari borrow timeout) and 12 "pricing concurrency limit reached"; the
admission filter does not log, so roughly 97% of the 503s were the
`RequestLimitsFilter` semaphore being full, not the pool. It was full because
requests were queued *behind* the pool: the Hikari timeout message reads
`total=5, active=5, idle=0, waiting=230`. 230 of the 256 admitted virtual
threads were parked waiting for one of 5 connections. Some borrows timed out
after 804 and 1,476 ms although `connectionTimeout` is 500 ms, which only
happens when the CPU is so busy that the waiting thread is not scheduled.

**CPU.** The app container sat at 200% CPU from 3,000 req/s up: the service
was CPU-bound, and every queue (pool, pricing bulkhead, admission) filled
because each request held its slot longer on a starved CPU. The JFR
CPU-time sampler (recording `prof1`, 24,680 samples, 1,500-4,000 req/s)
split the CPU like this:

| Component (first matching frame) | Share of CPU samples |
|---|---|
| PostgreSQL JDBC driver (mostly socket write/read) | 32.9% |
| Tomcat socket I/O | 18.8% |
| JDK `HttpClient` (pricing calls) | 15.8% |
| Spring MVC + Tomcat request processing | 13.2% |
| Spring transaction interceptor | 3.4% |
| Jackson (write + read) | 2.2% |
| Hikari | 0.8% |
| Logging | 0.2% |

`SocketDispatcher.write0` alone was 27% of all samples. Inside the JDBC
share, 25% was `PgConnection.commit` (its own round trip), and every read
was BEGIN + two SELECTs + COMMIT. The top Java method in the execution
sampler was `PgConnection.addTimerTask` (6.1% of samples), reached from
`PgStatement.startTimer`: `@Transactional(timeout = 2)` became a JDBC query
timeout on every statement and the driver scheduled a `java.util.Timer` task
for each one (`java.util.Timer.schedule` was 1.45% of CPU time on its own).

Logging was not a cost: the service writes no access log, and the only lines
under load are WARNs for failed borrows and failed quotes (0.2% of CPU).

## Kept changes

Numbers are single runs on the same day unless marked; `b0` is the unchanged
copy measured that morning.

| Step | S1 CPU ms/req at 3,000 | S7 errors at 4,000 / 5,000 / 6,000 | S3 errors / p50 at 4,000 |
|---|---|---|---|
| b0 unchanged copy | 0.638 | 8.4% / 36.2% / 46.5% | 21.1% / 54.8 ms |
| 1. pool 5 → 8 | 0.599 | 6.3% / 22.3% / 38.5% | 9.5% / 28.8 ms |
| 2. no `@Transactional(timeout)` | 0.612 | 2.5% / 24.4% / 44.6% | 13.6% / 31.5 ms |
| 3. reads: one statement, no transaction | 0.459 | 4.0% / 15.8% / 20.0% | 7.0% / 3.5 ms |
| 4. create: one statement | 0.417 | 0 / 2.1% / 15.7% | 10.9% / 11.7 ms |
| 5. fixed heap (Xms = Xmx) | 0.435 | 0 / 11.2% / 12.1% | 5.0% / 4.7 ms |
| 6. Tomcat accept backlog 1024 | (see below) | | |
| 7. `HttpClient` virtual-thread executor | 0.427 → 0.358 in the "cpu" shape | | |
| 8. AOT cache | 0.343 | 0 / 0 / 5.7% | 0.7% / 1.0 ms |
| 9. pricing bulkhead 200 → 512 | | 3 runs at 200: 0 / 3.9% / 2.5% (medians); 2 runs at 512: 0 / 0 / 0 and 0 / 0 / 2.8% | |

### 1. Pool size from the real surge: 5 → 8 (`application.properties`, `DatabaseConfig`)

`java-with` sized the pool as 80 / (8 replicas + 8 surge) = 5. A Deployment
does not double during a rolling update: `maxSurge` "is calculated from the
percentage by rounding up. The default value is 25%" ([Kubernetes,
Deployments](https://kubernetes.io/docs/concepts/workloads/controllers/deployment/#max-surge)),
so 8 replicas run at most 8 + ceil(2) = 10 pods and the budget allows
80 / 10 = 8 connections each. Peak `pg_stat_activity` was 9 in every run
(8 app connections plus the sampler). Measured: S7 errors at 5,000 req/s
36% → 22%, S3 errors at 4,000 21% → 9.5%; CPU per request did not change (the
pool was a queue, not a CPU cost).

HikariCP's own guidance agrees with a small pool and with threads waiting on
it: "You want a small pool of a few dozen connections at most" and "you want
the rest of the application threads blocked on the pool awaiting
connections" ([About Pool
Sizing](https://github.com/brettwooldridge/HikariCP/wiki/About-Pool-Sizing)).
The fix is the surge arithmetic, not a bigger pool: what really emptied the
queue was steps 3 and 4, which cut how long each request holds a connection.

### 2. Statement timeouts on the server only (`OrderStore`)

`@Transactional(timeout = N)` is applied by `DataSourceTransactionManager`
"as appropriate JDBC statement timeouts"
([javadoc](https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/jdbc/datasource/DataSourceTransactionManager.html)),
and the JFR stacks show what that costs with the PostgreSQL driver: a timer
task armed and cancelled per statement (`PgStatement.startTimer` →
`PgConnection.addTimerTask` → `java.util.Timer.schedule`). The service
already sets `statement_timeout=5000` in the connection `options`, which the
server enforces for every statement "from the time a command arrives at the
server until it is completed"
([PostgreSQL, Client Connection Defaults](https://www.postgresql.org/docs/current/runtime-config-client.html)),
and `socketTimeout` still catches a dead peer. The annotation timeouts were
removed. Measured alone the effect is inside single-run noise (S1 CPU at
2,500 req/s 0.664 → 0.613 ms, at 3,000 0.599 → 0.612); it is kept because it
removes a measured hot method and loses no protection, and step 3 removes the
read transactions anyway.

### 3. Reads: one statement, no transaction (`OrderStore`)

`GET /orders/{id}`, `GET /customers/{id}/orders` and the item lookup of the
quote each ran BEGIN (read-only) + orders query + `order_items = ANY(?)` +
COMMIT: three round trips and a borrowed connection held across all of
them. They now run one statement in autocommit, with the items aggregated
per order:

```sql
SELECT o.id, o.customer_id, o.status, o.created_at, i.skus, i.qtys
FROM (SELECT ... FROM orders WHERE customer_id = ? [AND (created_at, id) < (?, ?)]
      ORDER BY created_at DESC, id DESC LIMIT ?) o
LEFT JOIN LATERAL (SELECT array_agg(sku ORDER BY sku) AS skus, array_agg(qty ORDER BY sku) AS qtys
                   FROM order_items WHERE order_id = o.id) i ON true
ORDER BY o.created_at DESC, o.id DESC
```

A single statement needs no explicit transaction: "PostgreSQL actually
treats every SQL statement as being executed within a transaction. If you do
not issue a BEGIN command, then each individual statement has an implicit
BEGIN and (if successful) COMMIT wrapped around it"
([PostgreSQL tutorial,
Transactions](https://www.postgresql.org/docs/current/tutorial-transactions.html)),
and Hikari hands out connections with `autoCommit` true by default
([HikariCP README](https://github.com/brettwooldridge/HikariCP)). The quote
endpoint reuses the single-order statement, so it also lost the separate
`EXISTS` query. This is the same change as go-tuned's change 1.

Measured: S1 CPU per request at 3,000 req/s 0.612 → 0.459 ms (−25%), at
2,000 0.622 → 0.501 (−19%); S7 errors at 6,000 req/s 45% → 20%; S3 p50 at
4,000 req/s 31.5 → 3.5 ms. Reads are 70% of the mix, so this was the largest
single change.

### 4. Create: one statement (`OrderStore`)

`POST /orders` ran BEGIN + `INSERT ... RETURNING id` + a batched (rewritten)
items insert + COMMIT. It is now one data-modifying CTE with the items as two
parallel arrays:

```sql
WITH o AS (INSERT INTO orders (customer_id) VALUES (?) RETURNING id),
     i AS (INSERT INTO order_items (order_id, sku, qty)
           SELECT o.id, s.sku, s.qty FROM o, unnest(?::text[], ?::int[]) AS s(sku, qty))
SELECT id FROM o
```

Data-modifying statements in `WITH` "are executed exactly once, and always
to completion", and `RETURNING` is "the only way to communicate changes
between different WITH sub-statements"
([PostgreSQL, WITH Queries](https://www.postgresql.org/docs/current/queries-with.html));
as one statement it is one implicit transaction, so the order and its items
still commit together or not at all (the contract's create/get tests pass).
Measured: S1 CPU at 3,000 req/s 0.459 → 0.417 ms (−9%), S7 errors at 5,000
req/s 15.8% → 2.1%.

### 5. Fixed heap size: `-XX:InitialRAMPercentage=75` next to `MaxRAMPercentage=75` (`Dockerfile`)

With only `MaxRAMPercentage`, JFR showed the heap starting at 16 MB
(`jdk.GCHeapConfiguration initialSize = 16.0 MB`) and G1 sizing it to about
150-180 MB in use under load, with young pauses of 56-62 ms and concurrent
cycle pauses up to 81 ms at 4,500 req/s (recording `prof2`). With the heap
fixed at 768 MB there were 30 collections in a whole 1,500/3,000/6,000 req/s
run instead of 235, steady-state pauses of 5-25 ms (the long ones were all in
the first seconds after start). Oracle's G1 tuning guide recommends exactly
this for consistent pauses: "set the options -Xms and -Xmx to the same value"
([HotSpot GC Tuning Guide, G1](https://docs.oracle.com/en/java/javase/25/gctuning/garbage-first-garbage-collector-tuning.html)).

The error-rate effect is near the noise floor, so it was A/B-tested twice on
the "knee" shape with everything else equal: at 6,000 req/s the fixed heap
gave 0.09% and 1.67% errors, the default heap 10.0% and 1.8%. Cost: peak
container memory 430-530 MiB → about 700 MiB of the 1 GiB limit.

### 6. Tomcat accept backlog: `server.tomcat.accept-count=1024` (`application.properties`)

Every overloaded run had a p99 near 1,040 ms and a few status-0 (client
timeout) and 504 errors. That number is the TCP SYN retransmission: the
container's `/proc/net/netstat` after an S3-shaped run showed
`ListenOverflows 95`, `ListenDrops 95`. When the app stalls, nginx opens new
upstream connections, and Tomcat's listen backlog defaults to 100
(`acceptCount`: "When this queue is full, the operating system may actively
refuse additional connections or those connections may time out. The default
value is 100" — [Tomcat 11 HTTP
connector](https://tomcat.apache.org/tomcat-11.0-doc/config/http.html);
Spring Boot `server.tomcat.accept-count`, default 100). With 1024 (the
kernel cap, `net.core.somaxconn`, is 4096 in the container): `ListenOverflows
0`, the 4,000 req/s phase max latency 1,234 → 458 ms, client timeouts 67 → 7.
Admission still sheds with 503; this only stops the kernel from dropping
connections before Tomcat can answer them.

### 7. `HttpClient` with a virtual-thread executor (`PricingClient`)

With pricing at 15% of requests (3 lookups each), the JDK `HttpClient` was
16-22% of the CPU samples, and 66% of those samples ran on
`ThreadPoolExecutor` worker threads: the client's default executor, which
"uses a thread pool, with a custom thread factory"
([HttpClient.Builder.executor](https://docs.oracle.com/en/java/javase/25/docs/api/java.net.http/java/net/http/HttpClient.Builder.html)),
runs the response parsing and completes the future, then hands back to the
caller's virtual thread. `.executor(Executors.newVirtualThreadPerTaskExecutor())`
keeps those hand-offs on the virtual-thread scheduler. Measured on the "cpu"
shape (12 samples each, SD 3%): 0.427 → 0.358 ms per request at 3,000 req/s
(−16%), contract and pricing timeouts unchanged.

### 8. AOT cache trained in the Dockerfile (JEP 483/514)

The runtime image extracts the jar (`java -Djarmode=tools -jar ... extract`)
and runs `java -XX:AOTCacheOutput=app.aot -Dspring.context.exit=onRefresh
-jar orders-api.jar` once at build time; the entrypoint adds
`-XX:AOTCache=app.aot`. This is the procedure in [Spring Boot, AOT
Cache](https://docs.spring.io/spring-boot/reference/packaging/aot-cache.html)
("You have to use the cache file with the extracted form of the
application"), and `AOTCacheOutput` does the training and creation in one
command ([JEP 514](https://openjdk.org/jeps/514)). The training run has no
database, so `DatabaseConfig` takes `orders.db.initialization-fail-timeout-ms`
(default 1, Hikari's default: fail fast at startup) and only the build sets it
to -1. The JVM options during training match production (same
`JAVA_TOOL_OPTIONS`, `-XX:MaxRAM=1g`).

Measured: startup 1.93 s → 1.00 s ("Started OrdersApiApplication in ...").
The first loaded seconds are still the weakest point: the "cpu" shape's cold
1,500 req/s phase had 3.1-5.1% errors with the cache and 5.1% without, which
is inside the spread. The cache holds loaded and linked classes from startup
only; the cold-phase errors come from the JIT compiling request paths
(late C2 compilations and 149 `unstable_if` deoptimizations show up in JFR
while the load is already running). JEP 515 can also store method profiles,
but only from a representative training workload ([JEP
515](https://openjdk.org/jeps/515)), which a build without a database cannot
run.

### 9. Pricing bulkhead sized from the measured rate: 200 → 512 (`PricingClient`)

After step 8 the first full three-repetition set (kept as
`results/jt-pb200-*`) still lost 2-11% of requests at 5,000 and 6,000 req/s
in S7 while the CPU was at 1.4-1.9 cores, all as 503. The app logs had
4,229-5,954 "pricing concurrency limit reached" per S7 run: the bulkhead
comment had estimated 30 quotes/s per replica, the measured capacity is about
900 quotes/s (6,000 req/s × 15%), so 2,700 pricing calls/s. At 30 ms that is
about 80 in flight on average (Little's law), and every short stall (a G1
pause, a C2 compile, a CPU-saturated second) adds 2.7 calls per millisecond,
so 200 filled in bursts. With 512 (80 average plus a 100 ms stall, rounded
up): S7 at 5,000 req/s 0 and 0% errors, at 6,000 0 and 2.8%, with the
rejection count down to 661-1,261 per run, nearly all at 8,000 req/s where
the replica is out of CPU. The slow-pricing scenario S4 was the same (14.3%
errors, all fast 503s, p99 1,005 ms, against 14.8-15.3% with 200), because
at 1.5 s pricing the call cap, the quote budget and the request admission
limit still bound everything. This is the same correction go-tuned needed
(64 → 256).

## Tried and not kept

- **ZGC** (`-XX:+UseZGC`, generational in JDK 25, same fixed heap): CPU
  0.371 ms per request against 0.358-0.364 with G1, the knee at 6,000 req/s
  1.13% errors (inside G1's spread), and peak memory 950 MiB of the 1 GiB
  limit. G1 stays.
- **No `HttpRequest.timeout`** (only `future.get(timeout)` plus `cancel`): JFR
  showed 73 ms lock contention in `HttpClientImpl.purgeTimeoutsAndReturnNextDeadline`.
  Two knee runs each way: 6,000 req/s errors 3.23% / 0.14% without it, 0.09% /
  1.67% with it; CPU 0.364 against 0.358 ms. No difference, so the original
  double bound stays.
- **Admission limit.** Not changed, on measurement: at 6,000 req/s the mean
  request is about 5 ms, so about 30 are in flight (Little's law); 256 covers
  a 35 ms stall at that rate and bounds the queue at about 40 ms under
  overload. Beyond capacity the tuned service sheds with fast 503s (8,000
  req/s: 22-48% errors, p99 193-537 ms, 2 client timeouts in three runs).
  The filter comment now carries the measured arithmetic.
- **Logging.** Nothing to change: there is no per-request log line, and
  logging was 0.2% of CPU.

## What is left

At about 0.34 ms of CPU per request the replica saturates its 2 vCPU near
6,000 req/s. In the last profile, socket syscalls are most of what remains:
one `write` per SQL statement and per HTTP response, and the `HttpClient`
selector wake-ups (`EventFD.set0` 5%). go-tuned reaches 10,000 req/s at
0.18 ms per request on the same box.

## Rules this suggests for the skills

Each rule names the skill it belongs in. The measurement behind it is in the
section above.

1. **postgres-hikari-pool**: size the pool as connection budget / (replicas +
   surge), with surge taken from the Deployment: `maxSurge` defaults to 25%,
   rounded up, so 8 replicas surge to 10 pods, not 16. Write the arithmetic in
   the comment. (80 / 16 = 5 left the pool as the queue: `waiting=230` on 5
   connections; 80 / 10 = 8 cut S3 errors from 21% to 9.5%.)
2. **sql-and-transactions**: a read that is one statement needs no
   `@Transactional`; in autocommit PostgreSQL wraps it in its own
   transaction. A `@Transactional(readOnly = true)` read costs a COMMIT round
   trip (25% of the JDBC CPU here) and holds the connection across every
   statement in it.
3. **sql-and-transactions**: load a parent and its small, bounded children in
   one statement (`LEFT JOIN LATERAL (SELECT array_agg(...) ...)` on the
   page subquery) instead of a second `= ANY(?)` query; read the arrays with
   `ResultSet.getArray`. Measured −25% CPU per request (same rule as go-skills
   `sql-queries-and-transactions` rule 4).
4. **sql-and-transactions**: insert a parent and its children atomically with
   one data-modifying CTE (`WITH o AS (INSERT ... RETURNING id) INSERT ...
   SELECT ... FROM o, unnest(?::text[], ?::int[])`) instead of a transaction
   with a batch; one round trip instead of four. Measured −9% CPU.
5. **timeouts-and-deadlines** (and **sql-and-transactions**): with the
   PostgreSQL driver, bound statements with `statement_timeout` in the
   connection `options`, not with `@Transactional(timeout)`, which Spring
   turns into a JDBC query timeout per statement and the driver into a
   `java.util.Timer` task per statement (top Java method in JFR, 6%).
6. **outbound-http-client** (and **virtual-threads**): build the JDK
   `HttpClient` with `.executor(Executors.newVirtualThreadPerTaskExecutor())`
   in a virtual-thread service. The default executor is a platform thread
   pool that runs the client's async stages; with it, 66% of the client's CPU
   samples were on pool workers. Measured −16% CPU per request with 15% of
   requests making 3 calls.
7. **overload-protection**: size every bulkhead from the measured rate at
   capacity, not from a guess: in-flight = calls/s × normal latency, plus
   calls/s × the longest stall you expect (GC pause, JIT, a CPU-saturated
   second). The pricing bulkhead (estimated from 30 quotes/s, real 900/s)
   turned stalls into 503s at 5,000-6,000 req/s; 200 → 512 removed them
   without changing the slow-dependency result.
8. **overload-protection**: before tuning, attribute every 503 to its source.
   Here 97% came from the unlogged admission filter and only 3% from pool
   timeouts, and the filter was full because requests were queued behind the
   pool on a CPU-bound replica. Make shed responses countable (a counter or
   a sampled log line), or the load test cannot tell the limits apart.
9. **http-server**: set `server.tomcat.accept-count` (Tomcat `acceptCount`,
   default 100) to about 1024, below `net.core.somaxconn`. Behind a proxy
   that opens connections in bursts, the default backlog overflows
   (`ListenOverflows` in `/proc/net/netstat`), the kernel drops the SYN and
   the client retries after 1 s: the signature is a p99 near 1,040 ms plus
   client timeouts and 504s that the app never sees.
10. **container-runtime-tuning**: in a fixed-size container, set
    `-XX:InitialRAMPercentage` equal to `-XX:MaxRAMPercentage` with G1. The
    default started the heap at 16 MB and kept it at about 150-180 MB under
    load, with 56-81 ms pauses; fixed at 768 MB, 30 collections per run
    instead of 235 and steady-state pauses of 5-25 ms. Budget about 700 MiB
    of a 1 GiB container. Keep G1 at 2 vCPU / 1 GiB: generational ZGC used
    as much CPU and 950 MiB of memory for no gain in errors.
11. **fast-startup-aot-cache**: in the Dockerfile, extract the jar (`java
    -Djarmode=tools -jar app.jar extract`), run `java
    -XX:AOTCacheOutput=app.aot -Dspring.context.exit=onRefresh -jar ...`
    with the production JVM options, and start with `-XX:AOTCache=app.aot`.
    If a bean connects at startup (Hikari fails fast by default), make that
    configurable and relax it only in the training run. Expect startup to
    halve (1.9 → 1.0 s); do not expect it to remove the cold-JIT errors in
    the first seconds under load, which need profiles from a representative
    training workload (JEP 515) or a warm-up before readiness.
12. **profiling-jfr**: on JDK 25 / Linux, profile with
    `jdk.CPUTimeSample#enabled=true` (JEP 509) and read
    `jfr view cpu-time-hot-methods`. The classic execution sampler showed
    only Java frames (top: a timer method at 6%); the CPU-time sampler showed
    that 27% of CPU was `SocketDispatcher.write0`, which points at round
    trips, not at Java code. Also read `contention-by-site`,
    `pinned-threads`, `gc-pauses` and `jdk.GCHeapConfiguration` (initial
    heap size) before changing flags.
13. **logging-structured**: no change needed, and worth saying in the skill:
    with no per-request access log the ECS encoder was 0.2% of CPU even under
    overload; the WARN lines for failed borrows and quotes are cheap. Logging
    was not the problem it was suspected to be.
