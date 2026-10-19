---
type: regex
target: { source: file, path: OrderEvents.java }
pattern: '^(?!\s*//).*(afterCommit|@TransactionalEventListener|TransactionSynchronization)'
flags: im
match: not_contains
---
