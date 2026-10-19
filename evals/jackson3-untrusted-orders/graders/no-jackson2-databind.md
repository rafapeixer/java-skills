---
type: regex
target: { source: file, path: OrderJson.java }
pattern: '^(?!\s*(//|#)).*com\.fasterxml\.jackson\.(databind|core)\.'
flags: im
match: not_contains
---
