---
type: regex
target: { source: file, path: DatabaseConfig.java }
pattern: '^(?!\s*(//|#|--)).*setMaximumPoolSize\([^;]*(availableProcessors|[Tt]hread)'
flags: im
match: not_contains
---
