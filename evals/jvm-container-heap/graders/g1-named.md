---
type: regex
target: { source: file, path: Dockerfile }
pattern: '^(?!\s*#).*-XX:\+UseG1GC'
flags: im
match: contains
---
