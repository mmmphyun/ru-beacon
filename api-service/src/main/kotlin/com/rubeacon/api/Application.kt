package com.rubeacon.api

import com.rubeacon.api.redis.RedisEventPublisher
import com.rubeacon.api.routes.accountLinkRoutes
import com.rubeacon.api.routes.minecraftWebSocketRoutes
import com.rubeacon.api.routes.tenantRoutes
import com.rubeacon.api.routes.workflowRoutes
import com.rubeacon.api.service.AccountLinkService
import com.rubeacon.api.service.InstanceAuthService
import com.rubeacon.api.service.SessionRegistry
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.metrics.micrometer.MicrometerMetrics
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.micrometer.core.instrument.binder.jvm.ClassLoaderMetrics
import io.micrometer.core.instrument.binder.jvm.JvmGcMetrics
import io.micrometer.core.instrument.binder.jvm.JvmMemoryMetrics
import io.micrometer.core.instrument.binder.jvm.JvmThreadMetrics
import io.micrometer.core.instrument.binder.system.ProcessorMetrics
import io.micrometer.prometheus.PrometheusConfig
import io.micrometer.prometheus.PrometheusMeterRegistry
import kotlinx.serialization.json.Json
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.sql.Database
import org.slf4j.LoggerFactory
import redis.clients.jedis.JedisPooled
import javax.sql.DataSource

/**
 * HikariCP 커넥션 풀을 생성함.
 */
fun createDataSource(jdbcUrl: String, user: String, pass: String): DataSource {
    val config = HikariConfig().apply {
        this.jdbcUrl = jdbcUrl
        this.username = user
        this.password = pass
        this.maximumPoolSize = 10
        this.minimumIdle = 2
        this.driverClassName = "org.postgresql.Driver"
    }
    return HikariDataSource(config)
}

/**
 * Flyway V1 마이그레이션을 실행함.
 */
fun runFlyway(dataSource: DataSource) {
    Flyway.configure()
        .dataSource(dataSource)
        .locations("classpath:db/migration")
        .baselineOnMigrate(true)
        .load()
        .migrate()
}

/**
 * Ktor API Service 메인 모듈.
 */
fun Application.module(
    database: Database,
    jedis: JedisPooled,
    authService: InstanceAuthService = InstanceAuthService(),
    sessionRegistry: SessionRegistry = SessionRegistry(),
    accountLinkService: AccountLinkService = AccountLinkService(),
    redisPublisher: RedisEventPublisher = RedisEventPublisher(jedis),
    meterRegistry: PrometheusMeterRegistry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT).apply {
        ClassLoaderMetrics().bindTo(this)
        JvmMemoryMetrics().bindTo(this)
        JvmGcMetrics().bindTo(this)
        ProcessorMetrics().bindTo(this)
        JvmThreadMetrics().bindTo(this)
    }
) {
    org.jetbrains.exposed.sql.transactions.TransactionManager.defaultDatabase = database

    install(MicrometerMetrics) {
        registry = meterRegistry
    }

    install(WebSockets)

    install(ContentNegotiation) {
        json(Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        })
    }

    routing {
        get("/healthz") {
            call.respondText("OK")
        }
        get("/metrics") {
            call.respond(meterRegistry.scrape())
        }
        minecraftWebSocketRoutes(authService, sessionRegistry, redisPublisher)
        accountLinkRoutes(accountLinkService)
        workflowRoutes()
        tenantRoutes(authService)
    }
}

/**
 * api-service 독립 실행 엔트리포인트.
 */
fun main() {
    val log = LoggerFactory.getLogger("com.rubeacon.api.Main")
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8080
    val jdbcUrl = System.getenv("DATABASE_URL") ?: "jdbc:postgresql://localhost:5432/rubeacon"
    val dbUser = System.getenv("DATABASE_USER") ?: "postgres"
    val dbPass = System.getenv("DATABASE_PASSWORD") ?: "postgres"
    val redisHost = System.getenv("REDIS_HOST") ?: "localhost"
    val redisPort = System.getenv("REDIS_PORT")?.toIntOrNull() ?: 6379

    log.info("API Service 기동 중... (Port: {}, DB: {}, Redis: {}:{})", port, jdbcUrl, redisHost, redisPort)

    val dataSource = createDataSource(jdbcUrl, dbUser, dbPass)
    runFlyway(dataSource)
    val database = Database.connect(dataSource)
    val jedis = JedisPooled(redisHost, redisPort)

    embeddedServer(Netty, port = port, host = "0.0.0.0") {
        module(database, jedis)
    }.start(wait = true)
}

