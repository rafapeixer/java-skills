FROM maven:3.9-eclipse-temurin-25 AS build
WORKDIR /build
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src ./src
RUN mvn -B package

FROM eclipse-temurin:25-jre
RUN useradd --system --uid 10001 --no-create-home app
WORKDIR /app
COPY --from=build /build/target/orders-api.jar /tmp/orders-api.jar
# The AOT cache only works with the extracted layout (application jar + lib/).
RUN java -Djarmode=tools -jar /tmp/orders-api.jar extract --destination /app && rm /tmp/orders-api.jar
# Heap fixed at 75% of the container limit (Xms = Xmx): no heap resizing under load.
# G1 named because JDK 25 picks Serial below 1792 MB. Exit on OOM so the orchestrator
# replaces the container instead of limping on.
ENV JAVA_TOOL_OPTIONS="-XX:InitialRAMPercentage=75 -XX:MaxRAMPercentage=75 -XX:+UseG1GC -XX:+ExitOnOutOfMemoryError"
# Training run (JEP 483/514): start the Spring context once and record loaded and linked
# classes into app.aot. No database at build time, so the pool starts without connecting.
# MaxRAM mimics the 1 GiB container so the heap layout matches production.
RUN DATABASE_URL=postgres://training@127.0.0.1:1/training PRICING_URL=http://127.0.0.1:1 \
    ORDERS_DB_INITIALIZATION_FAIL_TIMEOUT_MS=-1 \
    java -XX:MaxRAM=1g -XX:AOTCacheOutput=app.aot -Dspring.context.exit=onRefresh -jar orders-api.jar
USER 10001
EXPOSE 8080
# Exec form: java is PID 1 and receives SIGTERM directly for graceful shutdown.
ENTRYPOINT ["java", "-XX:AOTCache=app.aot", "-jar", "/app/orders-api.jar"]
