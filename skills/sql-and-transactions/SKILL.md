---
name: sql-and-transactions
description: Use when writing or reviewing Java code that runs SQL on PostgreSQL via JDBC, Spring JdbcClient/JdbcTemplate or @Transactional, loading a record with its children, inserting an order with its items or many rows, adding a dynamic sort, paginating, retrying on deadlock or serialization failure, or calling a service inside a transaction. Key rule: values only as ?/:name parameters, identifiers from an allow-list; a one-statement read runs in autocommit with no @Transactional (not even readOnly); load parent + children in one statement (LEFT JOIN LATERAL + array_agg/json_agg), insert parent + children in one data-modifying CTE with unnest arrays, many rows as one batch with reWriteBatchedInserts; keyset pagination, never OFFSET; @Transactional only around several statements, called through the proxy, rollbackFor for checked exceptions; no HTTP or queue calls in a transaction (outbox row); rerun the whole transaction on 40001/40P01; server-side statement_timeout, not @Transactional(timeout); open-in-view=false.
metadata:
  verified: 2026-10-19
---

# SQL and transactions

## The decision

Once the pool exists (see [`postgres-hikari-pool`](../postgres-hikari-pool/SKILL.md)), every feature adds queries and transactions, and the way they are written decides how the service behaves with ten million rows and two hundred concurrent requests instead of ten rows and one user. The failures do not show up in a demo: a sort parameter that becomes SQL injection, an import that sends one statement per row, page 5,000 that takes seconds, a `@Transactional` that silently does nothing because the method calls itself, a transaction that holds row locks while a mail service times out, a deadlock that becomes a 500 instead of a retry. This skill fixes how SQL takes input, how many statements a read or a write costs, how lists are paged, and how transactions are scoped, retried and bounded in Spring. Pool sizing belongs to `postgres-hikari-pool`, schema changes to [`db-migrations`](../db-migrations/SKILL.md), and the database-side view of locking to db-skills [`pg-transactions-and-locking`](https://github.com/rafapeixer/db-skills/blob/main/skills/pg-transactions-and-locking/SKILL.md).

## The rule

1. Pass every value as a `?` or `:name` parameter (`JdbcClient`, `JdbcTemplate`, `PreparedStatement`), and when a column or sort direction must vary, map the request's key through an allow-list of SQL fragments written in code.
2. Write a parent and its children with one data-modifying CTE that takes the children as `unnest` arrays, and many independent rows with one JDBC batch (`JdbcTemplate.batchUpdate` with pgJDBC `reWriteBatchedInserts=true`), never one statement per row.
3. Paginate with a keyset (`where (created_at, id) < (?, ?) order by created_at desc, id desc limit ?`) on an index that ends in a unique column, never with `OFFSET`.
4. Run a read as one statement in autocommit with no `@Transactional` (not even `readOnly = true`), loading a parent with its small, bounded children in that statement (`left join lateral` with `array_agg` or `json_agg`) instead of a second query.
5. Put `@Transactional` only on public service methods that run several statements which must commit together, reach them through the Spring proxy (never `this.method()`), and declare `rollbackFor = Exception.class` when they throw checked exceptions.
6. Make no network call (HTTP, queue publish, email) inside a transaction: write the side effect to an outbox table in the same transaction or statement and deliver it after commit.
7. Rerun the whole transaction on SQLSTATE `40001` or `40P01` with a retry that wraps the transaction from outside, with capped attempts and jittered backoff, and retry nothing else.
8. Bound every statement and transaction on the server, with `statement_timeout` and `idle_in_transaction_session_timeout` on the role or in pgJDBC's `options`, instead of `@Transactional(timeout)`, and set `spring.jpa.open-in-view=false` whenever JPA is on the classpath.

## Why

**Rule 1.** With a parameter the value travels separately from the SQL text, so no value can change the statement. Placeholders work only for values: a column name, `asc`/`desc` or a table name cannot be a parameter, which is exactly where string concatenation creeps in. Map `sort=newest` to a constant fragment and reject everything else; the allow-list also documents which sorts have an index. `JdbcClient` (Spring Framework 6.1+) takes positional `?` and named `:name` parameters in the same fluent API and maps rows to records ([JdbcClient](https://docs.spring.io/spring-framework/reference/data-access/jdbc/core.html)); use it for single statements.

**Rule 2.** Each statement is a network round trip and a parse on the server, and a transaction adds a `BEGIN` and a `COMMIT` round trip around them. Saving an order with its items as `BEGIN` + insert + batch insert + `COMMIT` is four round trips; one data-modifying CTE (`with o as (insert ... returning id), i as (insert ... select ... from o, unnest(?::text[], ?::int[]))`) is one. Data-modifying statements in `WITH` "are executed exactly once, and always to completion", and `RETURNING` is "the only way to communicate changes between different `WITH` sub-statements" ([WITH Queries](https://www.postgresql.org/docs/current/queries-with.html)); as one statement it is one implicit transaction, so the order, its items and the outbox row commit together or not at all. Pass the children as parallel arrays with `Connection.createArrayOf`. Measured in benchmarks/orders-api (TUNING.md): replacing the transaction plus batch with the CTE cut CPU per request by 9% (0.459 to 0.417 ms at 3,000 req/s). For many independent rows (an import, a backfill), `JdbcClient` has no batch API and the Spring docs point to "plain direct `JdbcTemplate` usage"; pgJDBC sends a batch as separate inserts unless `reWriteBatchedInserts=true` (default false), which rewrites them into multi-row inserts and which its docs put at 2-3x faster ([connection parameters](https://jdbc.postgresql.org/documentation/use/)). For tens of thousands of rows outside a request, use PostgreSQL `COPY` through pgJDBC's `CopyManager`.

**Rule 3.** `OFFSET n` makes PostgreSQL produce and throw away `n` rows before the page, so cost grows with depth, and a crawler walking to the last page turns every request into a near full scan. Rows inserted between two requests shift the window, so users see duplicates or miss rows. A keyset page starts where the previous one ended; with an index on `(customer_id, created_at, id)` each page is an index range scan of `limit` rows. End the key with a unique column: `created_at` alone has ties, and a tie at the page edge drops rows, which the good example's test (five rows per timestamp) would catch. Hand the client an opaque cursor so it does not depend on its shape. The cost: no "jump to page 37" and no exact page count. For the SQL side see db-skills [`pg-query-patterns`](https://github.com/rafapeixer/db-skills/blob/main/skills/pg-query-patterns/SKILL.md).

**Rule 4.** "PostgreSQL actually treats every SQL statement as being executed within a transaction. If you do not issue a `BEGIN` command, then each individual statement has an implicit `BEGIN` and (if successful) `COMMIT` wrapped around it" ([Transactions](https://www.postgresql.org/docs/current/tutorial-transactions.html)), and HikariCP hands out connections with `autoCommit` true by default ([README](https://github.com/brettwooldridge/HikariCP)). One statement also reads one snapshot, so a single-statement read is already consistent. `@Transactional(readOnly = true)` around it adds a `BEGIN READ ONLY` and a `COMMIT` round trip and holds the connection across everything in the method. Measured in benchmarks/orders-api (TUNING.md): each read was `BEGIN` + orders query + `order_items = any(?)` + `COMMIT`, and `PgConnection.commit` alone was 25% of the driver's CPU; one statement with a `left join lateral (select array_agg(...) ...)` on the page subquery, in autocommit, cut CPU per request by 25% (0.612 to 0.459 ms at 3,000 req/s) and the median latency of a 4,000 req/s spike from 31.5 to 3.5 ms. Read the arrays with `ResultSet.getArray`, or use `json_agg` when the children have many columns. The children must be bounded (an order's items, not a customer's history); page an unbounded child list on its own. Keep `@Transactional(readOnly = true)` for a read that needs several statements to see the same snapshot (with `REPEATABLE READ`).

**Rule 5.** In proxy mode, the default, "only external method calls coming in through the proxy are intercepted", so self-invocation "does not lead to an actual transaction at runtime even if the invoked method is marked with `@Transactional`" ([Spring docs](https://docs.spring.io/spring-framework/reference/data-access/transaction/declarative/annotations.html)). The code compiles, the tests with one happy path pass, and the first failure halfway through leaves half the work committed. Call the transactional method from another bean, or use `TransactionTemplate` inside the method. Plain methods it calls join its transaction: the good example's `importOrders` runs `placeOrder` once per order, and a failure in the second order rolls back the first. By default "any `RuntimeException` or `Error` triggers rollback, and any checked `Exception` does not". A `@Transactional` method that throws `IOException` after its inserts commits them. Spring Framework 6.2+ can change the default globally with `@EnableTransactionManagement(rollbackOn = ALL_EXCEPTIONS)`; otherwise say it on the method.

**Rule 6.** A transaction holds a pooled connection, and after its first write, row locks, until it ends. An HTTP call inside it makes the database wait on someone else's latency: a mail service that takes 2 s holds the locks for 2 s, and when it hangs, other requests queue behind those locks until the pool is exhausted. It is also wrong on its own terms: if the transaction rolls back, or is rerun by rule 7, the email has already gone, once per attempt. Writing the event as an outbox row in the same transaction makes it atomic with the business change; a relay delivers it after commit, at least once, so consumers must be idempotent. Reads from other services that decide what to write belong before the transaction starts.

**Rule 7.** PostgreSQL aborts transactions with `40001 serialization_failure` under `REPEATABLE READ` and `SERIALIZABLE`, and with `40P01 deadlock_detected` at any level; its docs say "it is important to retry the complete transaction, including all logic that decides which SQL to issue and/or which values to use" ([serialization failure handling](https://www.postgresql.org/docs/current/mvcc-serialization-failure-handling.html)). After either error the transaction is aborted and every further statement fails with `25P02`, so catching the exception and repeating one statement never works. The retry therefore has to sit outside the transaction: `RetryTemplate` (Spring Framework 7.0+, `org.springframework.core.retry`) around `TransactionTemplate.execute`, so each attempt is a fresh `BEGIN`. Calling it from inside another transaction joins that one and defeats the retry. Classify by `SQLException.getSQLState()` anywhere in the cause chain, never by message text. Retry nothing else: `23505 unique_violation` is usually a real conflict, and a statement timeout repeats the same slow query. Cap attempts (five in all is plenty) and add jitter so the two colliding transactions do not collide again. The good example's test makes two `REPEATABLE READ` transactions read the same stock row before either writes; one gets `40001`, is rerun, and the final stock is 8, not the 9 a lost update would leave.

**Rule 8.** A transaction left open by a stuck thread or a dead pod holds locks and blocks vacuum, and a runaway query holds a connection. On the server, `statement_timeout` aborts any statement that takes longer, counted "from the time a command arrives at the server until it is completed", and `idle_in_transaction_session_timeout` terminates a session that sits idle inside an open transaction ([Client Connection Defaults](https://www.postgresql.org/docs/current/runtime-config-client.html)). Set them on the role with `ALTER ROLE ... SET`, or per pool with pgJDBC's `options` (`-c statement_timeout=5000`), so migrations and maintenance can use other values. Do not reach for `@Transactional(timeout)` or `TransactionTemplate.setTimeout` with pgJDBC: `DataSourceTransactionManager` applies it "as appropriate JDBC statement timeouts" ([javadoc](https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/jdbc/datasource/DataSourceTransactionManager.html)), and the driver arms and cancels a timer task for every statement; measured in benchmarks/orders-api (TUNING.md), `PgConnection.addTimerTask` was the top Java method in JFR (6.1% of samples). Keep a bound either way: a server-side timeout, plus pgJDBC `socketTimeout` above it for a dead peer (see [`timeouts-and-deadlines`](../timeouts-and-deadlines/SKILL.md)). With JPA, Spring Boot registers `OpenEntityManagerInViewInterceptor` in web applications by default, which keeps the persistence context, and the connection it holds, for the whole request, including view rendering and lazy loads; set `spring.jpa.open-in-view` to `false` ([Spring Boot docs](https://docs.spring.io/spring-boot/reference/data/sql.html)) and load what the response needs inside the service.

The cost: keyset pages cannot jump, the outbox adds a table and a relay and turns "exactly once" into "at least once with idempotent consumers", and a retry loop means the transaction body must be safe to run twice. Each is cheaper than the incident it prevents.

## Good example

See [`examples/good/`](examples/good/).

```java
    private static final String PLACE = """
            with o as (insert into orders (customer_id, total_cents) values (?, ?) returning id),
                 i as (insert into order_items (order_id, sku, qty)
                       select o.id, s.sku, s.qty from o, unnest(?::text[], ?::int[]) as s(sku, qty)),
                 e as (insert into outbox (topic, payload)
                       select 'order-placed', jsonb_build_object('order_id', o.id) from o)
            select id from o""";
```

```java
            select o.id, o.customer_id, o.total_cents, o.created_at, i.skus, i.qtys
            from (select id, customer_id, total_cents, created_at from orders
                  where customer_id = ? %s
                  order by created_at desc, id desc limit ?) o
            left join lateral (select array_agg(sku order by sku) as skus, array_agg(qty order by sku) as qtys
                               from order_items where order_id = o.id) i on true
            order by o.created_at desc, o.id desc""";
    private static final String FIRST_PAGE = PAGE.formatted("");
    private static final String NEXT_PAGE = PAGE.formatted("and (created_at, id) < (?, ?)");
```

```java
    /** One statement in autocommit: no @Transactional, no BEGIN/COMMIT round trips. */
    public List<Order> page(long customerId, Cursor after, int limit) {
```

```java
    @Transactional(rollbackFor = Exception.class)
    public List<Long> importOrders(long customerId, List<List<Item>> orders) {
```

```java
    /** The retry wraps the transaction, so every attempt is a fresh BEGIN ... COMMIT. */
    public <T> T run(TransactionCallback<T> work) {
        return retry.invoke(() -> template.execute(work));
    }
```

`placeOrder` writes the order, its items and an outbox row in one statement with no `@Transactional`; `page` is one keyset statement that brings each order's items along; `importOrders` is the only method with several statements, so it is the only `@Transactional` one; the `%s` slot takes one of two constant fragments, never input. `OrderServiceTest` runs them through a real Spring context against `postgres:17` in Testcontainers: a bad item fails the whole statement and leaves no order, a failing second order rolls back the first through the proxy, 25 rows with tied timestamps come back exactly once across pages, a forced serialization failure is rerun as a whole transaction, and the pool's connections report `statement_timeout` 5s and `idle_in_transaction_session_timeout` 10s.

## Bad example

See [`examples/bad/`](examples/bad/).

```java
    // MISTAKE: a transaction around a read. readOnly = true still costs a BEGIN and a COMMIT
    // round trip and holds the connection across both queries, and timeout = 2 makes
    // pgJDBC arm a timer task for every statement.
    @Transactional(readOnly = true, timeout = 2)
```

```java
            // MISTAKE: self-invocation. this.placeOrder skips the Spring proxy, so no
            // transaction starts: a failure leaves an order without its items.
            this.placeOrder(1, items);
```

```java
        // MISTAKE: an HTTP call inside the transaction. The connection and the row locks
        // are held for the mail service's latency, and a rollback cannot unsend the email.
        http.send(HttpRequest.newBuilder(URI.create("https://mail.internal/send"))
```

```java
        // MISTAKE: request text concatenated into SQL. sort=id;drop table orders-- runs.
        // MISTAKE: OFFSET reads and discards every earlier row; page 5000 scans 100,000 rows,
        // and an insert between requests shifts rows across pages.
        return jdbc.sql("select * from orders where customer_id = " + customerId
                + " order by " + sort + " limit 20 offset " + (page * 20))
```

The file also loads the items with a second query, inserts items one statement at a time, lets a checked `IOException` commit, retries a single statement after a deadlock by matching the message, and `application.properties` turns `spring.jpa.open-in-view` on. Each passes a demo with one user and ten rows; the failures need load, a failure halfway, a slow dependency, a hostile parameter or two concurrent writers.

## Checklist

When reviewing code under this skill, confirm:

- [ ] Every value reaches the database as a `?`/`:name` parameter, and every dynamic identifier or direction comes from an allow-list in code.
- [ ] A parent and its children are inserted in one data-modifying CTE; many independent rows use `batchUpdate` with `reWriteBatchedInserts=true` (or `COPY` for bulk loads).
- [ ] No list endpoint uses `OFFSET` on a growing table; pages use a keyset on an indexed key ending in a unique column.
- [ ] A read that is one statement has no `@Transactional`, and bounded children come in the same statement (`left join lateral` + `array_agg`/`json_agg`), not a second query.
- [ ] `@Transactional` appears only on methods with several statements, none is called through `this`, and those throwing checked exceptions declare `rollbackFor` (or the project sets `rollbackOn = ALL_EXCEPTIONS`).
- [ ] Nothing between transaction start and commit calls HTTP, a queue or email; side effects are outbox rows.
- [ ] Retries on `40001`/`40P01` wrap the whole transaction from outside, classify by SQLSTATE, cap attempts and add jitter.
- [ ] The role or the pool's `options` set `statement_timeout` and `idle_in_transaction_session_timeout`, no `@Transactional(timeout)` adds a per-statement JDBC timeout, and `spring.jpa.open-in-view=false` is set when JPA is present.

## When not to apply

- **Truly dynamic queries.** Report builders with many optional filters can build SQL in one small function, as long as every value is a parameter, every identifier comes from an allow-list, and a test checks the generated SQL.
- **Small, bounded lists.** A settings page with at most 50 rows needs no pagination, and an admin table that needs page numbers over a few thousand rows can use `OFFSET`; switch to a keyset before the table grows.
- **Single-statement writes that deadlock.** One statement needs no explicit transaction, but a deadlock can still hit it; wrap it in the same retry when it touches rows other writers lock in a different order.
- **Large or unbounded children.** Aggregating thousands of child rows into one array makes one huge row; page the children with their own keyset query.
- **Effects that must happen before commit.** A payment authorization cannot be an outbox row: do it before the transaction, store its id inside it, and compensate (void) when the transaction fails.
- **Batch jobs and backfills.** They run as their own role with longer timeouts, commit in chunks of a few thousand rows, and may hold a connection far longer than a request; they still use parameters and keysets.
- **Non-PostgreSQL databases.** The SQLSTATE codes, `readOnlyMode` and the role settings here are PostgreSQL's and pgJDBC's; keep the shape of the retry and classify from that database's documentation.
