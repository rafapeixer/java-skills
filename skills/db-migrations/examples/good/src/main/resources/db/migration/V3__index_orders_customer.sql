-- Runs outside a transaction (see V3__index_orders_customer.sql.conf): CONCURRENTLY
-- cannot run in a transaction block. A failed build leaves an INVALID index behind,
-- so drop it first and the migration is safe to retry.
SET lock_timeout = '5s';
DROP INDEX CONCURRENTLY IF EXISTS orders_customer_created_idx;
CREATE INDEX CONCURRENTLY orders_customer_created_idx ON orders (customer_id, created_at);
RESET lock_timeout;
