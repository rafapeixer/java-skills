---
type: regex
target: { source: file, path: OrderJson.java }
pattern: 'StreamReadConstraints\.builder\s*\(\s*\)[\s\S]*maxDocumentLength\s*\('
match: contains
---
