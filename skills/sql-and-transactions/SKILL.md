---
name: sql-and-transactions
description: Use when writing or reviewing Java code that runs SQL against PostgreSQL with JDBC, Spring JdbcClient/JdbcTemplate or @Transactional, building a query with a dynamic sort or filter, inserting many rows, paginating a list, retrying on deadlock or serialization failure, or calling another service while a transaction is open. Key rule: values only as ? or :name parameters, identifiers from an allow-list, never concatenated input; batch inserts with JdbcTemplate.batchUpdate plus reWriteBatchedInserts; keyset pagination, never OFFSET; short @Transactional methods called through the proxy (no this.method() self-invocation), readOnly = true for reads; no HTTP or queue calls inside a transaction (outbox row instead); rerun the whole transaction from outside on SQLSTATE 40001/40P01; checked exceptions need rollbackFor; spring.jpa.open-in-view=false with JPA.
metadata:
  verified: 2026-10-18
---

# SQL and transactions

## The decision

Once the pool exists (see [`postgres-hikari-pool`](../postgres-hikari-pool/SKILL.md)), every feature adds queries and transactions, and the way they are written decides how the service behaves with ten million rows and two hundred concurrent requests instead of ten rows and one user. The failures do not show up in a demo: a sort parameter that becomes SQL injection, an import that sends one statement per row, page 5,000 that takes seconds, a `@Transactional` that silently does nothing because the method calls itself, a transaction that holds row locks while a mail service times out, a deadlock that becomes a 500 instead of a retry. This skill fixes how SQL takes input, how many rows go per round trip, how lists are paged, and how transactions are scoped, retried and bounded in Spring. Pool sizing belongs to `postgres-hikari-pool`, schema changes to [`db-migrations`](../db-migrations/SKILL.md), and the database-side view of locking to db-skills [`pg-transactions-and-locking`](https://github.com/rafapeixer/db-skills/blob/main/skills/pg-transactions-and-locking/SKILL.md).

## The rule

1. Pass every value as a `?` or `:name` parameter (`JdbcClient`, `JdbcTemplate`, `PreparedStatement`), and when a column or sort direction must vary, map the request's key through an allow-list of SQL fragments written in code.
2. Insert many rows with one JDBC batch (`JdbcTemplate.batchUpdate`) on a pool with pgJDBC `reWriteBatchedInserts=true`, never one statement per row.
3. Paginate with a keyset (`where (created_at, id) < (?, ?) order by created_at desc, id desc limit ?`) on an index that ends in a unique column, never with `OFFSET`.
4. Put `@Transactional` on public service methods that callers reach through the Spring proxy, never call one from another method of the same class, and mark read paths `readOnly = true`.
5. Make no network call (HTTP, queue publish, email) inside a transaction: write the side effect to an outbox table in the same transaction and deliver it after commit.
6. Rerun the whole transaction on SQLSTATE `40001` or `40P01` with a retry that wraps the transaction from outside, with capped attempts and jittered backoff, and retry nothing else.
7. Roll back on checked exceptions too: declare `rollbackFor = Exception.class` on a transactional method that throws them, or throw unchecked exceptions.
8. Bound every transaction: a transaction timeout in code, `idle_in_transaction_session_timeout` on the service's role, and `spring.jpa.open-in-view=false` whenever JPA is on the classpath.

## Why

**Rule 1.** With a parameter the value travels separately from the SQL text, so no value can change the statement. Placeholders work only for values: a column name, `asc`/`desc` or a table name cannot be a parameter, which is exactly where string concatenation creeps in. Map `sort=newest` to a constant fragment and reject everything else; the allow-list also documents which sorts have an index. `JdbcClient` (Spring Framework 6.1+) takes positional `?` and named `:name` parameters in the same fluent API and maps rows to records ([JdbcClient](https://docs.spring.io/spring-framework/reference/data-access/jdbc/core.html)); use it for single statements.

**Rule 2.** Each statement is a network round trip and a parse on the server. Importing an order with 100 items one `insert` at a time is 100 round trips; at 1 ms each that is 100 ms of latency holding a connection and a transaction. `JdbcClient` has no batch API, and the Spring docs point to "plain direct `JdbcTemplate` usage" for batch inserts, so use `batchUpdate` with a `ParameterizedPreparedStatementSetter`. pgJDBC still sends a batch as separate inserts unless `reWriteBatchedInserts=true` (default false), which rewrites them into multi-row inserts and which its docs put at 2-3x faster ([connection parameters](https://jdbc.postgresql.org/documentation/use/)). For tens of thousands of rows outside a request, use PostgreSQL `COPY` through pgJDBC's `CopyManager`.

**Rule 3.** `OFFSET n` makes PostgreSQL produce and throw away `n` rows before the page, so cost grows with depth, and a crawler walking to the last page turns every request into a near full scan. Rows inserted between two requests shift the window, so users see duplicates or miss rows. A keyset page starts where the previous one ended; with an index on `(customer_id, created_at, id)` each page is an index range scan of `limit` rows. End the key with a unique column: `created_at` alone has ties, and a tie at the page edge drops rows, which the good example's test (five rows per timestamp) would catch. Hand the client an opaque cursor so it does not depend on its shape. The cost: no "jump to page 37" and no exact page count. For the SQL side see db-skills [`pg-query-patterns`](https://github.com/rafapeixer/db-skills/blob/main/skills/pg-query-patterns/SKILL.md).

**Rule 4.** In proxy mode, the default, "only external method calls coming in through the proxy are intercepted", so self-invocation "does not lead to an actual transaction at runtime even if the invoked method is marked with `@Transactional`" ([Spring docs](https://docs.spring.io/spring-framework/reference/data-access/transaction/declarative/annotations.html)). The code compiles, the tests with one happy path pass, and the first failure halfway through leaves an order without its items. Call the transactional method from another bean, or use `TransactionTemplate` inside the method. `readOnly = true` makes the transaction manager call `Connection.setReadOnly(true)`, which pgJDBC (default `readOnlyMode=transaction`) turns into `BEGIN READ ONLY`, so an accidental write fails instead of committing, and it tells a routing data source the work can go to a replica.

**Rule 5.** A transaction holds a pooled connection, and after its first write, row locks, until it ends. An HTTP call inside it makes the database wait on someone else's latency: a mail service that takes 2 s holds the locks for 2 s, and when it hangs, other requests queue behind those locks until the pool is exhausted. It is also wrong on its own terms: if the transaction rolls back, or is rerun by rule 6, the email has already gone, once per attempt. Writing the event as an outbox row in the same transaction makes it atomic with the business change; a relay delivers it after commit, at least once, so consumers must be idempotent. Reads from other services that decide what to write belong before the transaction starts.

**Rule 6.** PostgreSQL aborts transactions with `40001 serialization_failure` under `REPEATABLE READ` and `SERIALIZABLE`, and with `40P01 deadlock_detected` at any level; its docs say "it is important to retry the complete transaction, including all logic that decides which SQL to issue and/or which values to use" ([serialization failure handling](https://www.postgresql.org/docs/current/mvcc-serialization-failure-handling.html)). After either error the transaction is aborted and every further statement fails with `25P02`, so catching the exception and repeating one statement never works. The retry therefore has to sit outside the transaction: `RetryTemplate` (Spring Framework 7.0+, `org.springframework.core.retry`) around `TransactionTemplate.execute`, so each attempt is a fresh `BEGIN`. Calling it from inside another transaction joins that one and defeats the retry. Classify by `SQLException.getSQLState()` anywhere in the cause chain, never by message text. Retry nothing else: `23505 unique_violation` is usually a real conflict, and a statement timeout repeats the same slow query. Cap attempts (five in all is plenty) and add jitter so the two colliding transactions do not collide again. The good example's test makes two `REPEATABLE READ` transactions read the same stock row before either writes; one gets `40001`, is rerun, and the final stock is 8, not the 9 a lost update would leave.

**Rule 7.** By default "any `RuntimeException` or `Error` triggers rollback, and any checked `Exception` does not". A `@Transactional` method that throws `IOException` after its inserts commits them. Spring Framework 6.2+ can change the default globally with `@EnableTransactionManagement(rollbackOn = ALL_EXCEPTIONS)`; otherwise say it on the method.

**Rule 8.** A transaction left open by a stuck thread or a dead pod holds locks and blocks vacuum. `TransactionTemplate.setTimeout` (or `@Transactional(timeout = ...)`) makes Spring set a query timeout from the remaining time. On the server, `idle_in_transaction_session_timeout` terminates a session that sits idle inside an open transaction and `statement_timeout` cancels a slow statement; set them on the role with `ALTER ROLE ... SET` so migrations and maintenance can use other values. With JPA, Spring Boot registers `OpenEntityManagerInViewInterceptor` in web applications by default, which keeps the persistence context, and the connection it holds, for the whole request, including view rendering and lazy loads; set `spring.jpa.open-in-view` to `false` ([Spring Boot docs](https://docs.spring.io/spring-boot/reference/data/sql.html)) and load what the response needs inside the service transaction.

The cost: keyset pages cannot jump, the outbox adds a table and a relay and turns "exactly once" into "at least once with idempotent consumers", and a retry loop means the transaction body must be safe to run twice. Each is cheaper than the incident it prevents.

## Good example

See [`examples/good/`](examples/good/).

```java
    /** The retry wraps the transaction, so every attempt is a fresh BEGIN ... COMMIT. */
    public <T> T run(TransactionCallback<T> work) {
        return retry.invoke(() -> template.execute(work));
    }

    static boolean retryable(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (t instanceof SQLException sql && RETRYABLE.contains(sql.getSQLState())) {
                return true;
            }
        }
        return false;
    }
```

```java
    @Transactional
    public long placeOrder(long customerId, long totalCents, List<Item> items) {
        long id = jdbc.sql("insert into orders (customer_id, total_cents) values (?, ?) returning id")
                .param(customerId).param(totalCents)
                .query(Long.class).single();
        // One round trip with reWriteBatchedInserts=true instead of one insert per item.
        template.batchUpdate("insert into order_items (order_id, sku, qty) values (?, ?, ?)",
```

```java
        return jdbc.sql("""
                select id, customer_id, total_cents, created_at from orders
                where customer_id = ? and (created_at, id) < (?, ?)
                order by created_at desc, id desc limit ?""")
                .param(customerId).param(after.createdAt()).param(after.id()).param(limit)
                .query(Order.class).list();
```

`placeOrder` writes the order, its items as one batch and an outbox row in one short transaction, and `page` is a read-only keyset query. `OrderServiceTest` runs them through a real Spring context against `postgres:17` in Testcontainers: an invalid item rolls back the order row through the proxy, 25 rows with tied timestamps come back exactly once across pages, and a forced serialization failure is rerun as a whole transaction.

## Bad example

See [`examples/bad/`](examples/bad/).

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

The file also inserts items one statement at a time, lets a checked `IOException` commit, retries a single statement after a deadlock by matching the message, and `application.properties` turns `spring.jpa.open-in-view` on. Each passes a demo with one user and ten rows; the failures need a failure halfway, a slow dependency, a hostile parameter or two concurrent writers.

## Checklist

When reviewing code under this skill, confirm:

- [ ] Every value reaches the database as a `?`/`:name` parameter, and every dynamic identifier or direction comes from an allow-list in code.
- [ ] Multi-row inserts use `batchUpdate` (or `COPY` for bulk loads), and the pool sets `reWriteBatchedInserts=true`.
- [ ] No list endpoint uses `OFFSET` on a growing table; pages use a keyset on an indexed key ending in a unique column.
- [ ] No `@Transactional` method is called through `this`, and every transactional method is reached through a Spring bean.
- [ ] Read-only service methods are `@Transactional(readOnly = true)`.
- [ ] Nothing between transaction start and commit calls HTTP, a queue or email; side effects are outbox rows.
- [ ] Retries on `40001`/`40P01` wrap the whole transaction from outside, classify by SQLSTATE, cap attempts and add jitter.
- [ ] Transactional methods that throw checked exceptions declare `rollbackFor`, or the project sets `rollbackOn = ALL_EXCEPTIONS`.
- [ ] Transactions have a timeout, the role has `idle_in_transaction_session_timeout`, and `spring.jpa.open-in-view=false` is set when JPA is present.

## When not to apply

- **Truly dynamic queries.** Report builders with many optional filters can build SQL in one small function, as long as every value is a parameter, every identifier comes from an allow-list, and a test checks the generated SQL.
- **Small, bounded lists.** A settings page with at most 50 rows needs no pagination, and an admin table that needs page numbers over a few thousand rows can use `OFFSET`; switch to a keyset before the table grows.
- **Single-statement writes.** One `insert ... on conflict` or one `update` is atomic on its own and needs no explicit transaction; a deadlock can still hit it, so wrap it in the same retry when it touches rows other writers lock in a different order.
- **Effects that must happen before commit.** A payment authorization cannot be an outbox row: do it before the transaction, store its id inside it, and compensate (void) when the transaction fails.
- **Batch jobs and backfills.** They run as their own role with longer timeouts, commit in chunks of a few thousand rows, and may hold a connection far longer than a request; they still use parameters and keysets.
- **Non-PostgreSQL databases.** The SQLSTATE codes, `readOnlyMode` and the role settings here are PostgreSQL's and pgJDBC's; keep the shape of the retry and classify from that database's documentation.
