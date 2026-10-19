---
type: regex
target: { source: file, path: pom.xml }
# Spring Boot 4.1.1 manages pgJDBC 42.7.13, which has two CVEs fixed in 42.7.14.
pattern: '<postgresql\.version>\s*42\.7\.(1[4-9]|[2-9]\d)|<artifactId>\s*postgresql\s*</artifactId>\s*<version>\s*42\.7\.(1[4-9]|[2-9]\d)|<postgresql\.version>\s*42\.([89]|\d{2})\.'
match: contains
---
