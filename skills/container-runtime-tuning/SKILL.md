---
name: container-runtime-tuning
description: Use when writing a Dockerfile or Kubernetes manifest for a Java service, setting container memory or CPU limits for the JVM, choosing -Xmx or -XX:MaxRAMPercentage, seeing OOMKilled pods or a heap far smaller than the pod, picking the GC or base image for a container, or deciding between JAVA_TOOL_OPTIONS, JAVA_OPTS and ENTRYPOINT flags. Key rule: never hard-code -Xmx in a container; set -XX:MaxRAMPercentage=75 (the default 25 leaves three quarters of the pod unused), and with G1 -XX:InitialRAMPercentage=75 too (initial = max, no 16 MB start); the rest is for metaspace, code cache, stacks and direct buffers; keep UseContainerSupport on; set a CPU limit and do not override ActiveProcessorCount; name the GC (-XX:+UseG1GC) because JDK 25 silently picks Serial with 1 CPU or under 1792 MB (JDK 27 always picks G1, JEP 523); pass flags in JAVA_TOOL_OPTIONS with an exec-form ENTRYPOINT on eclipse-temurin:25-jre as a non-root user; verify with -Xlog:os+container=trace.
metadata:
  verified: 2026-10-19
---

# Container runtime tuning

## The decision

A JVM in a container sizes its heap, its GC threads and its default garbage collector from what it detects at startup. Since JDK 10 it reads the cgroup limits itself, so the decision is no longer how to tell it about the container, but which of its defaults are wrong for a service and which old flags now break a good default. Get it wrong one way and a 2 GiB pod runs with a 512 MiB heap and pays for 1.5 GiB it never uses; get it wrong the other way (`-Xmx` copied from a VM) and the kernel OOM-kills the pod with no `OutOfMemoryError` and no heap dump. This skill fixes how the heap is sized, which GC runs, how CPU limits reach the JVM, how flags are passed, and how you check the result. Measuring where memory and CPU actually go is `profiling-jfr`.

## The rule

1. Size the heap with `-XX:MaxRAMPercentage=75`, set `-XX:InitialRAMPercentage` to the same value when running G1 in a fixed-size container, and never set `-Xmx` (or `-XX:MaxHeapSize`) in a container image or manifest.
2. Keep `UseContainerSupport` at its default (on) and never set `MaxRAM`, which JDK 27 ignores.
3. Set a memory limit equal to the request and a CPU limit on every Java container, and do not set `-XX:ActiveProcessorCount` unless several JVMs share one container.
4. Name the collector, `-XX:+UseG1GC`, so JDK 25 does not silently choose Serial in a container with one CPU or less than 1792 MB.
5. Pass JVM options in `JAVA_TOOL_OPTIONS` and start the JVM with an exec-form `ENTRYPOINT ["java", ...]`, never through `sh -c` with `$JAVA_OPTS`.
6. Run on `eclipse-temurin:25-jre` (or a `jlink` runtime) as a fixed non-root UID.
7. Verify what the JVM detected with `-Xlog:os+container=trace` and `jcmd 1 VM.flags` in the running container before tuning anything else.
8. On JDK 25 consider `-XX:+UseCompactObjectHeaders` after a load test; on JDK 27 it is already the default (JEP 534).

## Why

**Rule 1.** Without heap options the JVM caps the heap at `MaxRAMPercentage` of the memory it detects, and the [JDK 25 `java` reference](https://docs.oracle.com/en/java/javase/25/docs/specs/man/java.html) gives the default as 25 percent (initial heap `InitialRAMPercentage` 1.5625%, and `MinRAMPercentage` 50% for small heaps of about 125 MB). The good example measures it: a `--memory=1g` container gets a 256 MiB heap by default and 768 MiB with `MaxRAMPercentage=75`. The default suits a desktop shared with other programs. A container runs one process, so 25% means paying for four times the heap you get. Why 75 and not 95: the heap is not the only memory the JVM uses. Metaspace grows with the number of loaded classes; the code cache reserves up to 240 MB (`ReservedCodeCacheSize`, tiered compilation); each platform thread reserves a 1 MB stack on Linux x64 (`-Xss`, 2 MB on AArch64); direct buffers (`java.nio`, Netty) live outside the heap and the JVM picks their limit itself unless `-XX:MaxDirectMemorySize` is set. 75% leaves 256 MiB of a 1 GiB pod for all of that; stacks and the code cache are reserved up front but committed as they are used, so the starting point holds for most services, and a measurement tells you when it does not. For a limit under 1 GiB, or a service with heavy direct-buffer or native use, measure non-heap memory with `-XX:NativeMemoryTracking=summary` and `jcmd <pid> VM.native_memory` (a 5 to 10% slowdown per the [troubleshooting guide](https://docs.oracle.com/en/java/javase/25/troubleshoot/diagnostic-tools.html), so in a test, not always on) and set the percentage to what is left. `-Xmx` is wrong in a container because it is a constant: it ignores the limit, a smaller pod gets OOM-killed by the kernel (the JVM thinks it still has room, so there is no `OutOfMemoryError` and no heap dump), and a larger pod wastes the difference. A percentage follows every resize. Set the initial heap with the same percentage: the container's memory is fixed and paid for, and G1 started small spends the first minutes under load growing the heap. Oracle's G1 guide gives the same advice for consistent pauses: "set the options -Xms and -Xmx to the same value" ([G1 tuning](https://docs.oracle.com/en/java/javase/25/gctuning/garbage-first-garbage-collector-tuning.html)); `InitialRAMPercentage` is that advice as a percentage. Measured in benchmarks/orders-api (TUNING.md), 2 vCPU and 1 GiB: with only `MaxRAMPercentage`, JFR showed the heap starting at 16 MB (`jdk.GCHeapConfiguration`), G1 kept it near 150-180 MB under load, and young pauses reached 56-62 ms (concurrent-cycle pauses 81 ms); with `InitialRAMPercentage=75` the heap stayed at 768 MB, a whole run had 30 collections instead of 235, and steady-state pauses were 5-25 ms. The cost is resident memory: peak container memory went from 430-530 MiB to about 700 MiB of the 1 GiB limit, which the limit already reserved.

**Rule 2.** The `java` reference describes `UseContainerSupport` as Linux-only, on by default, and the source of "the amount of memory and number of processors that are available to a Java process running in docker containers". `-XX:-UseContainerSupport` makes the JVM size itself from the node, so a 1 GiB pod on a 64 GiB node gets a 16 GiB heap. `MaxRAM` used to cap the detected memory at 128 GB; its default was removed in JDK 26 and the flag is obsolete in JDK 27, which prints "Ignoring option MaxRAM; support was removed in 27.0" ([JDK-8369347](https://bugs.openjdk.org/browse/JDK-8369347)). The same work removed the `InitialRAMPercentage` default on JDK 27 (it prints 0), so do not rely on any initial-heap default there; rule 1's explicit `InitialRAMPercentage` makes JDK 25 and 27 start with the same heap.

**Rule 3.** A Kubernetes CPU limit becomes a CFS quota, and the JVM turns the quota into `Runtime.availableProcessors()`, rounded up: `--cpus=1.5` gives 2 in the official Temurin image. CPU requests (cgroup shares) are ignored since JDK 19 ([JDK-8281181](https://bugs.openjdk.org/browse/JDK-8281181)), so a pod with only a request sees every core of the node, and G1, the JIT compiler and `ForkJoinPool.commonPool()` all size their threads for 64 CPUs that the pod cannot use. `ActiveProcessorCount` exists, per the `java` reference, for "partitioning CPU resources when running multiple Java processes in docker containers"; setting it above the limit adds threads that only contend for the quota and get throttled. Set memory request equal to the limit: memory is not compressible, and a pod scheduled on its request can be OOM-killed when the node fills up even if the JVM is within its own limit.

**Rule 4.** The [JDK 25 GC tuning guide](https://docs.oracle.com/en/java/javase/25/gctuning/ergonomics.html) chooses "Garbage-First (G1) Collector on server-class machines, Serial Collector otherwise", where server-class means "two or more processors and physical memory larger than or equal to 1792 MB", detected from the container. The good example shows it: on JDK 25, a `--cpus=1 --memory=1g` container runs Serial (`Copy`, `MarkSweepCompact`); on JDK 27 the same container runs G1, because [JEP 523](https://openjdk.org/jeps/523) made G1 the default in all environments. A service that moves from a 2-CPU to a 1-CPU pod on JDK 25 silently changes collector, with Serial's single-threaded full pauses. Naming G1 makes JDK 25 and 27 behave the same. Choose ZGC (`-XX:+UseZGC`) only for large heaps with a pause-time target, and only after measuring; adaptive heap sizing for ZGC is still only targeted for JDK 28 ([JEP 546](https://openjdk.org/jeps/546)). Measured in benchmarks/orders-api (TUNING.md) at 2 vCPU and 1 GiB with the same fixed heap: generational ZGC used as much CPU per request as G1 (0.371 against 0.358-0.364 ms), gave no fewer errors at the knee, and took the container to about 950 MiB of its 1 GiB limit, so G1 stays at that size.

**Rule 5.** `JAVA_TOOL_OPTIONS` is read by the JVM itself (it prints "Picked up JAVA_TOOL_OPTIONS" to stderr), so it applies to `java -jar`, to Spring Boot buildpack images, and to every `jcmd` or child JVM in the container, with no shell involved. `JAVA_OPTS` is only a convention of shell scripts: it works only if a shell expands it. The shell form `ENTRYPOINT java $JAVA_OPTS -jar app.jar` runs `/bin/sh -c`, which becomes PID 1 and does not forward `SIGTERM`, so the JVM never runs its shutdown hooks and Kubernetes kills it after the grace period. The exec form makes `java` PID 1. `JDK_JAVA_OPTIONS` is the launcher-only alternative; it is ignored by tools other than `java`.

**Rule 6.** The [official Temurin image docs](https://github.com/docker-library/docs/blob/master/eclipse-temurin/content.md) state that JRE images exist for every version and recommend a `jlink` runtime for smaller images. A JRE image has no `javac` or `jshell`, which is less to patch. The image runs as root unless you add `USER`; a fixed numeric UID (`10001`) satisfies a Kubernetes `runAsNonRoot` policy, which cannot check a user name. The same docs note that a read-only root filesystem needs a writable `/tmp`.

**Rule 7.** Every other setting is a guess until you see what the JVM saw. `-Xlog:os+container=trace` is the "maximum logging of container information" the `java` reference names: the cgroup version, the memory limit and the CPU quota as the JVM read them. `jcmd 1 VM.flags` shows the final `MaxHeapSize` and the selected GC. The good example does the same check in code, comparing `MaxHeapSize` with `OperatingSystemMXBean.getTotalMemorySize()`, which is container-aware.

**Rule 8.** [JEP 519](https://openjdk.org/jeps/519) made compact object headers a product feature in JDK 25, off by default; [JEP 534](https://openjdk.org/jeps/534) turned them on by default in JDK 27. Headers shrink from 96 to 64 bits; the JEPs report 22% less heap and 8% less CPU on SPECjbb2015 in one setting. On JDK 25 it is one flag, but it is still opt-in, so enable it after a load test rather than by default. On JDK 27, `-XX:-UseCompactObjectHeaders` is the way back if something breaks.

The cost: 75% is a default, not a law. A service with thousands of platform threads or large direct buffers needs a lower percentage, and only a native-memory measurement tells you how much lower.

## Good example

See [`examples/good/`](examples/good/).

```dockerfile
FROM eclipse-temurin:25-jre
RUN useradd --system --uid 10001 --no-create-home app
USER 10001
ENV JAVA_TOOL_OPTIONS="-XX:InitialRAMPercentage=75 -XX:MaxRAMPercentage=75 -XX:+UseG1GC"
ENTRYPOINT ["java", "-jar", "app.jar"]
```

```java
var os = ManagementFactory.getPlatformMXBean(OperatingSystemMXBean.class);
long detected = os.getTotalMemorySize();
var hotspot = ManagementFactory.getPlatformMXBean(HotSpotDiagnosticMXBean.class);
long maxHeapSize = Long.parseLong(hotspot.getVMOption("MaxHeapSize").getValue());
long initialHeapSize = Long.parseLong(hotspot.getVMOption("InitialHeapSize").getValue());
long expected = (long) (detected * expectedPercent / 100);
```

`Main.java` starts two child JVMs with the same JDK, one with no options and one with the Dockerfile's `JAVA_TOOL_OPTIONS`, and each child asserts that its `MaxHeapSize` is the expected percentage of the memory the JVM detected (25 and 75), that the tuned one runs G1 with `InitialHeapSize` equal to `MaxHeapSize`, and prints the CPUs and the initial heap it saw. Run it with `docker run --memory=1g --cpus=1` to see 256 MiB versus 768 MiB, and Serial versus G1 on JDK 25.

## Bad example

See [`examples/bad/`](examples/bad/).

```dockerfile
FROM eclipse-temurin:25
ENV JAVA_OPTS="-Xmx2g -XX:-UseContainerSupport -XX:ActiveProcessorCount=16"
ENTRYPOINT java $JAVA_OPTS -jar /app.jar
```

```java
// MISTAKE: /proc/meminfo shows the node's memory, not the cgroup limit. On a 64 GiB node
long hostBytes = readMemTotalKb() * 1024;
ExecutorService cpuPool = Executors.newFixedThreadPool(32);
```

Each line is a habit carried over from VMs: a heap size from an old host, container detection switched off "to be safe", a shell entrypoint that swallows `SIGTERM`, and application code that sizes itself from the node. Ask the JVM instead: `Runtime.getRuntime().availableProcessors()` and `OperatingSystemMXBean.getTotalMemorySize()` already report the container's limits.

## Checklist

When reviewing a Dockerfile, manifest or startup script under this skill, confirm:

- [ ] No `-Xmx`, `-Xms` or `-XX:MaxHeapSize` appears; the heap comes from `-XX:MaxRAMPercentage`, with `-XX:InitialRAMPercentage` equal to it under G1.
- [ ] `MaxRAMPercentage` is 75, or a lower value backed by a native-memory measurement written next to it.
- [ ] `-XX:-UseContainerSupport` and `-XX:MaxRAM` are absent, and `ActiveProcessorCount` is absent or justified by several JVMs in one container.
- [ ] The pod has a CPU limit and a memory limit, with the memory request equal to the limit.
- [ ] The collector is named (`-XX:+UseG1GC` unless a measurement chose another).
- [ ] Options are in `JAVA_TOOL_OPTIONS` and the `ENTRYPOINT` is exec form with `java` as PID 1.
- [ ] The runtime image is a JRE or `jlink` image and `USER` is a numeric non-root UID.
- [ ] Application code reads CPUs and memory from `Runtime` or `OperatingSystemMXBean`, never from `/proc/meminfo`, `/proc/cpuinfo` or a constant.
- [ ] Someone ran `-Xlog:os+container=trace` or `jcmd 1 VM.flags` in the real container and the detected limits match the manifest.

## When not to apply

- Several JVMs in one container (a sidecar JVM, a test harness): split the memory explicitly with a lower `MaxRAMPercentage` each, and use `ActiveProcessorCount` to partition CPUs; that is the case the flag exists for.
- A container of 512 MiB or less: the remaining 25% (128 MiB) is often too little for metaspace, code cache and stacks, so measure with NMT and set a lower percentage from the result, or give the pod more memory.
- A GraalVM native image is not a HotSpot JVM: these flags do not apply; see `fast-startup-aot-cache` for when to choose it.
- Outside Linux containers (Windows containers, a bare VM where the JVM is the only process), container detection does not apply; on a dedicated VM `-Xmx` from a measured budget is fine.
- A latency-critical service with a measured pause problem and a large heap may choose ZGC instead of G1; name it explicitly the same way, and budget the extra memory it uses.
- A JVM that shares its memory with other processes (a VM or host rather than a fixed-size container): leave `InitialRAMPercentage` low so the heap starts small and grows only when needed, and accept the resizing pauses.
