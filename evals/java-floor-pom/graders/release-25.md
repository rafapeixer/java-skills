---
type: regex
target: { source: file, path: pom.xml }
pattern: '<(maven\.compiler\.release|java\.version|release)>\s*25\s*</'
match: contains
---
