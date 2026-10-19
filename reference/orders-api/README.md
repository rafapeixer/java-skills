# orders-api: reference service

A small orders service (Spring Boot 4, JDK 25, PostgreSQL, one outbound HTTP dependency) that an agent wrote with the java-skills installed, from the same spec as the benchmark. It was then load-tested in a 2 vCPU / 1 GiB container: about 5,000 req/s warm, 1.6% errors at a 4,000 req/s overload, 0% once the load dropped. See [BENCHMARKS.md](../../BENCHMARKS.md) for the method and the numbers.

The code here is the benchmarked code. The only additions are the tests, this README, and one test seam (`AdmissionFilter.permits` is package-private so a test can fill it).

## API

| Request | Answer |
|---|---|
| `POST /orders` `{"customer_id":42,"items":[{"sku":"A1","qty":2}]}` | `201 {"id":123}`; `400` on invalid input (1 to 100 items, `qty` ≥ 1, no duplicate SKUs, `customer_id` > 0) |
| `GET /orders/{id}` | `200` with the order and its items; `404` |
| `GET /customers/{id}/orders?limit=20&cursor=…` | `200 {"orders":[…],"next_cursor":"…"}`, newest first, `limit` 1 to 100 (default 20), `next_cursor` empty on the last page |
| `GET /orders/{id}/quote` | `200 {"order_id":123,"total_cents":4500}`; `404` unknown order; `422` SKU unknown to pricing; `503` pricing failing or slower than the budget |
| `GET /healthz` | `200`; `503` while draining on shutdown |

Every error is `{"error":"<message>"}`. Overload answers `503` with `Retry-After: 1`; a body over 64 KiB answers `413`.

## Run it

The schema must exist; the service never creates or migrates it. It is in [src/test/resources/schema.sql](src/test/resources/schema.sql).

```yaml
# compose.yaml
services:
  postgres:
    image: postgres:17
    environment:
      POSTGRES_PASSWORD: orders
      POSTGRES_DB: orders
    volumes:
      - ./src/test/resources/schema.sql:/docker-entrypoint-initdb.d/schema.sql:ro
  orders-api:
    build: .
    depends_on: [postgres]
    ports: ["8080:8080"]
    stop_grace_period: 30s          # 5 s drain + 20 s graceful shutdown
    deploy:
      resources:
        limits: {cpus: "2", memory: 1g}  # the heap is 75% of this
    environment:
      PORT: "8080"
      DATABASE_URL: postgres://postgres:orders@postgres:5432/orders
      PRICING_URL: http://pricing:8080  # your pricing service: GET /prices/{sku}
```

```bash
docker compose up --build
curl localhost:8080/healthz
```

| Variable | Meaning |
|---|---|
| `PORT` | HTTP port, default `8080` |
| `DATABASE_URL` | `postgres://user:pass@host:port/db?params`, or a `jdbc:postgresql:` URL as-is. Required. |
| `PRICING_URL` | Base URL of the pricing service. Required. |
| `SHUTDOWN_DRAIN` | How long `/healthz` answers 503 before Tomcat stops, default `5s` |

## The configuration that matters

Each value comes from a skill, and each is arithmetic on the deployment in the spec (8 replicas, 80 Postgres connections for the service, t4g.micro containers), not a guess.

| Setting | Where | Skill |
|---|---|---|
| Pool of 8 connections: 80 / (8 replicas + 2 surging during a rolling deploy), fixed size, 500 ms borrow timeout mapped to 503 | `DatabaseConfig` | [postgres-hikari-pool](../../skills/postgres-hikari-pool/SKILL.md) |
| At most 256 requests in flight, the rest get an immediate 503 + `Retry-After` (Little's law: ~1,000 req/s × ~255 ms); `/healthz` is never shed | `http/AdmissionFilter` | [overload-protection](../../skills/overload-protection/SKILL.md), [http-server](../../skills/http-server/SKILL.md) |
| Virtual threads, `accept-count=1024`, 5 s connection timeout, 65 s keep-alive (above the load balancer's 60 s), 64 KiB body limit, graceful shutdown with a health-check drain | `application.properties`, `http/BodyLimitFilter`, `ShutdownDrain` | [http-server](../../skills/http-server/SKILL.md) |
| One statement per request, in autocommit: the order and its items are inserted by one CTE, read with their items in one query, paged by keyset `(created_at, id)`, never `OFFSET` | `order/OrderRepository` | [sql-and-transactions](../../skills/sql-and-transactions/SKILL.md) |
| One shared `HttpClient` on a virtual-thread executor, 500 ms connect, 128 calls in flight, one retry with jittered backoff on connect errors and 429/502/503/504, 4 KiB response limit | `order/PricingClient` | [outbound-http-client](../../skills/outbound-http-client/SKILL.md) |
| A 1.5 s budget per quote; each pricing call gets min(800 ms, what is left); 5 s `statement_timeout`, 10 s `idle_in_transaction_session_timeout`, 15 s socket timeout | `order/QuoteService`, `order/Deadline`, `DatabaseConfig` | [timeouts-and-deadlines](../../skills/timeouts-and-deadlines/SKILL.md) |
| Heap fixed at 75% of the container limit, G1 named explicitly (the JDK picks Serial below 1792 MB), exec-form entrypoint so java gets SIGTERM | `Dockerfile` | [container-runtime-tuning](../../skills/container-runtime-tuning/SKILL.md) |

## Test it

```bash
mvn verify        # needs Docker: Testcontainers starts postgres:17
```

- Unit tests (`*Test`, surefire): cursor encoding, `DATABASE_URL` parsing. They need no Docker and also run inside `docker build`.
- Integration tests (`*IT`, failsafe): the whole service on a random port against PostgreSQL 17 with `schema.sql`, and a stub pricing server (`PricingStub`, the JDK's `HttpServer`) with SKUs that are slow, broken or fail once. They cover every status in the API table, cursor paging to the last page, the limit bounds, the 503 within the quote budget, the 413 for declared and chunked bodies, and a full admission filter (its permits drained) answering 503 while `/healthz` still answers 200.

Without Maven installed, run them in the image CI uses:

```bash
docker run --rm -v "$PWD":/work -w /work \
  -v /var/run/docker.sock:/var/run/docker.sock \
  --add-host=host.docker.internal:host-gateway \
  -e TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal \
  maven:3.10-eclipse-temurin-25 mvn -B -ntp verify
```

## Adapt it

1. Rename the package (`com.example.orders`) and the `groupId`.
2. Redo the budget arithmetic for your deployment before you change any number. The pool is the connections the database allows for this service divided by (replicas + surge instances); the admission cap and the pricing cap are peak rate per instance × (typical latency + the stall you tolerate); the heap is a share of the container limit. Each constant's comment shows its formula.
3. Re-measure under load, in the container size you deploy to, before you raise or lower a limit: the benchmarked numbers hold only for the configuration that was measured.
4. Keep `mvn verify` green: the integration tests pin the API contract and the protections, so a change that breaks either shows up there.
