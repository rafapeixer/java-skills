---
type: regex
target: { source: file, path: Dockerfile }
pattern: '^(?!\s*#).*-Xmx'
flags: im
match: not_contains
---
