# MISTAKE: the cache is trained with one JDK and used with another. An AOT cache is
# specific to the JDK release, so the runtime image silently ignores it (no speed-up).
FROM eclipse-temurin:25-jdk AS builder
WORKDIR /build
COPY target/app.jar app.jar
# MISTAKE: training on the fat jar. Spring Boot's cache only has an effect with the
# extracted layout (-Djarmode=tools extract).
# MISTAKE: no -Dspring.context.exit=onRefresh, so the training run starts serving,
# connects to the production database from the build and never exits on its own.
RUN timeout 60 java -XX:AOTCacheOutput=app.aot -jar app.jar || true

FROM eclipse-temurin:27-jre
COPY --from=builder /build/app.jar /build/app.aot /app/
# MISTAKE: AOTMode=on in production: any mismatch (new JDK, one more jar) turns into a
# JVM that refuses to start, a crash loop instead of a slower start.
ENTRYPOINT ["java", "-XX:AOTMode=on", "-XX:AOTCache=/app/app.aot", "-jar", "/app/app.jar"]
