---
type: regex
target: { source: file, path: pom.xml }
pattern: '^(?!\s*//).*<maven\.compiler\.(source|target)>'
flags: im
match: not_contains
---
