# Multi-arch base images: builds natively for arm64 (t4g / Graviton) and amd64.
FROM maven:3.9-eclipse-temurin-25 AS build
WORKDIR /src
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src ./src
RUN mvn -B -q -DskipTests package

FROM eclipse-temurin:25-jre
RUN groupadd --system app && useradd --system --gid app --no-create-home app
WORKDIR /app
COPY --from=build /src/target/orders-api.jar /app/orders-api.jar
USER app

# t4g.micro has 1 GiB shared with the OS and container runtime: cap the heap at
# half of it and leave room for metaspace, code cache and thread stacks.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=50 -XX:+ExitOnOutOfMemoryError"
EXPOSE 8080

# Exec form so the JVM is PID 1 and receives SIGTERM directly (graceful shutdown).
ENTRYPOINT ["java", "-jar", "/app/orders-api.jar"]
