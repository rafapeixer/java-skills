# syntax=docker/dockerfile:1
FROM maven:3-eclipse-temurin-25 AS build
WORKDIR /src
COPY pom.xml .
COPY src ./src
RUN --mount=type=cache,target=/root/.m2 mvn -B package

FROM eclipse-temurin:25-jre
RUN useradd --system --uid 10001 --no-create-home app
WORKDIR /app
COPY --from=build /src/target/orders-api.jar /app/orders-api.jar
USER 10001
# Heap as a share of the container limit (768 MiB of 1 GiB), fixed from the start. G1 is named
# because JDK 25 picks Serial below 1792 MB, which a t4g.micro container is.
ENV JAVA_TOOL_OPTIONS="-XX:InitialRAMPercentage=75 -XX:MaxRAMPercentage=75 -XX:+UseG1GC"
EXPOSE 8080
# Exec form: java is PID 1 and receives SIGTERM directly.
ENTRYPOINT ["java", "-jar", "/app/orders-api.jar"]
