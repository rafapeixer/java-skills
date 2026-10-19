---
type: regex
target: { source: file, path: Dockerfile }
pattern: '^\s*USER\s+(?!root\b|0\b)\S+'
flags: im
match: contains
---
