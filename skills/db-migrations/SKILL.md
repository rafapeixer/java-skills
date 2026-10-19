---
name: db-migrations
description: Use when adding or reviewing PostgreSQL schema migrations in a Java service, setting up Flyway (plain or Spring Boot spring.flyway), deciding where migrations run during a deploy, changing a table live traffic uses, or seeing a Flyway checksum mismatch or a migration that hangs. Key rule: Flyway 13 (12.4 under Spring Boot 4.1.1) with flyway-core plus flyway-database-postgresql, with V<n>__name.sql files and validateMigrationNaming; run migrate once per deploy as a separate Job before the rollout (spring.flyway.enabled=false in the service), never on every replica's startup; never edit an applied migration, add a new version; expand/contract across releases; SET LOCAL lock_timeout in every transactional file; CREATE INDEX CONCURRENTLY alone in a file with executeInTransaction=false, DROP INDEX CONCURRENTLY IF EXISTS first, and postgresql.transactional.lock=false or the build waits on Flyway's own lock.
metadata:
  verified: 2026-10-18
---

# Database migrations with Flyway

## The decision

Every schema change in a Java service needs a tool that applies it, a place in the deploy where it runs, and a shape that keeps the running service working while it happens. The failures are rarely in the SQL itself: ten replicas starting the same migration while their startup probes run out, a hotfix edit to an applied file that makes every environment disagree about the schema, an `ALTER TABLE` waiting for a lock while every query on the table queues behind it, a rename that breaks the pods of the previous release that are still serving, an index build that waits forever on the migration tool's own transaction. This skill fixes the tool (Flyway), where migrations run (a separate step, before the rollout) and the Flyway-specific shape of each file. Which form of each `ALTER` is safe on a live table (`NOT VALID` constraints, `NOT NULL` through a `CHECK`, constant defaults) is SQL, not Java, and lives in db-skills [`pg-online-migrations`](https://github.com/rafapeixer/db-skills/blob/main/skills/pg-online-migrations/SKILL.md); this skill does not repeat it.

## The rule

1. Write migrations as Flyway SQL files (13.10 standalone, 12.4 as managed by Spring Boot 4.1.1) named `V<version>__<description>.sql` in `db/migration`, with `flyway-core` and `flyway-database-postgresql` on the classpath and `validateMigrationNaming(true)`.
2. Run `migrate` once per deploy from a separate command (a Kubernetes Job, a Helm pre-upgrade hook or a pipeline step) that finishes before the rollout, and in a Spring Boot service set `spring.flyway.enabled=false`.
3. Never edit, rename or delete a migration that has run anywhere shared; fix it with a new version, and keep `validateOnMigrate` on.
4. Keep every release working against the schema before and after its migrations: expand in one release, backfill in batches, contract in a later release.
5. Start every transactional migration with `SET LOCAL lock_timeout = '5s'`, and let the job fail and retry when it fires.
6. Build indexes on existing tables with `CREATE INDEX CONCURRENTLY` alone in a file whose `.sql.conf` says `executeInTransaction=false`, after `DROP INDEX CONCURRENTLY IF EXISTS`, with `SET`/`RESET lock_timeout` around it and `postgresql.transactional.lock=false`.
7. Run the migrations against a real PostgreSQL in CI and check the file conventions in a test.

## Why

**Rule 1.** Flyway runs versioned SQL files in order and records each one, with a checksum, in `flyway_schema_history`. The current release is 13.10.3 ([releases](https://github.com/flyway/flyway/releases)). PostgreSQL support is a separate dependency, `org.flywaydb:flyway-database-postgresql`, that "you must add" ([PostgreSQL database](https://documentation.red-gate.com/fd/postgresql-database-277579325.html)); Spring Boot says the same for its `spring-boot-starter-flyway` ([Boot docs](https://docs.spring.io/spring-boot/how-to/data-initialization.html)). Spring Boot 4.1.1 manages Flyway 12.4.0 and its auto-configuration is built against it; keep that version in a Boot service unless the override passes your tests, and use 13 in a standalone migration command like the good example's. `validateMigrationNaming` defaults to false, and then "files with invalid names are ignored and Flyway continues normally": `add_index.sql` or `V5-add-index.sql` is not an error, it is a migration that never runs. With it on, Flyway fails and lists the file; the good example's test proves both behaviors.

**Rule 2.** Spring Boot calls `Flyway.migrate()` during startup when Flyway is on the classpath. With one replica that looks harmless. With ten, every pod runs it: Flyway's lock makes nine of them wait while the first works, a migration longer than the startup probe gets the pod killed mid-run, the next pod starts it again, and the rollout loops. The service also needs DDL privileges it should not have. A separate Job runs once, with its own timeout, credentials and logs, and the rollout starts only when it succeeds; a failure stops the deploy instead of half the pods. The same artifact can be the Job: the good example's `Migrate.main` reads `MIGRATE_JDBC_URL`, or a Boot app run with `spring.flyway.enabled=true` and `spring.main.web-application-type=none` in the Job's profile. Point it at PostgreSQL directly or through a session pooler, never through PgBouncer in transaction mode, where a session `SET` or a session advisory lock may land on another server connection.

**Rule 3.** Flyway compares the checksum of every applied file with the one on disk. Edit an applied file and databases that already ran it never get the change while new ones do, so environments drift; `validate` fails until someone runs `repair` or turns validation off, and turning it off also hides the next edit and a missing file. A new version that corrects the old one is the only change every database applies the same way.

**Rule 4.** During a rolling update the pods of the previous release keep serving after the Job has run, and a rollback puts them back. A one-step `RENAME COLUMN`, `DROP COLUMN` or type change breaks every query of the old code the moment it commits. Add the new shape (a nullable column is catalog-only), write both in the next release, backfill in small batches outside the migration, switch reads, and drop the old shape a release later. db-skills `pg-online-migrations` has the safe form of each `ALTER`. The cost is real: one logical change becomes two or three releases, and the code carries dual writes for a while.

**Rule 5.** Most `ALTER TABLE` forms take an `ACCESS EXCLUSIVE` lock, which conflicts with the `ACCESS SHARE` of a plain `SELECT`. Behind one long report the `ALTER` waits, and every query that arrives after it queues behind the waiting `ALTER`: a millisecond change becomes an outage as long as the slowest open transaction. `lock_timeout` defaults to 0, wait forever. Flyway wraps each migration in its own transaction ([transaction handling](https://documentation.red-gate.com/fd/migration-transaction-handling-273973399.html)), so `SET LOCAL` covers exactly that file and leaves nothing behind on the connection. The good example's test holds `ACCESS SHARE` from an open transaction: V2 fails with `55P03` after five seconds, PostgreSQL rolls the DDL back with the transaction, and the next run applies V2 and V3. Keep `lock_timeout` below any `statement_timeout`, or the statement timeout fires first.

**Rule 6.** A plain `CREATE INDEX` blocks writes for the whole build. `CONCURRENTLY` does not, but it cannot run in a transaction block, so the file needs the script configuration `executeInTransaction=false` in a `.sql.conf` file next to it ([script configuration](https://documentation.red-gate.com/fd/script-configuration-277578847.html)); `SET LOCAL` does nothing there, so use `SET` and `RESET`. A concurrent build also waits for every transaction that is open when it starts, and Flyway's default lock on PostgreSQL is a transactional advisory lock: the docs say it "can cause problems with statements such as `CREATE INDEX CONCURRENTLY`" and that `postgresql.transactional.lock` "should be set to `false`" for them ([setting](https://documentation.red-gate.com/fd/flyway-postgresql-transactional-lock-setting-277579114.html)). Write it exactly as the docs spell it: `transactional.lock = false` under `[flyway.postgresql]` in `flyway.toml`, `flyway.postgresql.transactional.lock=false` in a `.conf` file, `FLYWAY_POSTGRESQL_TRANSACTIONAL_LOCK=false` in the environment, or `setTransactionalLock(false)` through the Java API; there is no `transactionalLock` key in the TOML file. Running the good example with the default lock shows why: V3 waits on Flyway's own lock transaction and fails with `55P03` after the five-second `lock_timeout`; without that timeout it would hang. A failed concurrent build leaves an `INVALID` index that writes still maintain, and Flyway cannot roll a non-transactional file back, so start with `DROP INDEX CONCURRENTLY IF EXISTS` to make the retry safe, and put one index per file. Do not use `CREATE INDEX CONCURRENTLY IF NOT EXISTS` for this; it skips an invalid index with that name.

**Rule 7.** A migration that only ran on an empty laptop database has not been tested. Testcontainers starts the PostgreSQL major you run in production, `migrate` applies every file, and `validateWithResult` and a second `migrate` that executes nothing confirm the history is consistent. Checking the files themselves (naming, `lock_timeout`, `.sql.conf` next to every `CONCURRENTLY`) turns rules 1, 5 and 6 into CI failures instead of review comments.

## Good example

See [`examples/good/`](examples/good/).

```java
        FluentConfiguration config = Flyway.configure()
                .dataSource(url, user, password)
                .locations("classpath:db/migration")
                // Fail on files Flyway would otherwise skip, such as add_index.sql.
                .validateMigrationNaming(true);
        // Session-level advisory lock. Flyway's docs say the default transactional lock
        // causes problems with CREATE INDEX CONCURRENTLY.
        config.getConfigurationExtension(PostgreSQLConfigurationExtension.class).setTransactionalLock(false);
        return config.load();
```

```sql
-- Expand: a nullable column is a catalog-only change, and the release still serving
-- ignores it. Reads of the column ship in the next release, never in this one.
SET LOCAL lock_timeout = '5s';
ALTER TABLE orders ADD COLUMN note text;
```

`V3__index_orders_customer.sql`, with `V3__index_orders_customer.sql.conf` containing `executeInTransaction=false`:

```sql
SET lock_timeout = '5s';
DROP INDEX CONCURRENTLY IF EXISTS orders_customer_created_idx;
CREATE INDEX CONCURRENTLY orders_customer_created_idx ON orders (customer_id, created_at);
RESET lock_timeout;
```

`Migrate` is the Job's entry point. `MigrationsTest` checks the file conventions, shows that a misnamed file fails with `validateMigrationNaming` and is silently skipped without it, and against `postgres:17` in Testcontainers holds a lock so V2 fails fast, retries to version 3, validates, and checks the index is valid.

## Bad example

See [`examples/bad/`](examples/bad/).

```java
        // MISTAKE: every replica migrates on startup with the service's own credentials.
        // Ten pods wait on one lock, a slow migration fails their startup probes, and the
        // rollout loops while the old pods already serve against the new schema.
        Flyway.configure()
                .dataSource(System.getenv("JDBC_URL"), System.getenv("DB_USER"), System.getenv("DB_PASSWORD"))
                // MISTAKE: validation turned off to get past the checksum error from
                // editing V1, which hides every future edit and missing file as well.
                .validateOnMigrate(false)
```

```sql
-- MISTAKE: run with Flyway's default transactional lock and no lock_timeout. The
-- concurrent build waits for every open transaction, including the one Flyway holds
-- for its own lock, so the migration hangs and the deploy never finishes.
CREATE INDEX CONCURRENTLY orders_client_idx ON orders (client_id);
```

The other files hold the rest: V1 was edited after it was applied, V2 renames a column in one step with no `lock_timeout`, and `add_orders_note.sql` has no version prefix and never runs. All of it works on a laptop with one replica and an empty table, which is why it reaches production.

## Checklist

When reviewing code under this skill, confirm:

- [ ] Flyway (13, or the version Spring Boot manages) with `flyway-database-postgresql` is on the classpath, and `validateMigrationNaming` is true.
- [ ] Every migration file is named `V<version>__<description>.sql` and lives in the configured location.
- [ ] Migrations run from a separate Job or pipeline step before the rollout, and the service has `spring.flyway.enabled=false` (or never calls `migrate`).
- [ ] No applied migration was edited, renamed or deleted in this change, and `validateOnMigrate` is not turned off.
- [ ] No migration renames, drops or retypes something the currently deployed release still uses.
- [ ] Every transactional file starts with `SET LOCAL lock_timeout`.
- [ ] Every `CREATE INDEX` on an existing table is `CONCURRENTLY`, alone in a file with `executeInTransaction=false`, after `DROP INDEX CONCURRENTLY IF EXISTS`, between `SET` and `RESET lock_timeout`.
- [ ] `postgresql.transactional.lock` is false wherever a concurrent index build runs.
- [ ] CI applies all migrations to a real PostgreSQL and checks the file conventions.

## When not to apply

- **One process owns the database.** A single-instance service with no rolling update can let Spring Boot migrate on startup, which happens before it serves traffic; there is nothing to race and no old version still serving. Keep `lock_timeout` and the file rules.
- **Tables nobody uses yet.** A table created in the same release has no traffic and no old code: create it and its indexes in one transactional file without `CONCURRENTLY` or expand/contract. `lock_timeout` still applies, because the file may touch existing tables through foreign keys.
- **A planned maintenance window.** With the service stopped and downtime agreed, a one-step rename or a table rewrite is acceptable; say so in the file, so nobody copies it as a pattern.
- **Local development and tests.** Applying migrations in test setup (or `clean` on a throwaway database) is the right way to get a real schema; the separate Job rule is about production rollouts.
- **A team already on Liquibase.** Do not switch tools for this skill. Rules 2 to 7 apply to any tool: how migrations run, lock and change tables is what causes outages.
- **Large backfills.** Updating millions of rows belongs in a batched, resumable job outside the migration, not in a file the deploy waits on; a Java-based Flyway migration is fine for small data fixes.
