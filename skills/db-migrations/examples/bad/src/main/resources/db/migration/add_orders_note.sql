-- MISTAKE: no V<version>__ prefix. Flyway does not treat this file as a migration and
-- skips it without an error, so the column silently never exists.
ALTER TABLE orders ADD COLUMN note text;
