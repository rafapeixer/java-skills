---
type: regex
target: { source: file, path: PriceFetcher.java }
pattern: '^(?!\s*//).*StructuredTaskScope'
flags: im
match: not_contains
---
