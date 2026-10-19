---
type: regex
target: { source: file, path: DatabaseConfig.java }
pattern: 'setConnectionTimeout\(\s*((?:[2-9]\d{2}|[1-4]_?\d{3})L?|[^;]*(ofMillis|ofSeconds|toMillis)\(\s*((?:[2-9]\d{2}|[1-4]_?\d{3})|[1-5])L?\s*\)[^;]*)\)'
match: contains
---
