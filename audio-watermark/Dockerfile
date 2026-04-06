# --- Stage 1: Build ---
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app
# Copy the pom and source code
COPY pom.xml .
COPY src ./src
# Build the jar
RUN mvn clean package -DskipTests

# --- Stage 2: Runtime ---
FROM eclipse-temurin:17-jre
WORKDIR /app
# Copy the jar from the build stage
COPY --from=build /app/target/*.jar app.jar
# The port is handled by the ${PORT} env var in your properties
EXPOSE 8081
ENTRYPOINT ["java", "-jar", "app.jar"]