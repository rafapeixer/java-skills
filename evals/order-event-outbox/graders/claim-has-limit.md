---
type: regex
target: { source: file, path: OrderEvents.java }
pattern: '^(?!\s*//).*\blimit\b\s+(\?|:\w+|\d+)'
flags: im
match: contains
---
