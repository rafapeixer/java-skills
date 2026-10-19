---
type: regex
target: { source: file, path: V14__orders_customer_created_idx.sql }
pattern: '^(?!\s*--).*CREATE\s+INDEX\s+CONCURRENTLY'
flags: im
match: contains
---
