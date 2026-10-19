---
type: regex
target: { source: file, path: OrderJson.java }
pattern: '(enable\s*\([^)]*FAIL_ON_UNKNOWN_PROPERTIES|FAIL_ON_UNKNOWN_PROPERTIES\s*,\s*true)'
match: contains
---
