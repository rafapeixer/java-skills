---
type: regex
target: { source: file, path: pom.xml }
pattern: '<artifactId>\s*junit-(bom|jupiter)\s*</artifactId>\s*<version>\s*6\.|<junit[\w.-]*\.version>\s*6\.'
match: contains
---
