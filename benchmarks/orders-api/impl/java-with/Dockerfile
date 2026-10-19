FROM maven:3.9-eclipse-temurin-25 AS build
WORKDIR /build
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src ./src
RUN mvn -B package

FROM eclipse-temurin:25-jre
RUN useradd --system --uid 10001 --no-create-home app
WORKDIR /app
COPY --from=build /build/target/orders-api.jar /app/orders-api.jar
USER 10001
EXPOSE 8080
# Heap follows the container memory limit; G1 named because JDK 25 picks Serial below
# 1792 MB. Exit on OOM so the orchestrator replaces the container instead of limping on.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+UseG1GC -XX:+ExitOnOutOfMemoryError"
# Exec form: java is PID 1 and receives SIGTERM directly for graceful shutdown.
ENTRYPOINT ["java", "-jar", "/app/orders-api.jar"]
