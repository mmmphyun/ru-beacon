package com.rubeacon.api

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class RateLimitingIntegrationTest : BaseIntegrationTest() {

    @Test
    fun `헬스체크 엔드포인트는 레이트리밋 영향을 받지 않고 통과한다`() = testApplication {
        application {
            module(database, jedis)
        }

        repeat(30) {
            val response = client.get("/healthz")
            assertEquals(HttpStatusCode.OK, response.status)
        }
    }

    @Test
    fun `테넌트 헤더 기반 요청은 버킷 소진 시 429 Too Many Requests가 반환된다`() = testApplication {
        val testTenantId = "tenant-${UUID.randomUUID()}"

        application {
            module(
                database = database,
                jedis = jedis,
                tenantRateLimitCapacity = 3L,
                tenantRateLimitRefillRate = 0.0, // 테스트 중 자동 리필 억제
                startRedisCommandConsumer = false,
                startStaleReaper = false
            )
        }

        // 용량 3개 허용 (초기 버킷 소진)
        repeat(3) {
            val res = client.get("/api/v1/auth/me") {
                header("X-Tenant-Id", testTenantId)
            }
            assertTrue(res.status == HttpStatusCode.Unauthorized || res.status == HttpStatusCode.OK)
            assertEquals("3", res.headers["X-RateLimit-Limit"])
        }

        // 4번째 요청: 한도 초과 429 응답 확인
        val response = client.get("/api/v1/auth/me") {
            header("X-Tenant-Id", testTenantId)
        }

        assertEquals(HttpStatusCode.TooManyRequests, response.status)
        assertEquals("3", response.headers["X-RateLimit-Limit"])
        assertEquals("0", response.headers["X-RateLimit-Remaining"])
        assertNotNull(response.headers["Retry-After"])
        assertTrue(response.headers["Retry-After"]!!.toLong() >= 1L)
    }

    @Test
    fun `비인증 클라이언트는 IP 기반으로 레이트리밋이 적용된다`() = testApplication {
        val testIp = "192.168.10.${(10..200).random()}"

        application {
            module(
                database = database,
                jedis = jedis,
                ipRateLimitCapacity = 2L,
                ipRateLimitRefillRate = 0.0, // 테스트 중 자동 리필 억제
                startRedisCommandConsumer = false,
                startStaleReaper = false
            )
        }

        // IP 용량 2개 소진
        repeat(2) {
            val res = client.get("/api/v1/auth/me") {
                header("X-Forwarded-For", testIp)
            }
            assertTrue(res.status == HttpStatusCode.Unauthorized || res.status == HttpStatusCode.OK)
            assertEquals("2", res.headers["X-RateLimit-Limit"])
        }

        // 3번째 요청: 한도 초과 429 응답 확인
        val response = client.get("/api/v1/auth/me") {
            header("X-Forwarded-For", testIp)
        }

        assertEquals(HttpStatusCode.TooManyRequests, response.status)
        assertEquals("2", response.headers["X-RateLimit-Limit"])
        assertEquals("0", response.headers["X-RateLimit-Remaining"])
    }
}
