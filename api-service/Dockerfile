# Stage 1: Build
FROM eclipse-temurin:21-jdk-jammy AS builder
WORKDIR /workspace

COPY gradlew gradlew.bat settings.gradle.kts build.gradle.kts ./
COPY gradle gradle
COPY common common
COPY api-service api-service

RUN ./gradlew :api-service:installDist --no-daemon -x test

# Stage 2: Runtime
FROM eclipse-temurin:21-jre-jammy
RUN groupadd -r appgroup && useradd -r -g appgroup -u 10001 appuser

WORKDIR /app
COPY --from=builder --chown=appuser:appgroup /workspace/api-service/build/install/api-service ./

USER appuser
EXPOSE 8080
ENV PORT=8080

ENTRYPOINT ["./bin/api-service"]
