# Stage 1: Build
FROM eclipse-temurin:21-jdk-jammy AS builder
WORKDIR /workspace

COPY gradlew gradlew.bat settings.gradle.kts build.gradle.kts ./
COPY gradle gradle
COPY common common
COPY workflow-worker workflow-worker

RUN ./gradlew :workflow-worker:installDist --no-daemon -x test

# Stage 2: Runtime
FROM eclipse-temurin:21-jre-jammy
RUN groupadd -r appgroup && useradd -r -g appgroup -u 10001 appuser

WORKDIR /app
COPY --from=builder --chown=appuser:appgroup /workspace/workflow-worker/build/install/workflow-worker ./

USER appuser
EXPOSE 8081
ENV METRICS_PORT=8081

ENTRYPOINT ["./bin/workflow-worker"]
