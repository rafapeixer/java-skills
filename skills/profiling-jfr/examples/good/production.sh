#!/usr/bin/env bash
# How the recording in Main.java runs in production. Not executed by the tests.
set -euo pipefail

# 1. Always on, from the first second: default.jfc (low overhead, built for continuous use),
#    bounded by age and size on disk, written out if the JVM exits. On the java command line
#    (or the ENTRYPOINT), not in JAVA_TOOL_OPTIONS: every jcmd would start a recording too.
java -XX:StartFlightRecording=name=always-on,settings=default,maxage=6h,maxsize=512m,dumponexit=true,filename=/tmp/app-%p.jfr -jar app.jar &
pid=$!

# 2. When something goes wrong, dump the last 15 minutes without stopping the recording.
#    (The eclipse-temurin JRE image has no jcmd: run it from a JDK image in the same process namespace.)
jcmd "$pid" JFR.dump name=always-on begin=-15m filename=/tmp/incident.jfr

# 3. A short, detailed recording for a specific question, then back to always-on only.
jcmd "$pid" JFR.start name=detail settings=profile duration=2m filename=/tmp/detail.jfr

# 4. Read it where it is: summary first, then the views for the question you have.
jfr summary /tmp/incident.jfr
jfr view hot-methods /tmp/incident.jfr
jfr view allocation-by-site /tmp/incident.jfr
jfr view contention-by-site /tmp/incident.jfr
jfr view pinned-threads /tmp/incident.jfr
jfr view gc-pauses /tmp/incident.jfr
jfr print --events jdk.GCHeapConfiguration /tmp/incident.jfr
jfr print --events jdk.VirtualThreadPinned --stack-depth 20 /tmp/incident.jfr

# 5. JDK 25+ on Linux: CPU-time sampling (experimental, JEP 509), off in default.jfc.
#    Unlike jdk.ExecutionSample it also sees native frames (socket writes, syscalls).
jcmd "$pid" JFR.start name=cpu settings=default jdk.CPUTimeSample#enabled=true duration=2m filename=/tmp/cpu.jfr
jfr view cpu-time-hot-methods /tmp/cpu.jfr
