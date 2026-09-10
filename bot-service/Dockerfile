# Stage 1: Build
FROM eclipse-temurin:21-jdk-jammy AS builder
WORKDIR /workspace

COPY gradlew gradlew.bat settings.gradle.kts build.gradle.kts ./
COPY gradle gradle
COPY common common
COPY bot-service bot-service

RUN ./gradlew :bot-service:installDist --no-daemon -x test

# Stage 2: Runtime
FROM eclipse-temurin:21-jre-jammy
RUN groupadd -r appgroup && useradd -r -g appgroup -u 10001 appuser

WORKDIR /app
COPY --from=builder --chown=appuser:appgroup /workspace/bot-service/build/install/bot-service ./

USER appuser

ENTRYPOINT ["./bin/bot-service"]
