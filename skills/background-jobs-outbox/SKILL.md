---
name: background-jobs-outbox
description: Use when writing or reviewing Java code that must publish an event or start background work because a row was written (send to Kafka, RabbitMQ, SNS or a webhook after saving an order), uses @TransactionalEventListener, TransactionSynchronization.afterCommit or a broker send inside @Transactional, builds an outbox table, relay, job table or worker that several replicas poll, or makes a consumer safe against redelivery. Key rule: insert the event into an outbox table in the same transaction as the state change, never publish inside @Transactional or "after commit" with nothing durable behind it; a relay claims rows with one autocommit UPDATE ... WHERE id IN (SELECT ... FOR UPDATE SKIP LOCKED LIMIT n) that leases them, publishes with no transaction open, then marks them sent; cap attempts with a dead state and an alert; delivery is at least once, so every consumer dedupes on the event id in the same transaction as its effect; claim job rows the same way.
metadata:
  verified: 2026-10-18
---

# Background jobs and the transactional outbox

## The decision

A service saves an order and something else must happen because of it: publish `order.placed` to Kafka, call a partner's webhook, send the receipt. [`sql-and-transactions`](../sql-and-transactions/SKILL.md) rule 5 already forbids doing that inside the transaction and says to write an outbox row instead. This skill covers the rest: what the row looks like, how a relay publishes it, how several replicas share the table without publishing a row twice or never, what happens when the broker is down, and why consumers must tolerate duplicates. The failures are silent and only show up under a crash, a deploy, a second pod or a broker outage: events for orders that were rolled back, orders whose event was never sent, the same event published by two replicas, a relay that holds row locks while the broker times out. The same claiming pattern runs any job table. The SQL side belongs to db-skills: locking in [`pg-transactions-and-locking`](https://github.com/rafapeixer/db-skills/blob/main/skills/pg-transactions-and-locking/SKILL.md), partial indexes in [`pg-indexing`](https://github.com/rafapeixer/db-skills/blob/main/skills/pg-indexing/SKILL.md), and the churn of a queue table in [`pg-vacuum-and-bloat`](https://github.com/rafapeixer/db-skills/blob/main/skills/pg-vacuum-and-bloat/SKILL.md); pool sizing to [`postgres-hikari-pool`](../postgres-hikari-pool/SKILL.md).

## The rule

1. Insert the event into an outbox table on the same connection and in the same transaction as the state change, and never call a broker, webhook or mail API from inside `@Transactional`.
2. Never publish from `afterCommit`, `@TransactionalEventListener` or a thread started after commit unless a durable row already records the event.
3. Claim pending rows in one autocommit statement, `UPDATE ... WHERE id IN (SELECT ... FOR UPDATE SKIP LOCKED LIMIT n) RETURNING ...`, that moves `available_at` forward as a lease, on a partial index of pending rows.
4. Publish each claimed row with no transaction open, wait for the broker's acknowledgement, and only then set `sent_at`.
5. Count attempts, let the lease expiry retry a failed row, and after a capped number of attempts set `dead_at` and alert on it.
6. Make every consumer idempotent: record the event id in the same transaction as its effect (`insert ... on conflict do nothing`), because delivery is at least once.
7. Run background jobs from a job table claimed the same way, with a bounded number of workers per replica, never one thread per pending row.

## Why

**Rule 1.** The outbox exists because a database commit and a broker send cannot be atomic: there is no transaction that spans both. Write the event as a row next to the order and PostgreSQL makes them atomic for you: both commit or both roll back, including when the caller's transaction rolls back after your method returned or when [`sql-and-transactions`](../sql-and-transactions/SKILL.md) rule 6 reruns the whole transaction on `40001`. Sending inside the transaction gets both directions wrong. A rollback after the send leaves consumers holding an order that does not exist, a retry sends it once per attempt, and while the broker is slow the transaction holds the pooled connection and every row lock it took. The good example's test runs `placeOrder` inside a caller transaction that is then rolled back and checks that no outbox row survives.

**Rule 2.** Spring's `afterCommit` callback and `@TransactionalEventListener(phase = AFTER_COMMIT)` run in memory after the commit. A crash, a deploy's SIGTERM or one broker error between `COMMIT` and the send loses the event, and nothing records that it was lost: the order exists, the downstream system never hears of it, and no retry can happen because no one knows. The listener is fine for work that may be lost (warming a cache); for anything that must happen, the listener's only safe job is to wake the relay early. Spring Modulith's event publication registry is the Spring-supported way to make in-process listeners durable: it "writes entries for each of them ... into an event publication log as part of the original business transaction" ([Spring Modulith events](https://docs.spring.io/spring-modulith/reference/events.html)). Its own docs say its native broker externalization "lacks critical features developers might expect from actual outbox pattern implementations", so for publishing to a broker, the table and relay in this skill are the default.

**Rule 3.** A relay that runs `select ... where sent_at is null limit 100` with no lock reads the same rows as every other replica, so each event is published once per replica. `FOR UPDATE` alone makes the second replica wait for the first one's rows, so replicas take turns instead of sharing the work. The PostgreSQL docs describe `SKIP LOCKED` for exactly this case: skipping locked rows "provides an inconsistent view of the data, so this is not suitable for general purpose work, but can be used to avoid lock contention with multiple consumers accessing a queue-like table" ([SELECT, the locking clause](https://www.postgresql.org/docs/current/sql-select.html#SQL-FOR-UPDATE-SHARE)). Doing the claim as one `UPDATE` that pushes `available_at` into the future turns the short row lock into a lease that outlives the statement: the statement commits immediately, other relays skip the rows because they are no longer due, and a relay that dies mid-batch loses nothing, since its rows become due again when the lease ends. Index only the pending rows (`where sent_at is null and dead_at is null`), or the claim slows down as sent rows pile up. The good example's test runs two relays concurrently over 200 rows and checks that each row was published exactly once and both relays took part.

**Rule 4.** Holding a transaction open across the send is the rule 1 mistake again, now in the relay: the locks and the pooled connection wait on the broker, and a broker error on row 50 of a batch rolls back `sent_at` for rows 1 to 49, which were already published and go out again. Publish with no transaction, and treat "sent" as "the broker acknowledged it" (a Kafka producer `send(...).get()` with a timeout, or publisher confirms on RabbitMQ), not "the client library accepted it into a buffer". Mark each row right after its acknowledgement so a crash repeats at most one row's publish per batch, not the batch.

**Rule 5.** A broker outage turns a relay without a cap into a tight loop over the same rows, and one malformed event (a payload the broker rejects every time) is retried forever and stays at the head of the queue. `attempts` counts claims; the lease length is the retry delay, and growing it with `attempts` gives backoff. After `maxAttempts`, `dead_at` takes the row out of the claim and the partial index; that is where lost work lives, so alert on `count(*) where dead_at is not null` and give someone a way to fix and requeue it (`update outbox set dead_at = null, attempts = 0 where id = ?`).

**Rule 6.** No relay gives exactly once. A relay can die after the broker acknowledged and before `sent_at` was written, a lease can expire while a slow publish is still running, and brokers redeliver too. Send the outbox id with every message (a header or the message key) and have consumers record it as the primary key of a `processed_events` table in the same transaction as their effect: a duplicate inserts nothing, the consumer sees zero rows changed and skips the effect. The good example's test delivers the same event twice and the customer gets the points once. When the effect is an external API, pass an idempotency key derived from the event id, never from the attempt.

**Rule 7.** A job table is an outbox whose consumer is this service: the same claim with `SKIP LOCKED`, the same lease, attempts and dead state. Each job usually needs a database connection from the same pool the requests use, so run a fixed number of workers per replica (a fraction of `maximumPoolSize`), each looping over claims; a virtual thread per pending row turns a backlog after an outage into thousands of threads queueing on a 10-connection pool ([`virtual-threads`](../virtual-threads/SKILL.md)). Stop claiming on shutdown and let in-flight jobs finish within the platform's grace period.

The cost: a table, a relay loop and a dead-letter alert, a delay of up to one poll interval between commit and publish (poll every 100 ms to 1 s, or wake the relay early with `LISTEN/NOTIFY` and keep polling as the fallback), and consumers that must dedupe. That is cheaper than events for orders that were rolled back and orders whose events never left.

## Good example

See [`examples/good/`](examples/good/).

```java
    @Transactional
    public long placeOrder(long customerId, long totalCents) {
        long id = jdbc.sql("insert into orders (customer_id, total_cents) values (?, ?) returning id")
                .param(customerId).param(totalCents)
                .query(Long.class).single();
        jdbc.sql("insert into outbox (topic, payload) values ('order.placed', ?::jsonb)")
                .param("{\"order_id\":" + id + ",\"customer_id\":" + customerId + "}")
                .update();
        return id;
    }
```

The order and its event commit together, and nothing in the transaction talks to the broker.

```java
    static final String CLAIM_SQL = """
            update outbox set attempts = attempts + 1,
                   available_at = now() + ? * interval '1 second'
            where id in (
                select id from outbox
                where sent_at is null and dead_at is null and available_at <= now()
                order by available_at, id
                limit ?
                for update skip locked)
            returning id, topic, payload::text as payload, attempts""";
```

```java
        for (Event event : batch) {
            try {
                publisher.publish(event.id(), event.topic(), event.payload());
            } catch (Exception e) {
                if (event.attempts() >= maxAttempts) {
                    // Parked for a human; alert on count(*) where dead_at is not null.
                    jdbc.sql("update outbox set dead_at = now() where id = ?").param(event.id()).update();
                }
                continue; // otherwise the lease expires and another pass retries it
            }
            jdbc.sql("update outbox set sent_at = now() where id = ?").param(event.id()).update();
            sent++;
        }
```

```sql
create index outbox_pending on outbox (available_at, id) where sent_at is null and dead_at is null;
```

```java
        int first = jdbc.sql("insert into processed_events (event_id) values (?) on conflict do nothing")
                .param(eventId).update();
        if (first == 0) {
            return false;
        }
```

The relay runs with no transaction: the claim commits on its own, each publish waits for the broker, and each row is marked as soon as its publish returns. The tests use Testcontainers with `postgres:17`: a caller rollback leaves no outbox row, two concurrent relays publish 200 rows exactly once between them, a broker that is down moves the row to `dead_at` after three attempts, and a redelivered event changes nothing.

## Bad example

See [`examples/bad/`](examples/bad/).

```java
        // MISTAKE: publishes inside the transaction. If the commit fails or the caller rolls
        // back, consumers already saw an order that does not exist; while the broker is slow,
        // the row locks and the pooled connection wait on it.
        broker.send("order.placed", "{\"order_id\":" + id + "}");
```

```java
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                // MISTAKE: "after commit" with nothing durable behind it. A crash, a deploy or
                // a broker error between COMMIT and this send loses the event for good, and
                // nothing records that it was lost.
                broker.send("order.placed", "{\"order_id\":" + id + "}");
            }
        });
```

```java
    // MISTAKE: one transaction held open while publishing, and FOR UPDATE without SKIP LOCKED.
    // A second replica blocks on these rows until this one commits, so replicas take turns;
    // a broker error on row 50 rolls back sent_at for rows 1-49, which go out again.
    @Transactional
    public void relay() {
```

All three pass a demo with one replica and a broker that never fails, which is why they ship. The first fails on every rollback, the second on every crash at the wrong moment, the third as soon as a second pod or a broker error appears.

## Checklist

When reviewing code under this skill, confirm:

- [ ] Every event or job caused by a write is an `INSERT` into the outbox or job table inside the same transaction as the write.
- [ ] No broker, webhook, mail or HTTP call runs inside `@Transactional`, `afterCommit` or an `AFTER_COMMIT` listener unless a durable row already exists for it.
- [ ] The claim is one autocommit statement with `FOR UPDATE SKIP LOCKED`, a `LIMIT`, and a lease on `available_at`, served by a partial index of pending rows.
- [ ] No transaction is open while the relay publishes, and `sent_at` is set only after the broker acknowledged.
- [ ] Failed rows are retried by lease expiry with a capped `attempts`, then parked with `dead_at`, and an alert watches the dead rows.
- [ ] Every message carries the outbox id, and every consumer records it in the same transaction as its effect, so a duplicate changes nothing.
- [ ] Workers per replica are a fixed number below the pool size, with no thread per pending row, and they stop claiming on shutdown.
- [ ] Sent rows are deleted or partitioned away on a schedule so the table does not grow forever.

## When not to apply

- **Work that may be lost.** Evicting a cache entry or a best-effort metric after commit can run from `@TransactionalEventListener`; a crash losing a few is acceptable and a table is overhead.
- **Effects that must succeed before the write.** Authorizing a payment or reserving stock in another system cannot wait for a relay; call it before the transaction begins and compensate if the write then fails.
- **The event is born in the broker.** When no database write causes it, consume and produce with the broker's own transactions or acknowledgements; an outbox only makes a database write and a publish atomic.
- **Spring Modulith already in use for in-process listeners.** Its event publication registry gives those listeners the same durability; keep this table and relay for publishing to other systems.
- **Very high event volume.** Each event costs an insert, two updates, WAL and vacuum; at many thousands per second, measure table bloat and autovacuum lag, and consider log-based change data capture from the outbox table instead of polling.
