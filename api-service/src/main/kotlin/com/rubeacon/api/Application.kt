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
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import kotlinx.serialization.json.Json
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.sql.Database
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
    redisPublisher: RedisEventPublisher = RedisEventPublisher(jedis)
) {
    org.jetbrains.exposed.sql.transactions.TransactionManager.defaultDatabase = database

    install(WebSockets)

    install(ContentNegotiation) {
        json(Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        })
    }

    routing {
        minecraftWebSocketRoutes(authService, sessionRegistry, redisPublisher)
        accountLinkRoutes(accountLinkService)
        workflowRoutes()
        tenantRoutes(authService)
    }
}
