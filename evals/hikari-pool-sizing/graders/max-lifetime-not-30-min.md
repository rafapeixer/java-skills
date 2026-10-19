---
type: regex
target: { source: file, path: DatabaseConfig.java }
pattern: '^(?!\s*(//|#|--)).*setMaxLifetime\(\s*(0|1_?800_?000L?|Duration\.ofMinutes\(\s*30\s*\)\.toMillis\(\)|TimeUnit\.MINUTES\.toMillis\(\s*30\s*\))\s*\)'
flags: im
match: not_contains
---
