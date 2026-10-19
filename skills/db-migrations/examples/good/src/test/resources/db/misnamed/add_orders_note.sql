-- No V<version>__ prefix: without validateMigrationNaming Flyway skips this file.
ALTER TABLE orders ADD COLUMN note text;
