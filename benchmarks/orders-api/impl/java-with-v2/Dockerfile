FROM maven:3-eclipse-temurin-25 AS build
WORKDIR /src
COPY pom.xml .
RUN --mount=type=cache,target=/root/.m2 mvn -B dependency:go-offline
COPY src ./src
RUN --mount=type=cache,target=/root/.m2 mvn -B package

FROM eclipse-temurin:25-jre
RUN useradd --system --uid 10001 --no-create-home app
WORKDIR /app
COPY --from=build /src/target/orders-api.jar /app/orders-api.jar
USER 10001
# 1 GiB / 2 vCPU: heap fixed at 75% (768 MiB), the rest for metaspace, code cache, stacks and
# buffers. G1 named explicitly: JDK 25 picks Serial below 1792 MB.
ENV JAVA_TOOL_OPTIONS="-XX:InitialRAMPercentage=75 -XX:MaxRAMPercentage=75 -XX:+UseG1GC -XX:+ExitOnOutOfMemoryError"
EXPOSE 8080
# Exec form: java is PID 1 and receives SIGTERM, which starts Spring's graceful shutdown.
ENTRYPOINT ["java", "-jar", "/app/orders-api.jar"]
