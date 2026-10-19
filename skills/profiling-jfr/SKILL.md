---
name: profiling-jfr
description: Use when a Java service is slow, uses too much CPU or memory, has latency spikes, lock contention or pinned virtual threads, when optimizing code or adding timing logs, when setting up production diagnostics, or when using JDK Flight Recorder, jcmd JFR.start/JFR.dump, jfr print/view/summary or jdk.jfr.Recording. Key rule: measure before optimizing; run JFR always on in production with -XX:StartFlightRecording=settings=default,maxage=...,maxsize=...,dumponexit=true on the java command line (not JAVA_TOOL_OPTIONS, which jcmd also reads); dump the last minutes with jcmd JFR.dump begin=-15m; use profile.jfc only for short recordings; read with jfr view hot-methods, allocation-by-site, contention-by-site, pinned-threads; default.jfc already records pinning (jdk.VirtualThreadPinned), contention and allocation; CPU-time sampling (jdk.CPUTimeSample, JEP 509) is experimental and Linux-only on JDK 25; JDK 27 redacts secrets in recordings (JEP 536); test with jdk.jfr.consumer.RecordingFile, not stopwatches.
metadata:
  verified: 2026-10-18
---

# Profiling with JDK Flight Recorder

## The decision

When a Java service is slow or expensive, the tempting move is to guess: wrap a call in `System.nanoTime()`, add a cache, rewrite a loop, add a thread pool. The decision this skill fixes is what you measure with, and when that measurement starts. JDK Flight Recorder (JFR) is built into every JDK, records CPU samples, allocation, GC, locks, I/O and virtual-thread pinning with low overhead, and can run continuously in production, so the data for the incident already exists when the incident happens. Without it, you reproduce the problem on a laptop that has neither the load nor the data, and optimize whatever the stopwatch pointed at. Container memory and GC settings are `container-runtime-tuning`; virtual-thread design is `virtual-threads`.

## The rule

1. Do not optimize code until a JFR recording from the real workload shows where the time, allocation or waiting goes.
2. Start every production JVM with an always-on recording: `-XX:StartFlightRecording=name=always-on,settings=default,maxage=6h,maxsize=512m,dumponexit=true,filename=...` on the `java` command line.
3. Keep `-XX:StartFlightRecording` out of `JAVA_TOOL_OPTIONS`, because every `jcmd` and other JVM in the container would start its own recording.
4. During an incident, copy the recent past with `jcmd <pid> JFR.dump name=always-on begin=-15m filename=...` instead of restarting or attaching a profiler.
5. Use `settings=profile` and the experimental `jdk.CPUTimeSample` (JDK 25+, Linux) only in recordings with a `duration`, never as the always-on setting.
6. Read recordings with `jfr summary`, then `jfr view` (`hot-methods`, `allocation-by-site`, `contention-by-site`, `pinned-threads`), and `jfr print --events <type>` for stack traces.
7. Check diagnostics in tests with `jdk.jfr.Recording` and `jdk.jfr.consumer.RecordingFile` (assert that the expected event exists), never with elapsed-time thresholds.
8. Treat recordings as sensitive: on JDK 25 scrub them with `jfr scrub` before sharing; JDK 27 redacts secrets in arguments, environment and properties by default (JEP 536).

## Why

**Rule 1.** A guess costs twice: the change you make and the real problem you leave. The bad example times one call on a cold JVM, which measures class loading and the interpreter, and then "fixes" it with per-thread caches. A recording answers with numbers: `jfr view hot-methods` lists the methods with the most execution samples, `allocation-by-site` the code that allocates the most, `contention-by-site` the monitors threads waited for. If the hot method is not yours (GC, JIT, a library), the fix is not in your code either.

**Rule 2.** [JEP 328](https://openjdk.org/jeps/328) set the goal of "at most 1% performance overhead out-of-the-box on SPECjbb2015". The [JDK 25 `java` reference](https://docs.oracle.com/en/java/javase/25/docs/specs/man/java.html) says `default.jfc` "collects a predefined set of information with low overhead, so it has minimal impact on performance and can be used with recordings that run continuously", and the [troubleshooting guide](https://docs.oracle.com/en/java/javase/25/troubleshoot/diagnostic-tools.html) describes "a recording that is always on and saves, for example, the last six hours of data". `maxage` and `maxsize` bound the data kept on disk (both apply only with `disk=true`, the default, and without them the JVM logs "No limit specified, using maxsize=250MB as default"). Size them to the disk: 6 hours or 512 MB, whichever comes first, is a start. `dumponexit=true` writes the file when the JVM shuts down, and `%p` in `filename` adds the process id. In a container, write to a mounted volume so the file outlives the pod. `default.jfc` already enables what most incidents need: `jdk.ExecutionSample` every 20 ms, `jdk.ObjectAllocationSample` throttled to 150 per second, and `jdk.JavaMonitorEnter`, `jdk.ThreadPark` and `jdk.VirtualThreadPinned` above 20 ms.

**Rule 3.** `JAVA_TOOL_OPTIONS` is read by every JVM in the environment, and `jcmd` and `jfr` are JVMs. With the recording there, each `jcmd` call prints "Started recording 1" and leaves its own `app-<pid>.jfr` behind, which is what happened when this was tested on JDK 25. Heap and GC options in `JAVA_TOOL_OPTIONS` (`container-runtime-tuning`) are harmless for a tool; a recording is not.

**Rule 4.** `JFR.dump` writes the data of a running recording, "the recording continues to run after the data is written" ([jcmd reference](https://docs.oracle.com/en/java/javase/25/docs/specs/man/jcmd.html)), and `begin=-15m` keeps only the last 15 minutes. That means the data for a latency spike that already ended is still there, and the service keeps running. The `eclipse-temurin` JRE image ships `jfr` but not `jcmd`, so run `jcmd` from a JDK image that shares the pod's process namespace, or take the snapshot from inside the application with `FlightRecorder.getFlightRecorder().takeSnapshot()`. `JFR.view` shows the same views as `jfr view` on a running JVM.

**Rule 5.** `profile.jfc` "provides more data than the default configuration, but can have more overhead and impact performance. Use this configuration for short periods of time" (`java` reference). Start it with `jcmd <pid> JFR.start settings=profile duration=2m`. [JEP 509](https://openjdk.org/jeps/509) (JDK 25) adds `jdk.CPUTimeSample`, which samples by CPU time instead of wall-clock time and so also catches time in native code; it is experimental, Linux-only and off in `default.jfc`. [JEP 520](https://openjdk.org/jeps/520) (JDK 25) adds `jdk.MethodTiming` and `jdk.MethodTrace` for a filter of methods, and says the 1% goal does not hold when many methods are instrumented, so time a few methods for a short period, not a package.

**Rule 6.** `jfr summary` shows how many events of each type exist, which tells you which view will have data. The [`jfr` reference](https://docs.oracle.com/en/java/javase/25/docs/specs/man/jfr.html) lists the subcommands; `jfr --help view` lists the views (in JDK 25 also `cpu-time-hot-methods`, `gc-pauses`, `container-cpu-throttling`, `container-memory-usage`). `jfr print` shows 5 stack frames by default, too few to reach your code under framework frames; `--stack-depth 20` usually does. For pinning, the stack trace is the answer: since [JEP 491](https://openjdk.org/jeps/491) `synchronized` no longer pins, so a `jdk.VirtualThreadPinned` event points at a class initializer or a native frame (the good example's event points at `SlowInit.<clinit>`). JDK Mission Control opens the same files with a GUI.

**Rule 7.** A threshold on elapsed time is flaky on a busy CI runner and proves nothing on a fast one. The events are the real contract: the good example records with `default.jfc`, creates one contended monitor, one virtual thread blocked in a class initializer and 256 MiB of short-lived arrays, and asserts that `jdk.JavaMonitorEnter`, `jdk.VirtualThreadPinned` and `jdk.ObjectAllocationSample` are in the file. Each problem lasts 200 ms, ten times the threshold, so a slow machine only makes it more visible. Use the same pattern to check that a custom `jdk.jfr.Event` your service emits is actually recorded.

**Rule 8.** A recording contains the JVM command line, environment variables and system properties, so a database password passed as `-Dspring.datasource.password=...` ends up in every file you attach to a ticket. [JEP 536](https://openjdk.org/jeps/536) (JDK 27) redacts these values to `[REDACTED]` by default for keys and arguments matching patterns such as password, token, secret and key, configurable with `-XX:FlightRecorderOptions:redact-key=...,redact-argument=...`. On JDK 25 nothing is redacted: keep secrets out of the command line, and use `jfr scrub --exclude-events jdk.InitialEnvironmentVariable,jdk.InitialSystemProperty,jdk.JVMInformation` before sharing a file. Stack traces and class names can still reveal internals either way.

The cost: an always-on recording uses disk (`maxsize`) and around 1% CPU, and someone has to learn the views. Both are cheaper than the first incident investigated without data.

## Good example

See [`examples/good/`](examples/good/).

```java
try (Recording recording = new Recording(Configuration.getConfiguration("default"))) {
recording.start();
recording.stop();
recording.dump(file);
for (RecordedEvent event : RecordingFile.readAllEvents(file)) {
counts.merge(event.getEventType().getName(), 1, Integer::sum);
assert counts.getOrDefault(type, 0) > 0 : "no " + type + " event recorded";
```

```bash
java -XX:StartFlightRecording=name=always-on,settings=default,maxage=6h,maxsize=512m,dumponexit=true,filename=/tmp/app-%p.jfr -jar app.jar &
jcmd "$pid" JFR.dump name=always-on begin=-15m filename=/tmp/incident.jfr
jfr view hot-methods /tmp/incident.jfr
jfr print --events jdk.VirtualThreadPinned --stack-depth 20 /tmp/incident.jfr
```

`Main.java` creates three problems, records them with the production settings, and asserts from the file that each one is visible, including that the pinning event's stack trace names the class initializer that caused it. `production.sh` shows the same recording as an always-on flag, the incident dump, a short `profile` recording, and the views to read first.

## Bad example

See [`examples/bad/`](examples/bad/).

```java
Recording recording = new Recording(Configuration.getConfiguration("profile"));
recording.enable("jdk.ThreadPark").withThreshold(Duration.ZERO).withStackTrace();
recording.setToDisk(false);
long start = System.nanoTime();
parse("2026-10-18");
```

Both halves are common: a recording switched on with every knob at maximum and nowhere to keep the data, so it costs CPU and is gone when it is needed; and a stopwatch on a cold JVM that leads to an optimization nobody measured.

## Checklist

When reviewing code, Dockerfiles or runbooks under this skill, confirm:

- [ ] Every optimization in the change cites a recording (view name and numbers) from a realistic workload.
- [ ] Production JVMs start with `-XX:StartFlightRecording` using `settings=default` and explicit `maxage` and `maxsize`.
- [ ] The recording flag is on the `java` command line or `ENTRYPOINT`, not in `JAVA_TOOL_OPTIONS`.
- [ ] The recording is written to a path that survives the container (`dumponexit=true`, a volume), and the runbook names the `JFR.dump` command.
- [ ] `profile.jfc`, zero thresholds, `jdk.CPUTimeSample` and method tracing appear only in recordings with a `duration`.
- [ ] No timing-based assertions check performance in tests; JFR events are asserted through `RecordingFile` where diagnostics matter.
- [ ] Recordings shared outside the team are scrubbed (JDK 25) or come from JDK 27 with redaction left on.
- [ ] Secrets are not passed as command-line arguments or system properties in the first place.

## When not to apply

- Micro-optimizing a known hot method: JFR finds it, but compare versions with a benchmark harness (JMH), not with JFR or a stopwatch.
- Native memory growth outside the heap: JFR shows `native-memory-committed`, but the breakdown comes from Native Memory Tracking (`-XX:NativeMemoryTracking=summary` and `jcmd VM.native_memory`), with its 5 to 10% cost, in a test environment.
- A GraalVM native image supports only part of JFR; check the GraalVM documentation for the events it records.
- Short-lived CLI tools that exit in a second: a recording adds startup cost; profile them with a `duration` recording during development instead.
- Distributed latency across services is a tracing question (`observability-otel`); JFR explains what one JVM did during the slow span.
