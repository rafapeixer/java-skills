-- Expand: a nullable column is a catalog-only change, and the release still serving
-- ignores it. Reads of the column ship in the next release, never in this one.
SET LOCAL lock_timeout = '5s';
ALTER TABLE orders ADD COLUMN note text;
