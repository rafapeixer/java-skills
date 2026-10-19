---
type: regex
target: { source: file, path: QuoteService.java }
pattern: '^(?!\s*//).*(\.join\s*\(\s*\)|[Ff]uture\w*\.get\s*\(\s*\))'
flags: im
match: not_contains
---
