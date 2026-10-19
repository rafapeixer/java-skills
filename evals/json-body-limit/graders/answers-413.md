---
type: regex
target: { source: file, path: BodyLimits.java }
pattern: '413|PAYLOAD_TOO_LARGE|CONTENT_TOO_LARGE|SC_REQUEST_ENTITY_TOO_LARGE'
match: contains
---
