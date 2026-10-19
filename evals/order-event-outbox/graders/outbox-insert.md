---
type: regex
target: { source: file, path: OrderEvents.java }
pattern: 'insert\s+into\s+\w*outbox'
flags: i
match: contains
---
