---
type: regex
target: { source: file, path: BodyLimits.java }
# Tomcat's maxPostSize and max-swallow-size do not apply to JSON bodies; the limit must be enforced in code.
pattern: '(OncePerRequestFilter|implements\s+(jakarta\.servlet\.)?Filter|FilterRegistrationBean)'
match: contains
---
