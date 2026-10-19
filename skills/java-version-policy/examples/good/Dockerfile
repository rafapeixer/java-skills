# Production runs the floor LTS with its newest update; Dependabot or Renovate bumps
# this tag after each quarterly OpenJDK update (third Tuesday of Jan, Apr, Jul, Oct).
FROM eclipse-temurin:25.0.4.1_1-jre
COPY target/versions-1.0.0.jar /app/app.jar
ENTRYPOINT ["java", "-cp", "/app/app.jar", "com.example.versions.JavaFloor"]
