# --- Stage 1: Build ---
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
# Copy the pom and source code
COPY audio-watermark/pom.xml .
COPY audio-watermark/src ./src
# Build the jar
RUN mvn clean package -DskipTests

# --- Stage 2: Runtime ---
FROM eclipse-temurin:21-jre
WORKDIR /app

# Install ffmpeg (needed to decode MP3/AAC/M4A/Opus uploads before watermark detection)
RUN apt-get update && \
    apt-get install -y --no-install-recommends ffmpeg && \
    rm -rf /var/lib/apt/lists/*

# Copy the jar from the build stage
COPY --from=build /app/target/*.jar app.jar
# The port is handled by the ${PORT} env var in your properties
EXPOSE 8081
ENTRYPOINT ["java", "-jar", "app.jar"]