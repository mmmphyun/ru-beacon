plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    application
}

application {
    mainClass.set("com.rubeacon.bot.RuBeaconBotKt")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

val kordVersion = "0.14.0"
val testcontainersVersion = "1.20.1"

dependencies {
    implementation(project(":common"))
    implementation("dev.kord:kord-core:$kordVersion")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.0")
    implementation("redis.clients:jedis:5.1.2")
    implementation("ch.qos.logback:logback-classic:1.5.3")

    // Test
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.0")
    testImplementation("org.testcontainers:testcontainers:$testcontainersVersion")
    testImplementation("org.testcontainers:junit-jupiter:$testcontainersVersion")
}

tasks.withType<Test> {
    environment("DOCKER_HOST", "npipe:////./pipe/docker_engine")
    environment("DOCKER_API_VERSION", "1.44")
    environment("TESTCONTAINERS_RYUK_DISABLED", "true")
    systemProperty("api.version", "1.44")
    systemProperty("docker.version", "1.44")
    useJUnitPlatform()
}
