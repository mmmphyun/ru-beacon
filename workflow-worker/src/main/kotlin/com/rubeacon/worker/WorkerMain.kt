package com.rubeacon.worker

import com.rubeacon.common.redis.RedisNamespaces
import com.rubeacon.worker.consumer.RedisStreamsConsumer
import com.rubeacon.worker.db.AuditLogger
import com.rubeacon.worker.db.Tenants
import com.rubeacon.worker.db.WorkflowVersions
import com.rubeacon.worker.engine.AttendanceReservationExecutor
import com.rubeacon.worker.engine.ConditionBranchExecutor
import com.rubeacon.worker.engine.DagWorkflowDispatcher
import com.rubeacon.worker.engine.DiscordSendMessageExecutor
import com.rubeacon.worker.engine.MinecraftDispatchCommandExecutor
import com.rubeacon.worker.engine.WorkflowDefinition
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.metrics.micrometer.MicrometerMetrics
import io.ktor.server.netty.Netty
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.micrometer.core.instrument.binder.jvm.ClassLoaderMetrics
import io.micrometer.core.instrument.binder.jvm.JvmGcMetrics
import io.micrometer.core.instrument.binder.jvm.JvmMemoryMetrics
import io.micrometer.core.instrument.binder.jvm.JvmThreadMetrics
import io.micrometer.core.instrument.binder.system.ProcessorMetrics
import io.micrometer.prometheus.PrometheusConfig
import io.micrometer.prometheus.PrometheusMeterRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory
import redis.clients.jedis.JedisPooled
import javax.sql.DataSource

fun createWorkerDataSource(url: String, user: String, pass: String): DataSource {
    val config = HikariConfig().apply {
        jdbcUrl = url
        username = user
        password = pass
        driverClassName = "org.postgresql.Driver"
        maximumPoolSize = 10
        minimumIdle = 2
    }
    return HikariDataSource(config)
}

fun runWorkerFlyway(ds: DataSource) {
    Flyway.configure()
        .dataSource(ds)
        .load()
        .migrate()
}

/**
 * workflow-worker 독립 실행 엔트리포인트.
 */
fun main() {
    val log = LoggerFactory.getLogger("com.rubeacon.worker.WorkerMain")
    val metricsPort = System.getenv("METRICS_PORT")?.toIntOrNull() ?: 8081
    val jdbcUrl = System.getenv("DATABASE_URL") ?: "jdbc:postgresql://localhost:5432/rubeacon"
    val dbUser = System.getenv("DATABASE_USER") ?: "postgres"
    val dbPass = System.getenv("DATABASE_PASSWORD") ?: "postgres"
    val redisHost = System.getenv("REDIS_HOST") ?: "localhost"
    val redisPort = System.getenv("REDIS_PORT")?.toIntOrNull() ?: 6379

    log.info("Workflow Worker 기동 중... (MetricsPort: {}, DB: {}, Redis: {}:{})", metricsPort, jdbcUrl, redisHost, redisPort)

    val dataSource = createWorkerDataSource(jdbcUrl, dbUser, dbPass)
    runWorkerFlyway(dataSource)
    val database = Database.connect(dataSource)
    val jedis = JedisPooled(redisHost, redisPort)

    val auditLogger = AuditLogger()
    val mcCommandExecutor = MinecraftDispatchCommandExecutor(jedis)
    val discordMessageExecutor = DiscordSendMessageExecutor(jedis)
    val conditionExecutor = ConditionBranchExecutor()
    val attendanceExecutor = AttendanceReservationExecutor(jedis)

    val executors = listOf(
        mcCommandExecutor,
        discordMessageExecutor,
        conditionExecutor,
        attendanceExecutor
    ).associateBy { it.supportedNodeType }

    val dispatcher = DagWorkflowDispatcher(executors, auditLogger)
    val json = Json { ignoreUnknownKeys = true }

    val cachedWorkflowLookup = CachedWorkflowLookup(
        database = database,
        json = json,
        maxSize = 10_000,
        expireDuration = java.time.Duration.ofMinutes(5)
    )

    val consumer = RedisStreamsConsumer(jedis, dispatcher, cachedWorkflowLookup::invoke)
    val workerScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    // Metrics & Health Server
    val meterRegistry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT).apply {
        ClassLoaderMetrics().bindTo(this)
        JvmMemoryMetrics().bindTo(this)
        JvmGcMetrics().bindTo(this)
        ProcessorMetrics().bindTo(this)
        JvmThreadMetrics().bindTo(this)
    }

    val server = embeddedServer(Netty, port = metricsPort, host = "0.0.0.0") {
        workerMetricsModule(meterRegistry)
    }

    // 스트림 컨슈머 백그라운드 기동 (다중 테넌트 지원)
    val streamsSupplier: () -> List<String> = {
        val envTenants = System.getenv("TENANT_IDS")?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }
        val tenantIds = if (!envTenants.isNullOrEmpty()) {
            envTenants
        } else {
            val dbTenants = transaction(database) {
                Tenants.selectAll().map { it[Tenants.id] }
            }
            if (dbTenants.isNotEmpty()) dbTenants else listOf("default")
        }
        tenantIds.map { RedisNamespaces.eventsStream(it) }
    }

    log.info("Streams 컨슈머 루프 시작 (다중 테넌트 동적 감지 지원)")
    val consumerJob = consumer.start(workerScope, streamsSupplier = streamsSupplier)

    Runtime.getRuntime().addShutdownHook(Thread {
        log.info("Workflow Worker 안전 종료 중 (Graceful Drain)...")
        runBlocking {
            withTimeoutOrNull(20_000L) {
                consumerJob.cancelAndJoin()
            } ?: log.warn("컨슈머 작업 종료 대기 타임아웃(20s), 강제 취소 진행")
            workerScope.cancel()
        }
        server.stop(1000, 3000)
        jedis.close()
        (dataSource as? AutoCloseable)?.close()
        log.info("Workflow Worker 종료 완료")
    })

    server.start(wait = false)
    log.info("Metrics 서버 기동 완료 (포트: {})", metricsPort)

    // 블로킹 대기
    Thread.currentThread().join()
}

/**
 * workflow-worker 헬스체크 및 Prometheus 메트릭 모듈.
 */
fun Application.workerMetricsModule(meterRegistry: PrometheusMeterRegistry) {
    install(MicrometerMetrics) {
        registry = meterRegistry
    }
    routing {
        get("/healthz") {
            call.respondText("OK")
        }
        get("/metrics") {
            call.respond(meterRegistry.scrape())
        }
    }
}


