---
name: secure-defaults
description: Use when writing or reviewing Java code that generates tokens, reset links or API keys, compares secrets, hashes or checks passwords, deserializes data, parses XML from outside, configures TLS or an SSLContext (private CA, self-signed certificate, PKIX path building failed), or reads secrets and credentials. Key rule: one shared new SecureRandom() with 32 bytes encoded by Base64 URL (no padding) or HexFormat, never Random, Math.random or getInstanceStrong() on a request path; store a SHA-256 of tokens and compare with MessageDigest.isEqual; passwords through Spring Security's DelegatingPasswordEncoder ({bcrypt}, ~1 s per check), never a plain digest; no Java serialization of untrusted bytes, or an ObjectInputFilter allow-list ending in !*; XML with disallow-doctype-decl; never a trust-all TrustManager or HostnameVerifier, trust a private CA through TrustManagerFactory; never pin protocols or named groups (JDK 27 offers X25519MLKEM768 first); secrets from the environment, never in source.
metadata:
  verified: 2026-10-18
---

# Secure defaults

## The decision

The JDK and Spring Security already ship the secure answer for the everyday security work in a backend: a CSPRNG, a constant-time comparison, adaptive password hashing, deserialization filters, an XML parser that can refuse a DOCTYPE, and TLS that verifies the peer and, from JDK 27, negotiates post-quantum key exchange without configuration. The decision is whether to use those defaults or override them with code copied from older answers: a `java.util.Random` token, `equals` on a secret, `SHA-256(password)`, an unfiltered `ObjectInputStream`, a trust-all `TrustManager` to get past a private CA, a pinned protocol list. Each override compiles, passes the tests and silently removes a protection. This skill takes one position: use the platform's secure default, never switch it off, never roll your own. Values in SQL go in as parameters, which is [`sql-and-transactions`](../sql-and-transactions/SKILL.md); request size and time limits are [`http-server`](../http-server/SKILL.md).

## The rule

1. Generate tokens, reset links and API keys from one shared `new SecureRandom()`, 32 bytes encoded with `Base64.getUrlEncoder().withoutPadding()` or `HexFormat`, never from `Random`, `ThreadLocalRandom`, `Math.random()` or `SecureRandom.getInstanceStrong()` on a request path.
2. Store only a SHA-256 of a token and compare digests with `MessageDigest.isEqual`, never with `equals`.
3. Hash passwords with Spring Security's `PasswordEncoderFactories.createDelegatingPasswordEncoder()` (BCrypt, `{bcrypt}` prefix), tuned to about one second per check, and never with a plain `MessageDigest`.
4. Never deserialize untrusted bytes with Java serialization; when legacy data forces `ObjectInputStream`, set an `ObjectInputFilter` allow-list with limits that ends in `!*`, plus a process-wide `jdk.serialFilter`.
5. Parse XML from outside the process with `disallow-doctype-decl` set to true, `FEATURE_SECURE_PROCESSING` on, and `ACCESS_EXTERNAL_DTD` and `ACCESS_EXTERNAL_SCHEMA` set to `""`.
6. Never install a trust-all `TrustManager` or a `HostnameVerifier` that returns true; trust a private CA by loading it into a `KeyStore` for `TrustManagerFactory`, and set endpoint identification to `HTTPS` on raw `SSLSocket`s.
7. Leave TLS protocols, named groups and cipher suites at the JDK defaults: no `setProtocols`, `setNamedGroups`, `setCipherSuites` or `jdk.tls.namedGroups`.
8. Read secrets from the environment or a secret manager at startup and fail when one is missing; never put them in source, a committed properties file or a default value.

## Why

**Rule 1.** The `Random` javadoc says it uses a 48-bit seed "modified using a linear congruential formula" and that instances "are not cryptographically secure", pointing to `SecureRandom` ([Random](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/util/Random.html)); an attacker who sees a few reset tokens can compute the next ones. `ThreadLocalRandom` and `Math.random()` are no better. `SecureRandom` is safe for concurrent use ([SecureRandom](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/security/SecureRandom.html)), so one static instance serves the whole service. On Linux the default is `NativePRNG`, whose `nextBytes()` reads `/dev/urandom` ([Oracle providers](https://docs.oracle.com/en/java/javase/25/security/oracle-providers.html)). `getInstanceStrong()` picks from `securerandom.strongAlgorithms`, which in the JDK 25 and 27 `java.security` file is `NativePRNGBlocking:SUN,DRBG:SUN`, and `NativePRNGBlocking` reads `/dev/random`; the javadoc warns that `nextBytes` "may block as entropy is being gathered". Keep it for long-lived keys generated offline. 32 bytes is 256 bits, 43 characters in URL-safe Base64 without padding, so it fits in a link and a header with no escaping; `HexFormat` (JDK 17) gives 64 hex characters when the consumer wants hex.

**Rule 2.** `String.equals` returns at the first differing character, so response time tells an attacker how much of a guess is right. `MessageDigest.isEqual` examines every byte, and its implementation note says the time "depends only on the length of `digesta`", not on the contents ([MessageDigest.isEqual](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/security/MessageDigest.html#isEqual(byte%5B%5D,byte%5B%5D))). Hashing both sides first makes both inputs 32 bytes, and storing only the hash means a leaked table or log line grants nothing. A single SHA-256 is enough here because the token already has 256 random bits; it is not enough for passwords.

**Rule 3.** Passwords are low-entropy, so a fast hash lets an attacker with a leaked table try billions of guesses per second on a GPU, and an unsalted one shows which users share a password. Spring Security's [password storage](https://docs.spring.io/spring-security/reference/features/authentication/password-storage.html) docs (7.1.1) recommend an adaptive one-way function and that "the 'work factor' be tuned to take about one second to verify a password on your system". `createDelegatingPasswordEncoder()` writes `{bcrypt}` hashes (`BCryptPasswordEncoder`, default strength 10) and reads every id it knows, so you can raise the cost or change the algorithm later and re-hash at the next login when `upgradeEncoding` returns true. `Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8()` also works but "requires BouncyCastle"; `Pbkdf2PasswordEncoder` is the documented choice "when FIPS certification is required". Strength 10 is a starting point: measure on production hardware and raise it until a check takes close to a second, keeping in mind that login throughput then costs CPU (four cores verify roughly four logins a second at one second each), so rate-limit login.

**Rule 4.** `ObjectInputStream.readObject` instantiates whatever classes the stream names, and a gadget chain from any library on the classpath runs code during that call. Prefer JSON or another format that maps to known types. When legacy data forces Java serialization, a filter is the only defense: JEP 290 added pattern filters, `jdk.serialFilter` and `ObjectInputFilter`, and [JEP 415](https://openjdk.org/jeps/415) (JDK 17) added per-context filter factories. The [pattern syntax](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/io/ObjectInputFilter.Config.html) checks limits first (`maxdepth`, `maxrefs`, `maxbytes`, `maxarray`), then patterns left to right, and a class no pattern matches is `UNDECIDED`, so an allow-list needs a final `!*`. JEP 415 says to create "an allow-list of classes, and rejecting everything else"; a reject-list of known gadgets misses the next one. Setting `jdk.serialFilter` with `System.setProperty` does not install it; pass it with `-D` or in the `java.security` file.

**Rule 5.** A DOCTYPE can declare an external entity (`<!ENTITY x SYSTEM "file:///etc/passwd">`) that the parser reads and puts in the document, or nested entities that expand to gigabytes. Oracle's [JAXP security guide](https://docs.oracle.com/en/java/javase/25/security/java-api-xml-processing-jaxp-security-guide.html) notes that secure processing is on by default yet "the default configuration does not restrict external entity resolution". It recommends setting the feature explicitly, setting the external access properties to an empty string to "deny any external connections", and `disallow-doctype-decl` for SAX and DOM, which makes any DOCTYPE a fatal error. For StAX set `XMLInputFactory.SUPPORT_DTD` to false. `-Djdk.xml.dtd.support=deny` does the same for every parser in the JVM, and `<java_home>/conf/jaxp-strict.properties.template` is the JDK's ready-made strict configuration to test against.

**Rule 6.** A trust-all `X509TrustManager` or a `HostnameVerifier` that returns true makes TLS encryption without authentication: anyone on the network path presents any certificate and reads the traffic. It usually appears to get past "PKIX path building failed" with a private or self-signed CA; the fix is to load that CA into a `KeyStore` and initialize a `TrustManagerFactory` with it, which keeps chain, validity and name checks. `HttpClient` and `HttpsURLConnection` check the host name, but a raw `SSLSocket` checks it only when `SSLParameters.setEndpointIdentificationAlgorithm("HTTPS")` is set. The good example's test does both handshakes: a certificate for `localhost` succeeds over TLS 1.3, one for `billing.internal` from the same trusted store fails with `SSLHandshakeException`.

**Rule 7.** On JDK 25 and 27 the default protocols are TLSv1.3 and TLSv1.2, the order the test asserts. [JEP 527](https://openjdk.org/jeps/527) (JDK 27) puts the hybrid `X25519MLKEM768` at the front of the default named groups, so clients offer it along with `x25519` and recorded traffic is protected against a future quantum computer; the ML-KEM primitive itself arrived in JEP 496 (JDK 24). `setNamedGroups` and `jdk.tls.namedGroups` replace that list, so `{"x25519", "secp256r1"}` copied from an older answer turns post-quantum off with no warning, and a pinned protocol or cipher list freezes today's choice while each JDK update tightens the defaults (`jdk.tls.disabledAlgorithms`). The test asserts `X25519MLKEM768` is first on JDK 27.

**Rule 8.** A secret in source is in every clone, every build log that prints the config, and git history forever; it is also the same in every environment, so a leak from a laptop is a leak from production. Inject secrets through the platform (Kubernetes Secret, a secret manager, a mounted file) and read them at startup; failing fast on a missing one beats a default like `change-me` that reaches production.

The cost: trusting the defaults means your security posture changes when you upgrade the JDK or Spring Security, because each release tightens them. That is the point; read the security sections of the release notes on every upgrade.

## Good example

See [`examples/good/`](examples/good/).

```java
    private static final SecureRandom RANDOM = new SecureRandom();
    public static String newToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
    public static boolean matches(String presented, String storedHex) {
        return MessageDigest.isEqual(sha256(presented), HexFormat.of().parseHex(storedHex));
    }
```

```java
    private static final PasswordEncoder ENCODER = PasswordEncoderFactories.createDelegatingPasswordEncoder();
```

```java
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
```

```java
            in.setObjectInputFilter(ObjectInputFilter.Config.createFilter(
                    "maxdepth=2;maxrefs=10;maxbytes=4096;example.SafeInput$Session;!*"));
```

```java
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(privateCa);
        params.setEndpointIdentificationAlgorithm("HTTPS");
        // No setProtocols, setNamedGroups or setCipherSuites: JDK 27 offers X25519MLKEM768 first.
```

```xml
<argLine>-Djdk.serialFilter=maxdepth=10;maxbytes=65536;example.*;java.base/*;!*</argLine>
```

Each method delegates to the JDK or Spring Security and configures only what it must provide. `SecureDefaultsTest` checks 1,000 unique 43-character tokens, `{bcrypt}$2a$10$` hashes that differ for the same password, an XXE payload rejected, a `HashMap` refused by the session filter, a real loopback handshake with a keytool-made certificate that fails on the wrong host name, and the default named groups.

## Bad example

See [`examples/bad/`](examples/bad/).

```java
    // MISTAKE: java.util.Random is a 48-bit LCG; a few outputs reveal the seed and every later token.
    private static final Random RANDOM = new Random();
```

```java
        // MISTAKE: one fast unsalted SHA-256: a GPU tries billions of guesses a second, and equal passwords look equal.
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(password.getBytes(StandardCharsets.UTF_8));
```

```java
        // MISTAKE: a trust-all manager accepts any certificate, so anyone on the path can impersonate the server.
        TrustManager[] trustAll = {new X509TrustManager() {
            public void checkClientTrusted(X509Certificate[] chain, String authType) {}
            public void checkServerTrusted(X509Certificate[] chain, String authType) {}
```

```java
        // MISTAKE: a pinned group list drops X25519MLKEM768, the JDK 27 post-quantum default, with no warning.
        params.setNamedGroups(new String[] {"x25519", "secp256r1"});
```

The rest of the file has the other overrides: a hard-coded signing key, `getInstanceStrong()` on a request path, `equals` on tokens, an unfiltered `ObjectInputStream`, a default `DocumentBuilderFactory` and an accept-all `HostnameVerifier`. Each one is what older answers still produce, and each compiles without a warning.

## Checklist

When reviewing code under this skill, confirm:

- [ ] No token, ID, nonce, key or reset code comes from `Random`, `ThreadLocalRandom`, `Math.random()` or a seeded generator, and `getInstanceStrong()` is not on a request path.
- [ ] Tokens are stored as a hash and compared with `MessageDigest.isEqual`, never `equals` or `==`.
- [ ] Passwords go through a Spring Security `PasswordEncoder` (delegating, `{bcrypt}` or `{argon2}`), with the cost measured on production hardware; no `MessageDigest` touches a password.
- [ ] Every `ObjectInputStream` reading outside data has an allow-list `ObjectInputFilter` with limits ending in `!*`, and the JVM sets `jdk.serialFilter`; better, there is no Java serialization at all.
- [ ] Every `DocumentBuilderFactory`, `SAXParserFactory` or `XMLInputFactory` reading outside XML disallows DOCTYPE (or DTD support), or the JVM runs with `jdk.xml.dtd.support=deny`.
- [ ] No `X509TrustManager` with empty checks, no `HostnameVerifier` returning true, and no disabled verification flag outside test code; private CAs go through `TrustManagerFactory`.
- [ ] No `setProtocols`, `setNamedGroups`, `setCipherSuites`, `jdk.tls.namedGroups` or `jdk.tls.client.protocols` without a cited requirement.
- [ ] No secret, password, key or token literal in source or committed configuration, and a missing secret fails startup.
- [ ] SQL takes values only as bind parameters (see `sql-and-transactions`).

## When not to apply

- **Non-security randomness.** Retry jitter, sampling, shuffling and simulations should use `RandomGenerator` or `ThreadLocalRandom`: faster, and predictability does no harm there. Seeded `Random` is right for reproducible tests.
- **Generating long-lived keys offline.** A CA or signing key made once in a tool may use `getInstanceStrong()`; blocking for a moment there is acceptable.
- **A peer that breaks on the defaults.** Some old middleboxes fail on the larger post-quantum ClientHello. Set `-Djdk.tls.namedGroups` for that one process, with a ticket to remove it and the hybrid kept if the peer allows, instead of pinning groups in code.
- **Compliance that dictates parameters.** A regulator or partner may require exact protocols, groups or suites, or FIPS-validated algorithms (then `Pbkdf2PasswordEncoder` for passwords). Set them, cite the requirement next to the setting, and keep TLS 1.3 if allowed.
- **Tests.** Self-signed certificates belong in a test trust store built as in the good example; even in tests, prefer that to a trust-all manager, so the test exercises the same code path as production.
- **Existing password hashes.** A table of SHA-256 or MD5 hashes cannot be rehashed without the passwords. Prefix them with the matching legacy id the delegating encoder still reads (it maps ids such as `{MD5}` and `{SHA-256}` to deprecated encoders), and replace each with `{bcrypt}` at the user's next successful login.
