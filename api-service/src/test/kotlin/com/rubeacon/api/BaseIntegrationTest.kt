package com.rubeacon.api

import org.jetbrains.exposed.sql.Database
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
 * PostgreSQL 16 및 Redis 7 Testcontainers 싱글톤 통합 테스트 베이스 클래스.
 * docs/TESTING_STRATEGY.md §2.2 규격을 따름.
 */
@Testcontainers
abstract class BaseIntegrationTest {
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
    }

    @org.junit.jupiter.api.BeforeEach
    fun resetDevMockAuthProperty() {
        System.setProperty("DEV_MOCK_AUTH", "true")
    }
}
