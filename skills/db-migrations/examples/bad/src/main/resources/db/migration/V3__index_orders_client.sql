-- MISTAKE: run with Flyway's default transactional lock and no lock_timeout. The
-- concurrent build waits for every open transaction, including the one Flyway holds
-- for its own lock, so the migration hangs and the deploy never finishes.
CREATE INDEX CONCURRENTLY orders_client_idx ON orders (client_id);
