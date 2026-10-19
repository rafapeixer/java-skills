---
type: regex
target: { source: file, path: pom.xml }
pattern: '^(?!\s*<!--).*EnableDynamicAgentLoading'
flags: im
match: not_contains
---
