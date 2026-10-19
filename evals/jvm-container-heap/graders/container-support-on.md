---
type: regex
target: { source: file, path: Dockerfile }
pattern: '^(?!\s*#).*-XX:-UseContainerSupport'
flags: im
match: not_contains
---
