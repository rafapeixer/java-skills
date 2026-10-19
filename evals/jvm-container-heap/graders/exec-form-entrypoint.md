---
type: regex
target: { source: file, path: Dockerfile }
pattern: '^\s*ENTRYPOINT\s*\['
flags: im
match: contains
---
