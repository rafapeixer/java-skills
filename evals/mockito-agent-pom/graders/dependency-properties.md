---
type: regex
target: { source: file, path: pom.xml }
pattern: '<artifactId>\s*maven-dependency-plugin\s*</artifactId>[\s\S]*<goal>\s*properties\s*</goal>'
match: contains
---
