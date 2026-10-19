-- MISTAKE: a one-step rename. The release still serving queries customer_id and
-- fails the moment this commits; a rollback of the code cannot fix it.
-- MISTAKE: no lock_timeout. Behind one long report this ALTER waits for ACCESS
-- EXCLUSIVE forever, and every query on orders queues behind it.
ALTER TABLE orders RENAME COLUMN customer_id TO client_id;
