plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    application
}

application {
    mainClass.set("com.rubeacon.worker.WorkerMainKt")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

val ktorVersion = "2.3.10"
val exposedVersion = "0.50.1"
val testcontainersVersion = "1.20.1"

dependencies {
    implementation(project(":common"))
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.0")

    // Ktor Server for Health & Observability Metrics
    implementation("io.ktor:ktor-server-core:$ktorVersion")
    implementation("io.ktor:ktor-server-netty:$ktorVersion")
    implementation("io.ktor:ktor-server-metrics-micrometer:$ktorVersion")
    implementation("io.micrometer:micrometer-registry-prometheus:1.12.5")
    implementation("ch.qos.logback:logback-classic:1.5.3")

    // Database & Migration
    implementation("org.jetbrains.exposed:exposed-core:$exposedVersion")
    implementation("org.jetbrains.exposed:exposed-jdbc:$exposedVersion")
    implementation("org.jetbrains.exposed:exposed-java-time:$exposedVersion")
    implementation("org.jetbrains.exposed:exposed-json:$exposedVersion")
    implementation("org.postgresql:postgresql:42.7.3")
    implementation("com.zaxxer:HikariCP:5.1.0")
    implementation("org.flywaydb:flyway-core:10.11.0")
    implementation("org.flywaydb:flyway-database-postgresql:10.11.0")

    // Redis
    implementation("redis.clients:jedis:5.1.2")

    // In-Memory Cache (Caffeine)
    implementation("com.github.ben-manes.caffeine:caffeine:3.1.8")

    // Test
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.0")
    testImplementation("io.ktor:ktor-server-test-host:$ktorVersion")

    // Testcontainers
    testImplementation("org.testcontainers:testcontainers:$testcontainersVersion")
    testImplementation("org.testcontainers:junit-jupiter:$testcontainersVersion")
    testImplementation("org.testcontainers:postgresql:$testcontainersVersion")
    testImplementation("ch.qos.logback:logback-classic:1.5.3")
}

tasks.withType<Test> {
    environment("DOCKER_HOST", "npipe:////./pipe/docker_engine")
    environment("DOCKER_API_VERSION", "1.44")
    environment("TESTCONTAINERS_RYUK_DISABLED", "true")
    systemProperty("api.version", "1.44")
    systemProperty("docker.version", "1.44")
    useJUnitPlatform()
}

