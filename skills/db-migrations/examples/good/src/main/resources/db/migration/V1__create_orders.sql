-- Flyway runs this file in one transaction, so SET LOCAL lasts exactly as long as it.
SET LOCAL lock_timeout = '5s';
CREATE TABLE orders (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    customer_id bigint NOT NULL,
    total_cents bigint NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now()
);
