---
name: fast-startup-aot-cache
description: Use when a Java/Spring Boot service starts too slowly (scale-to-zero, autoscaling, serverless, CLIs, slow readiness), when adding an AOT cache or CDS to a Dockerfile or buildpack, or choosing between the AOT cache, GraalVM native image and CRaC. Key rule: on JDK 25+ use the Leyden AOT cache first (JEP 483/514/515): train with -XX:AOTCacheOutput=app.aot, run with -XX:AOTCache=app.aot; in Spring Boot extract with -Djarmode=tools, train with -Dspring.context.exit=onRefresh in the runtime image (no database in the build: Hikari initialization-fail-timeout=-1 on the training run only), or BP_JVM_AOTCACHE_ENABLED=true with buildpacks; the cache must match the exact JDK, OS/arch and jar class path, so create it in the image that runs it, default AOTMode (auto) in production; it halves startup but not cold-JIT errors; one-step training needs 2x the heap; GraalVM native image only when millisecond startup or footprint justify a closed-world build; CRaC is not in mainline JDKs; never assert startup times in tests.
metadata:
  verified: 2026-10-19
---

# Fast startup with the AOT cache

## The decision

A JVM service spends its first seconds loading, verifying and linking thousands of classes and running cold code in the interpreter. That cost is paid on every start: each autoscaling step, each scale-from-zero, each rolling deploy, each CLI invocation. There are three ways to pay less: the HotSpot AOT cache from Project Leyden, which needs no code change; a GraalVM native image, which compiles the whole application ahead of time under a closed-world assumption; and checkpoint/restore (CRaC), which is not part of the JDK. This skill decides which one to use, how to build the AOT cache so it is actually used, and how to check it without timing assertions. Heap and GC flags for the same container are in `container-runtime-tuning`.

## The rule

1. On JDK 25+, use the AOT cache before anything else: train once with `-XX:AOTCacheOutput=app.aot`, then start with `-XX:AOTCache=app.aot`.
2. Create the cache in the same image, JDK build and class path that run it, as a step of the image build, never as a file copied from another build.
3. Put the application on the class path as jar files (`-jar` or jars in `-cp`), never as a class directory, and keep the same class path and module options in training and production.
4. In Spring Boot 4.1, extract the jar with `-Djarmode=tools extract` and train with `-Dspring.context.exit=onRefresh` on the extracted `application.jar`, relaxing for the training run only what connects at startup (`spring.datasource.hikari.initialization-fail-timeout=-1`), or set `BP_JVM_AOTCACHE_ENABLED=true` for buildpack images.
5. Leave `-XX:AOTMode` at its default (`auto`) in production, and use `required` (`on` on JDK 25) only in a build check that must fail when the cache is unusable.
6. Give the one-step training run about twice the heap it normally uses, or switch to the two-step `record`/`create` workflow when the build machine is small.
7. Choose GraalVM native image only when you need millisecond startup or a much smaller footprint and can accept a closed-world build; do not plan on CRaC, which is not in mainline JDK builds.
8. Prove the cache works by loading it in a mode that fails when it cannot be used, never by asserting how long startup took.

## Why

**Rule 1.** The AOT cache, introduced in JDK 24 by [JEP 483](https://openjdk.org/jeps/483), stores classes already loaded and linked during a training run. JEP 483 measured Spring PetClinic 3.2 (about 21,000 classes) starting in 2.604 s instead of 4.486 s, 42% faster, with a 130 MB cache. [JEP 514](https://openjdk.org/jeps/514) (JDK 25) reduced training to one command: `-XX:AOTCacheOutput` runs the app and writes the cache when it exits. [JEP 515](https://openjdk.org/jeps/515) (JDK 25) adds method profiles to the same cache, so the JIT compiles hot methods sooner (19% faster on its warm-up benchmark, cache about 2.5% larger). No code changes are needed and the benefit grows with each release: [JEP 516](https://openjdk.org/jeps/516) (JDK 26) lets the cache work with every GC, including ZGC, which JDK 25 does not support with the cache, and [JEP 544](https://openjdk.org/jeps/544) (integrated for JDK 28) adds compiled code. All of these are listed on the [Leyden project page](https://openjdk.org/projects/leyden/). On JDK 24 and earlier the predecessor is CDS (`-XX:ArchiveClassesAtExit`); this repository's floor is 25, so use the AOT cache.

**Rule 2.** The [JDK 25 `java` reference](https://docs.oracle.com/en/java/javase/25/docs/specs/man/java.html) says a cache is specific to the application class path, the JDK release, and the OS and CPU architecture: "If any of the above changes, you must recreate the AOT cache." A cache built in CI on one JDK and copied into an image with a newer patch release is silently ignored, so the service starts as slowly as before and nobody notices. Running the training run as a `RUN` step in the final image makes the match automatic: a new base image or a new jar rebuilds the layer and the cache with it. JEP 483 allows the GC and the main class to differ between training and production, so the heap and GC flags from `container-runtime-tuning` can be set after training.

**Rule 3.** JEP 483: "Only JAR files are supported; directories are not", and production may append entries to the class path but must otherwise match training. The arguments of `-m`, `--module-path`, `--add-modules` and `--enable-native-access` must be identical, and options such as `--add-opens` or `--patch-module` must not be used. Classes from custom class loaders and signed jars are not cached. A build that trains with `-cp target/classes` and ships `-jar app.jar` caches only JDK classes.

**Rule 4.** The [Spring Boot AOT cache reference](https://docs.spring.io/spring-boot/reference/packaging/aot-cache.html) supports the cache on Java 25 and above and gives the flow: `java -Djarmode=tools -jar my-app.jar extract`, then `java -XX:AOTCacheOutput=app.aot -Dspring.context.exit=onRefresh -jar my-app.jar` in the extracted folder, then `java -XX:AOTCache=app.aot -jar my-app.jar`. It adds: "You have to use the cache file with the extracted form of the application, otherwise it has no effect." `spring.context.exit=onRefresh` stops the application once the context has started, so the training run loads every bean class without serving traffic or running jobs. Beans that connect to remote services at startup still do so during the build; the [how-to guide](https://docs.spring.io/spring-boot/how-to/aot-cache.html) says to prevent that through configuration. The usual one is the pool: HikariCP's `initializationFailTimeout` defaults to 1 ms, which fails startup when the database is unreachable, and "a value less than zero will bypass any initial connection attempt" ([HikariCP README](https://github.com/brettwooldridge/HikariCP)). Pass `-Dspring.datasource.hikari.initialization-fail-timeout=-1` on the training `RUN` line only (or, with a hand-built pool, read the value from a property that only the build sets), so the build needs no database and production still fails fast. Measured in benchmarks/orders-api (TUNING.md) with that recipe: startup went from 1.93 s to 1.00 s. It did not fix the first seconds under load: a cold 1,500 req/s phase had 3.1-5.1% errors with the cache and 5.1% without, inside the spread, because the cache holds classes loaded and linked at startup while those errors came from the JIT compiling request paths (late C2 compilations and deoptimizations in JFR). JEP 515's method profiles need a representative training workload, which a build without a database cannot run; for the cold start, keep readiness behind a warm-up or ramp traffic onto new pods. With Cloud Native Buildpacks, the same guide says `BP_JVM_AOTCACHE_ENABLED=true` makes the buildpack run the training, store the cache in the image and use it at launch; `TRAINING_RUN_JAVA_TOOL_OPTIONS` overrides `JAVA_TOOL_OPTIONS` for the training run only.

**Rule 5.** In the default `auto` mode, a cache that fails to load is skipped and the JVM runs normally; the `java` reference calls this "the recommended mode for production environments". `required` (JDK 27; `on` is now its discouraged alias, and JDK 25 has only `on`) makes the JVM "print an error message and exit immediately", which is what you want in a build check and exactly what you do not want in a pod: an unexpected flag added by an operator turns into a crash loop. To see whether production uses the cache, start it once with `-Xlog:aot`.

**Rule 6.** JEP 514: the sub-process that writes the cache "uses its own Java heap with the same size as the heap used for the training run", so "the memory needed to complete the one-step workflow is double the heap size". A `docker build` on a small CI runner can fail or be killed here. Either give the build more memory, pass a smaller heap to the assembly step with `JDK_AOT_VM_OPTIONS`, or use the two steps: `-XX:AOTMode=record -XX:AOTConfiguration=app.aotconf`, then `-XX:AOTMode=create -XX:AOTConfiguration=app.aotconf -XX:AOTCache=app.aot`.

**Rule 7.** A [GraalVM native image](https://www.graalvm.org/latest/reference-manual/native-image/) starts in milliseconds and uses a fraction of the JVM's memory, but "all the bytecode in your application that can be called at runtime must be known (observed and analyzed) at build time". The [Spring Boot native image guide](https://docs.spring.io/spring-boot/reference/packaging/native-image/introducing-graalvm-native-images.html) lists what that costs: reflection, resources, serialization and proxies need metadata, "the application classpath is fixed at build time", `@Profile` and `@ConditionalOnProperty` have limits because beans cannot change at runtime, and the build is a separate, much slower compilation. Profile-guided optimization is "not available in GraalVM Community Edition". Choose it for CLIs and functions that live for seconds; for a long-running service, the AOT cache gives most of the startup gain while keeping the JIT and every library working. [CRaC](https://openjdk.org/projects/crac/) is an OpenJDK research project with no JEP; JDK 25 and 27 builds do not include it, so it depends on a vendor's special build.

**Rule 8.** Startup time on a shared CI runner varies by more than the cache saves, so a threshold is either flaky or so loose it proves nothing. Loading the cache with `-XX:AOTMode=required` (or `on`) is a yes/no answer: exit 0 means the cache matched and was used. The good example does this, then rebuilds the jar and checks that the stale cache is rejected in that mode and skipped in `auto`.

The cost: the image grows by the size of the cache (about 10 MB for the example, 130 MB for PetClinic in JEP 483), and the build runs the application once. A training run that does not exercise the startup path well gives a smaller gain, never a wrong result.

## Good example

See [`examples/good/`](examples/good/).

```dockerfile
RUN java -Djarmode=tools -jar application.jar extract --layers --destination extracted
FROM eclipse-temurin:25-jre
RUN java -XX:AOTCacheOutput=app.aot -Dspring.context.exit=onRefresh -Dspring.datasource.hikari.initialization-fail-timeout=-1 -jar application.jar
ENTRYPOINT ["java", "-XX:AOTCache=app.aot", "-jar", "application.jar"]
```

```java
Result training = java(dir, "-XX:AOTCacheOutput=app.aot", "-jar", "app.jar");
assert Files.size(cache) > 0 : "no AOT cache written";
Result production = java(dir, "-XX:AOTCache=app.aot", "-XX:AOTMode=" + requiredMode(), "-jar", "app.jar");
assert production.exit == 0 : "cache not usable:\n" + production.output;
return Runtime.version().feature() >= 27 ? "required" : "on";
```

`Main.java` compiles a small program into `app.jar`, trains a cache with the same JDK it runs on (`ProcessHandle.current().info().command()`), runs the jar with the cache in the mode that fails when the cache is unusable, then rebuilds the jar and asserts that the stale cache is refused in that mode and skipped in the default mode. The `Dockerfile` is the Spring Boot flow with the training run inside the runtime image, no database needed at build time, and a non-root user.

## Bad example

See [`examples/bad/`](examples/bad/).

```dockerfile
FROM eclipse-temurin:25-jdk AS builder
RUN timeout 60 java -XX:AOTCacheOutput=app.aot -jar app.jar || true
FROM eclipse-temurin:27-jre
ENTRYPOINT ["java", "-XX:AOTMode=on", "-XX:AOTCache=/app/app.aot", "-jar", "/app/app.jar"]
```

```java
Process training = new ProcessBuilder("java", "-XX:AOTCacheOutput=app.aot",
"-cp", "target/classes", "com.example.App").inheritIO().start();
if (millis > 500) {
```

Every line looks plausible and the image builds, but the cache is never used: a different JDK in the runtime stage, a fat jar or a class directory instead of the extracted jars, and a training run that serves traffic until a timeout kills it. `AOTMode=on` then turns the next mismatch into a JVM that refuses to start, and the timing check passes or fails depending on the runner.

## Checklist

When reviewing a build or Dockerfile under this skill, confirm:

- [ ] The JDK is 25 or later and the cache is created with `-XX:AOTCacheOutput` (or `record`/`create`), not with CDS flags.
- [ ] The training run happens in the final runtime image, so its JDK build is the one that loads the cache.
- [ ] Training and production use the same jars in the same class path order and the same module options, with no class directories.
- [ ] A Spring Boot app is extracted with `-Djarmode=tools` and trained with `-Dspring.context.exit=onRefresh`, or built with `BP_JVM_AOTCACHE_ENABLED=true`.
- [ ] The training run does not reach production databases or partner APIs, and startup checks relaxed for it (Hikari `initialization-fail-timeout=-1`) are relaxed on the training command only.
- [ ] Production keeps the default `AOTMode`; `required`/`on` appears only in a build or CI check.
- [ ] The build has memory for about twice the training heap, or uses the two-step workflow.
- [ ] Tests check that the cache loads (exit code with `required`/`on`, or `-Xlog:aot`), not a startup time.
- [ ] Native image or CRaC is chosen only with a written reason the AOT cache does not meet.

## When not to apply

- A service that starts once and runs for weeks gains little; the training step and the larger image may not be worth it, measure the start time first.
- Before JDK 25 (on JDK 24 the AOT cache exists, but Spring Boot supports it only from 25), use CDS with `-XX:ArchiveClassesAtExit` as the Spring Boot reference describes.
- ZGC on JDK 25 cannot use the AOT cache; either use G1 there or wait for JDK 26+ (JEP 516) before combining them.
- Command-line tools and short functions that must start in milliseconds: a GraalVM native image is the right tool, with its metadata and build cost accepted.
- Applications that load classes from custom class loaders (plugin systems, some app servers) get only the JDK and class-path part of the gain.
