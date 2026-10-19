# Spring Boot 4.1 service with an AOT cache (JDK 25+), following the Spring Boot
# "Dockerfiles" reference. Built after `mvn package` produced target/app.jar.
FROM eclipse-temurin:25-jre AS builder
WORKDIR /builder
COPY target/app.jar application.jar
# The cache only works with the extracted layout, not with the fat jar.
RUN java -Djarmode=tools -jar application.jar extract --layers --destination extracted

FROM eclipse-temurin:25-jre
RUN useradd --system --uid 10001 --no-create-home app
WORKDIR /application
COPY --from=builder /builder/extracted/dependencies/ ./
COPY --from=builder /builder/extracted/spring-boot-loader/ ./
COPY --from=builder /builder/extracted/snapshot-dependencies/ ./
COPY --from=builder /builder/extracted/application/ ./

# Training run in the runtime image, so the cache matches this exact JDK and these jars.
# onRefresh stops after the context starts: no traffic, no scheduled jobs.
# The one-step workflow forks a second JVM with the same heap: give the build 2x the heap.
# There is no database in the build: Hikari fails fast at startup by default (1 ms), so
# only the training run skips the initial connection (-1); production keeps failing fast.
RUN java -XX:AOTCacheOutput=app.aot -Dspring.context.exit=onRefresh -Dspring.datasource.hikari.initialization-fail-timeout=-1 -jar application.jar

USER 10001
ENV JAVA_TOOL_OPTIONS="-XX:InitialRAMPercentage=75 -XX:MaxRAMPercentage=75 -XX:+UseG1GC"
# Default AOTMode (auto): if the cache ever stops matching, the app still starts, only slower.
ENTRYPOINT ["java", "-XX:AOTCache=app.aot", "-jar", "application.jar"]
