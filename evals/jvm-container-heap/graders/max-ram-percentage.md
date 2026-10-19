---
type: regex
target: { source: file, path: Dockerfile }
pattern: '^(?!\s*#).*-XX:MaxRAMPercentage=(7[0-9]|80)(\.0+)?\b'
flags: im
match: contains
---
