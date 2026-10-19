# MISTAKE: a full JDK image as the runtime, running as root (no USER).
FROM eclipse-temurin:25
COPY target/app.jar /app.jar

# MISTAKE: -Xmx hard-coded to a number copied from a VM. In a 1 GiB container the heap
# can grow to 2 GiB and the kernel OOM-kills the pod with no OutOfMemoryError in the logs.
# MISTAKE: -XX:-UseContainerSupport makes the JVM size everything from the node's RAM and CPUs.
# MISTAKE: ActiveProcessorCount=16 on a 2-CPU limit: 16 GC threads fight for 2 CPUs of quota.
ENV JAVA_OPTS="-Xmx2g -XX:-UseContainerSupport -XX:ActiveProcessorCount=16"

# MISTAKE: shell form. sh is PID 1, java never receives SIGTERM, and $JAVA_OPTS is only
# read because of the shell; JAVA_TOOL_OPTIONS would be read by the JVM itself.
ENTRYPOINT java $JAVA_OPTS -jar /app.jar
