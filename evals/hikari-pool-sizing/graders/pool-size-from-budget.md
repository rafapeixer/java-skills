---
type: regex
target: { source: file, path: DatabaseConfig.java }
pattern: 'setMaximumPoolSize\(\s*25\s*\)|\b200\s*/\s*\(?\s*(8|6\s*\+\s*2)\b|=\s*25\s*;'
match: contains
---
