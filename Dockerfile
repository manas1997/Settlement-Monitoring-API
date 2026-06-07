# syntax=docker/dockerfile:1

# ---- Build stage -----------------------------------------------------------
FROM eclipse-temurin:21-jdk-jammy AS build
WORKDIR /workspace

# Cache dependencies first (layer is reused unless build scripts change).
COPY gradlew settings.gradle build.gradle gradle.properties ./
COPY gradle ./gradle
RUN chmod +x gradlew && ./gradlew --no-daemon dependencies > /dev/null 2>&1 || true

# Build the application.
COPY src ./src
RUN ./gradlew --no-daemon clean bootJar -x test && \
    cp build/libs/*.jar app.jar

# ---- Runtime stage ---------------------------------------------------------
FROM eclipse-temurin:21-jre-jammy AS runtime
WORKDIR /app

# Run as a non-root user (OWASP: least privilege).
RUN groupadd --system app && useradd --system --gid app app
COPY --from=build /workspace/app.jar /app/app.jar
USER app

EXPOSE 8080
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0 -XX:+UseG1GC"

# Container-native health check hits the actuator liveness probe.
HEALTHCHECK --interval=30s --timeout=3s --start-period=40s --retries=3 \
    CMD wget -qO- http://localhost:8080/actuator/health/liveness || exit 1

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/app.jar"]
