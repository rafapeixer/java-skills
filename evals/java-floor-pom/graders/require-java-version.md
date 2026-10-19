---
type: regex
target: { source: file, path: pom.xml }
pattern: '<requireJavaVersion>[\s\S]*?<version>\s*\[?25'
match: contains
---
