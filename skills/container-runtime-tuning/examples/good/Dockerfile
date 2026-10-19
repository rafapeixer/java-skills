# Runtime image for a Spring Boot (or any fat-jar) service. Built after `mvn package`.
# A JRE image: no compiler or jshell in production.
FROM eclipse-temurin:25-jre

# The image runs as root by default; create an unprivileged user with a fixed UID.
RUN useradd --system --uid 10001 --no-create-home app
WORKDIR /app
COPY --chown=10001:10001 target/app.jar app.jar
USER 10001

# Heap = 75% of the container memory limit. The other 25% is for metaspace, the code
# cache (240 MB reserved), 1 MB of stack per platform thread and direct buffers.
# G1 by name: on JDK 25 a container with 1 CPU or less than 1792 MB silently gets Serial.
# No -Xmx: it would ignore the limit and drift from it on the next resize.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+UseG1GC"

# Exec form: java is PID 1 and receives SIGTERM, so graceful shutdown runs.
ENTRYPOINT ["java", "-jar", "app.jar"]

# Verify what the JVM detected, in the running container:
#   docker run --memory=1g --cpus=2 IMAGE java -Xlog:os+container=trace -version
#   docker exec CONTAINER jcmd 1 VM.flags
