# MISTAKE: JDK 26 is a non-LTS release that stopped getting updates when 27 shipped
# (September 2026); every CVE fixed since then is still open in this image.
FROM eclipse-temurin:26-jre
COPY target/versions-bad-1.0.0.jar /app/app.jar
ENTRYPOINT ["java", "-cp", "/app/app.jar", "com.example.versions.VersionCheck"]
