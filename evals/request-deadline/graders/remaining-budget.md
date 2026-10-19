---
type: regex
target: { source: file, path: QuoteService.java }
pattern: '^(?!\s*//).*(System\.nanoTime\s*\(|remaining|deadline)'
flags: im
match: contains
---
