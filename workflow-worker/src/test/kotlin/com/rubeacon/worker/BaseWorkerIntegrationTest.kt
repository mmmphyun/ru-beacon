package com.rubeacon.worker

import com.rubeacon.worker.db.AttendanceQuotas
import com.rubeacon.worker.db.AuditLogs
import com.rubeacon.worker.db.RewardReservations
import com.rubeacon.worker.db.Tenants
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.deleteAll
import org.jetbrains.exposed.sql.insertIgnore
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.BeforeAll
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import redis.clients.jedis.JedisPooled
import javax.sql.DataSource

class KGenericContainer(imageName: String) : GenericContainer<KGenericContainer>(DockerImageName.parse(imageName))

/**
 * Worker 통합 테스트용 PostgreSQL 16 & Redis 7 Testcontainers 싱글톤 하네스.
 */
@Testcontainers
abstract class BaseWorkerIntegrationTest {

    companion object {
        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("rubeacon_test")
            .withUsername("test")
            .withPassword("test")

        @Container
        @JvmStatic
        val redis: KGenericContainer = KGenericContainer("redis:7-alpine")
            .withExposedPorts(6379)

        lateinit var dataSource: DataSource
        lateinit var database: Database
        lateinit var jedis: JedisPooled

        @BeforeAll
        @JvmStatic
        fun startContainers() {
            if (!postgres.isRunning) postgres.start()
            if (!redis.isRunning) redis.start()

            dataSource = createDataSource(postgres.jdbcUrl, postgres.username, postgres.password)
            runFlyway(dataSource)
            database = Database.connect(dataSource)
            org.jetbrains.exposed.sql.transactions.TransactionManager.defaultDatabase = database
            jedis = JedisPooled(redis.host, redis.getMappedPort(6379))
        }

        private fun createDataSource(url: String, user: String, pass: String): DataSource {
            val config = HikariConfig().apply {
                jdbcUrl = url
                username = user
                password = pass
                driverClassName = "org.postgresql.Driver"
                maximumPoolSize = 10
            }
            return HikariDataSource(config)
        }

        private fun runFlyway(ds: DataSource) {
            Flyway.configure()
                .dataSource(ds)
                .load()
                .migrate()
        }

        fun ensureTenant(tenantId: String, name: String = "Test Tenant") {
            transaction(database) {
                Tenants.insertIgnore {
                    it[id] = tenantId
                    it[Tenants.name] = name
                    it[discordGuildId] = "guild_${tenantId.take(16)}"
                }
            }
        }

        fun cleanupData() {
            transaction(database) {
                AuditLogs.deleteAll()
                RewardReservations.deleteAll()
                AttendanceQuotas.deleteAll()
                Tenants.deleteAll()
            }
        }
    }
}
