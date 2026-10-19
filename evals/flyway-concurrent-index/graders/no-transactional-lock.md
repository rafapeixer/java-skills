---
type: regex
target: { source: file, path: flyway.toml }
# TOML: [flyway.postgresql] transactional.lock, or the same key as a [flyway.postgresql.transactional] table.
# With Flyway's default transactional lock, CREATE INDEX CONCURRENTLY waits on Flyway's own lock transaction.
pattern: 'transactional\.lock\s*=\s*false|\[flyway\.postgresql\.transactional\][^\[]*?\block\s*=\s*false'
flags: i
match: contains
---
