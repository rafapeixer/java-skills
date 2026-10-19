---
type: regex
target: { source: file, path: pom.xml }
pattern: '<argLine>[^<]*-javaagent:\$\{org\.mockito:mockito-core:jar\}'
match: contains
---
