-- MISTAKE: edited after it was applied in production (total_cents added here).
-- Databases that already ran V1 never get the column, new ones do, and the checksum
-- no longer matches, so validation fails on every migrate until someone disables it.
CREATE TABLE orders (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    customer_id bigint NOT NULL,
    total_cents bigint NOT NULL DEFAULT 0
);
