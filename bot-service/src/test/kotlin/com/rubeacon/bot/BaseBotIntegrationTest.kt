package com.rubeacon.bot

import org.junit.jupiter.api.BeforeAll
import org.testcontainers.containers.GenericContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import redis.clients.jedis.JedisPooled

class KGenericContainer(imageName: String) : GenericContainer<KGenericContainer>(DockerImageName.parse(imageName))

@Testcontainers
abstract class BaseBotIntegrationTest {

    companion object {
        @Container
        @JvmStatic
        val redis: KGenericContainer = KGenericContainer("redis:7-alpine")
            .withExposedPorts(6379)

        lateinit var jedis: JedisPooled

        @BeforeAll
        @JvmStatic
        fun startContainers() {
            if (!redis.isRunning) redis.start()
            jedis = JedisPooled(redis.host, redis.getMappedPort(6379))
        }
    }
}
