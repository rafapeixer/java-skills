---
type: regex
target: { source: file, path: DatabaseConfig.java }
pattern: '^(?!\s*(//|#|--)).*setMinimumIdle\('
flags: im
match: not_contains
---
