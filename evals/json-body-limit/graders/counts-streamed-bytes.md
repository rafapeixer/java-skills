---
type: regex
target: { source: file, path: BodyLimits.java }
# Content-Length alone is not enough: chunked requests have none.
pattern: '(ServletInputStream|HttpServletRequestWrapper|getInputStream\s*\()'
match: contains
---
