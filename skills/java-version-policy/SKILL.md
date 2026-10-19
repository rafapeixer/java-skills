---
name: java-version-policy
description: Use when creating a pom.xml or build.gradle, choosing which Java version a project compiles for or runs on, writing a Dockerfile FROM line or a CI java-version matrix, upgrading the JDK, checking the Java version at runtime, or using a feature newer than the floor or a preview feature. Key rule: the floor is the newest LTS, JDK 25, set with maven.compiler.release (javac --release 25), never source/target; enforce it with requireJavaVersion [25,) and Runtime.version().feature(), never by comparing java.version strings; test every commit on 25 and the latest GA (27); run production on an LTS with its newest quarterly update (eclipse-temurin:25), not a non-LTS release that loses updates after six months (26 ended Sept 2026), and only on a JDK your framework supports (Spring Boot 4.1 lists up to 26); mark newer features "(JDK 27+)" and never ship --enable-preview; raise the floor when Java 29 LTS ships (Sept 2027).
metadata:
  verified: 2026-10-18
---

# Java version policy

## The decision

Every Java project states three versions, usually without deciding any of them: the release it compiles for (`maven.compiler.release`), the JDK that builds it in CI, and the runtime in the container. Left alone they drift apart. The build targets an old release because the archetype said so, CI runs whatever `setup-java` defaulted to, and production runs a non-LTS image that stopped getting security updates the month the next release shipped. A version check written for `1.8.0_402` throws on `27`. This skill fixes which release is the floor, how CI and the build enforce it, which JDK production runs, how newer and preview features are gated, and when the floor moves. The lint and dependency gates that run on top are the `ci-guardrails` skill.

## The rule

1. Set the floor to the newest LTS, JDK 25 today, with `<maven.compiler.release>25</maven.compiler.release>` (javac `--release 25`), never with `source` and `target`.
2. Fail the build below the floor with the enforcer rule `requireJavaVersion [25,)`, and check the running JDK with `Runtime.version().feature()`, never by parsing or comparing the `java.version` string.
3. Build and test every commit on the floor and on the latest GA release (25 and 27), with the latest JDK's stricter options turned on in a JDK-activated profile.
4. Run production on an LTS that your distribution still updates (Temurin 25: at least until September 2031), on its newest quarterly update, and on a non-LTS release only if you will move to the next one within six months.
5. Deploy only on a JDK your framework lists as supported: Spring Boot 4.1.1 is compatible up to Java 26, so a Boot service runs on 25 in production while CI already tests 27.
6. Mark every rule, snippet or code path that needs a newer release, "(JDK 27+)", gate it on `Runtime.version().feature()` or a `<jdk>` profile, and never ship code compiled with `--enable-preview`.
7. Review the versions every March and September when a feature release ships, and raise the floor in one commit when the next LTS ships (Java 29, planned for September 2027).

## Why

**Rule 1.** Oracle's roadmap lists 8, 11, 17, 21 and 25 as LTS releases and plans the next LTS, Java 29, for September 2027; between them come six-month releases (26 in March 2026, 27 on 2026-09-15). The newest LTS is the version every vendor patches longest and every framework supports, so it is the release you can promise to consumers and operators. The javac reference says `--release` compiles "against the combined Java SE and JDK API for the specified release", and it cannot be combined with `-source`/`-target`. With only `source`/`target`, javac on JDK 27 checks the syntax of 25 but links against the JDK 27 class library: code that calls a method added after 25 compiles, and fails with `NoSuchMethodError` on a 25 runtime. The good example's test reads its own class file and asserts major version 69 (Java 25) on both JDKs.

**Rule 2.** Without `requireJavaVersion`, a build on JDK 21 stops with "release version 25 not supported", which reads like a broken toolchain rather than a policy. The enforcer names the expected range. At runtime, `java.version` is a string like `25.0.4.1`, `27` or `27-ea`: `"9.0.4".compareTo("25") > 0`, so a string check lets Java 9 through, and the Java 8 habit of reading the second dot-separated field returns `0` on `25.0.4.1` and throws on `27`, which has no dots at all. `Runtime.version()` returns a parsed `Runtime.Version` whose `feature()` is the number you mean.

**Rule 3.** The floor proves the code still runs where you promised; the latest GA tells you what the next upgrade will break, months before you have to do it. JDK 26 started warning when code mutates `final` fields through reflection (JEP 500), and says `deny` "will become the default in a future release"; JDK 27 turns on compact object headers (JEP 534) and makes G1 the default collector in all environments (JEP 523). The good example's `jdk26-plus` profile, activated by `<jdk>[26,)</jdk>`, runs the tests on 27 with `--illegal-final-field-mutation=deny`, so a library that still mutates final fields fails CI now instead of production later. JDK 25 does not know the option, which is why it lives in a profile and not in `argLine`.

**Rule 4.** Adoptium's support page lists Temurin 25 until at least September 2031, 21 until at least December 2029, 26 until September 2026 and 27 until March 2027. Non-LTS releases get updates only until the next feature release: an image built `FROM eclipse-temurin:26-jre` has had no security fix since September 2026. Updates ship quarterly, "usually on the third Tuesday of January, April, July, and October", with out-of-cycle security updates in between (25.0.4.1 is one). Pin the full tag (`25.0.4.1_1-jre`) so a deploy is reproducible, and let Dependabot or Renovate raise it after each update; a bare `25-jre` changes under you, and a pinned tag nobody bumps ages out.

**Rule 5.** Framework support is a separate fact from JDK support. The Spring Boot 4.1.1 system requirements say it "requires at least Java 17 and is compatible with versions up to and including Java 26"; 27 is not listed yet, and 26 is out of updates, so a Boot service runs on 25 in production. Testing on 27 in CI is still right (rule 3), because that is how you find out before Boot adds it. Native images are the same: Spring Boot 4.1 needs GraalVM 25 or later.

**Rule 6.** A snippet that needs JDK 27 fails on a 25 floor, and an agent following an unmarked rule writes exactly that code. JEP 12 says a JDK accepts `--enable-preview` only with `--release` set to its own version, and "a JVM implementation for Java SE N must not define a class file that depends on the preview features of a different Java SE release". A jar built with preview features on 25 does not start on 27, and the API can change in between: structured concurrency is in its seventh preview in JDK 27 (JEP 533). Use preview features in experiments, never in code that ships. When behavior differs by release, branch on `Runtime.version().feature()` or a `<jdk>` profile, as the good example does for compact object headers.

**Rule 7.** A floor nobody reviews is a year out of date within twelve months. Put the review in the week after each March and September release: add the new GA to the CI matrix, read its JEPs for changed defaults, drop the old non-LTS, and bump the runtime image. When an LTS ships, raise `maven.compiler.release`, `requireJavaVersion`, the Dockerfile and the CI matrix in one reviewed commit, because the bytecode level, the API and the runtime defaults change together. This repository does the same: its floor moves to 29 when Java 29 ships.

The cost: a floor that tracks the newest LTS forces consumers still on 21 to upgrade or stay on an older release of your code, and a two-JDK matrix doubles CI minutes. Both are cheaper than a production JDK without security updates or an upgrade that breaks on a default nobody tested.

## Good example

See [`examples/good/`](examples/good/).

```xml
<!-- The floor: the newest LTS. release, not source/target, so the JDK 25 API is enforced too. -->
<maven.compiler.release>25</maven.compiler.release>
```

```java
    public static void require(Runtime.Version running) {
        if (running.feature() < FLOOR) {
            throw new IllegalStateException("needs Java " + FLOOR + "+, running " + running);
        }
    }
```

```xml
<!-- Version-gated: the option exists from JDK 26 (JEP 500); JDK 25 would refuse to start. -->
<id>jdk26-plus</id>
<activation>
<jdk>[26,)</jdk>
</activation>
<properties>
<argLine>--illegal-final-field-mutation=deny</argLine>
</properties>
```

```dockerfile
FROM eclipse-temurin:25.0.4.1_1-jre
```

`mvn verify` runs `requireJavaVersion [25,)`, and `JavaFloorTest` checks that the class file is version 69 even when JDK 27 compiled it, that `require` accepts `25.0.4.1+1` and `27-ea+12` and rejects `21.0.8`, and that on JDK 26+ the tests really ran with `--illegal-final-field-mutation=deny`. `ci.yml` runs the matrix `["25", "27"]`, and the Dockerfile pins the newest Temurin 25 update.

## Bad example

See [`examples/bad/`](examples/bad/).

```java
    public static boolean supported() {
        return System.getProperty("java.version").compareTo("25") >= 0;
    }
```

```dockerfile
# MISTAKE: JDK 26 is a non-LTS release that stopped getting updates when 27 shipped
# (September 2026); every CVE fixed since then is still open in this image.
FROM eclipse-temurin:26-jre
```

```yaml
      - run: ./mvnw -B -ntp verify -Dmaven.compiler.release=21 # MISTAKE: lowers the floor on the command line to get a green build.
      - run: java --enable-preview -jar target/app.jar # MISTAKE: preview class files run only on the exact JDK that built them.
```

The bad project has no `requireJavaVersion`, checks the version by string comparison and by the pre-JDK 9 `1.8.0` scheme (which throws on `27`), runs production on an unsupported non-LTS image, and tests on one JDK that is neither the floor nor the latest. It compiles; it fails on the first JDK upgrade or the first CVE.

## Checklist

When reviewing code under this skill, confirm:

- [ ] The build sets `maven.compiler.release` (or Gradle `options.release`) to 25, and no `source`/`target` property or `-Dmaven.compiler.release` override exists anywhere.
- [ ] `maven-enforcer-plugin` runs `requireJavaVersion [25,)`.
- [ ] No code reads `java.version` or `java.specification.version` to compare versions; runtime checks use `Runtime.version().feature()`.
- [ ] CI builds and tests on 25 and on the latest GA (27 today).
- [ ] The production image is an LTS (`eclipse-temurin:25...`) pinned to a full update tag that automation bumps, not a non-LTS or a bare `latest`.
- [ ] The production JDK is inside the framework's documented range (Spring Boot 4.1.1: up to 26).
- [ ] Every feature newer than the floor is marked "(JDK N+)" and gated by `Runtime.version().feature()` or a `<jdk>` profile.
- [ ] Nothing that ships is compiled or run with `--enable-preview`.
- [ ] A review date exists for the next March or September release, and the floor bump to 29 is planned for September 2027.

## When not to apply

- **A library with consumers on older LTS releases.** A library used by projects still on 21 or 17 may keep a lower `--release`, the oldest LTS its users need, as long as CI also tests on 25 and the latest GA; services and applications follow the floor.
- **A multi-release jar.** When a library must use a newer API where available, build a multi-release jar (`Multi-Release: true`, classes under `META-INF/versions/27`) compiled with `--release 27` for those classes only, instead of raising the floor for everyone.
- **A team that upgrades every six months.** Running production on the latest non-LTS (27 until March 2027) is fine if you move to 28 within its six-month window and your framework supports it; put the next upgrade on the calendar the day you deploy.
- **Commercial or vendor-specific support.** If you buy support with a different schedule (Oracle Java SE subscription, a cloud vendor's build), use that vendor's published end dates in rule 4 instead of Adoptium's.
- **Experiments with preview features.** A spike or a benchmark may use `--enable-preview` with `--release` equal to the running JDK; keep it out of the deployable module and do not merge it into code that ships.
