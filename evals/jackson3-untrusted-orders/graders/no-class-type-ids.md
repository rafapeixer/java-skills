---
type: regex
target: { source: file, path: OrderJson.java }
pattern: '^(?!\s*(//|#)).*(activateDefaultTyping|Id\.CLASS|Id\.MINIMAL_CLASS|allowIfBaseType\s*\(\s*Object\.class)'
flags: im
match: not_contains
---
