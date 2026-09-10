package com.rubeacon.api

import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EventSimulationRoutesTest : BaseIntegrationTest() {

    @BeforeEach
    fun flushRedis() {
        jedis.flushAll()
    }

    @Test
    fun `선착순 정원 이내의 신규 요청은 200 OK와 함께 COMMITTED를 반환해야 한다`() = testApplication {
        application {
            module(database, jedis)
        }

        val tenantId = "tenant_test_sim"
        val playerUuid = UUID.randomUUID().toString()
        val payload = """
            {
                "event_id": "evt_test_1",
                "event_type": "minecraft.player.interact",
                "source": "MINECRAFT_BACKEND",
                "tenant_id": "$tenantId",
                "timestamp": "2026-09-10T12:00:00Z",
                "correlation_id": "corr-1",
                "idempotency_key": "idem-1",
                "version": 1,
                "payload": {
                    "action": "ATTENDANCE_REWARD",
                    "player_uuid": "$playerUuid",
                    "player_name": "HeroPlayer",
                    "total_limit": 5
                }
            }
        """.trimIndent()

        val response = client.post("/api/v1/events/simulate") {
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            header("X-Tenant-Id", tenantId)
            setBody(payload)
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("COMMITTED"))
        assertTrue(body.contains(playerUuid))
    }

    @Test
    fun `동일 플레이어의 중복 요청은 409 Conflict와 ALREADY_RESERVED를 반환해야 한다`() = testApplication {
        application {
            module(database, jedis)
        }

        val tenantId = "tenant_test_sim"
        val playerUuid = UUID.randomUUID().toString()
        fun makePayload(corrId: String) = """
            {
                "event_id": "evt_$corrId",
                "event_type": "minecraft.player.interact",
                "source": "MINECRAFT_BACKEND",
                "tenant_id": "$tenantId",
                "timestamp": "2026-09-10T12:00:00Z",
                "correlation_id": "$corrId",
                "idempotency_key": "idem-$corrId",
                "version": 1,
                "payload": {
                    "action": "ATTENDANCE_REWARD",
                    "player_uuid": "$playerUuid",
                    "player_name": "HeroPlayer",
                    "total_limit": 5
                }
            }
        """.trimIndent()

        val res1 = client.post("/api/v1/events/simulate") {
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            header("X-Tenant-Id", tenantId)
            setBody(makePayload("first"))
        }
        assertEquals(HttpStatusCode.OK, res1.status)

        val res2 = client.post("/api/v1/events/simulate") {
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            header("X-Tenant-Id", tenantId)
            setBody(makePayload("second"))
        }
        assertEquals(HttpStatusCode.Conflict, res2.status)
        assertTrue(res2.bodyAsText().contains("ALREADY_RESERVED"))
    }

    @Test
    fun `정원을 초과한 요청은 429 Too Many Requests와 QUOTA_EXCEEDED를 초고속 반환해야 한다`() = testApplication {
        application {
            module(database, jedis)
        }

        val tenantId = "tenant_test_sim"
        val limit = 2

        for (i in 1..limit) {
            val pUuid = UUID.randomUUID().toString()
            val payload = """
                {
                    "event_id": "evt_limit_$i",
                    "event_type": "minecraft.player.interact",
                    "source": "MINECRAFT_BACKEND",
                    "tenant_id": "$tenantId",
                    "timestamp": "2026-09-10T12:00:00Z",
                    "correlation_id": "corr-$i",
                    "idempotency_key": "idem-$i",
                    "version": 1,
                    "payload": {
                        "action": "ATTENDANCE_REWARD",
                        "player_uuid": "$pUuid",
                        "player_name": "Player_$i",
                        "total_limit": $limit
                    }
                }
            """.trimIndent()

            val res = client.post("/api/v1/events/simulate") {
                header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                header("X-Tenant-Id", tenantId)
                setBody(payload)
            }
            assertEquals(HttpStatusCode.OK, res.status)
        }

        // 초과 요청
        val overflowUuid = UUID.randomUUID().toString()
        val overflowPayload = """
            {
                "event_id": "evt_overflow",
                "event_type": "minecraft.player.interact",
                "source": "MINECRAFT_BACKEND",
                "tenant_id": "$tenantId",
                "timestamp": "2026-09-10T12:00:00Z",
                "correlation_id": "corr-overflow",
                "idempotency_key": "idem-overflow",
                "version": 1,
                "payload": {
                    "action": "ATTENDANCE_REWARD",
                    "player_uuid": "$overflowUuid",
                    "player_name": "OverflowPlayer",
                    "total_limit": $limit
                }
            }
        """.trimIndent()

        val overflowRes = client.post("/api/v1/events/simulate") {
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            header("X-Tenant-Id", tenantId)
            setBody(overflowPayload)
        }
        assertEquals(HttpStatusCode.TooManyRequests, overflowRes.status)
        assertTrue(overflowRes.bodyAsText().contains("QUOTA_EXCEEDED"))
    }

    @Test
    fun `일반 비즈니스 이벤트는 202 Accepted를 반환해야 한다`() = testApplication {
        application {
            module(database, jedis)
        }

        val tenantId = "tenant_test_sim"
        val payload = """
            {
                "event_id": "evt_general_1",
                "event_type": "minecraft.block.break",
                "source": "MINECRAFT_BACKEND",
                "tenant_id": "$tenantId",
                "timestamp": "2026-09-10T12:00:00Z",
                "correlation_id": "corr-gen-1",
                "idempotency_key": "idem-gen-1",
                "version": 1,
                "payload": {
                    "action": "BLOCK_BREAK",
                    "block_type": "DIAMOND_ORE"
                }
            }
        """.trimIndent()

        val response = client.post("/api/v1/events/simulate") {
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            header("X-Tenant-Id", tenantId)
            setBody(payload)
        }

        assertEquals(HttpStatusCode.Accepted, response.status)
        assertTrue(response.bodyAsText().contains("ACCEPTED"))
    }

    @Test
    fun `잘못된 JSON 또는 빈 요청 바디는 400 Bad Request를 반환해야 한다`() = testApplication {
        application {
            module(database, jedis)
        }

        val emptyRes = client.post("/api/v1/events/simulate") {
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            header("X-Tenant-Id", "tenant_test_sim")
            setBody("")
        }
        assertEquals(HttpStatusCode.BadRequest, emptyRes.status)

        val malformedRes = client.post("/api/v1/events/simulate") {
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            header("X-Tenant-Id", "tenant_test_sim")
            setBody("{ invalid json }")
        }
        assertEquals(HttpStatusCode.BadRequest, malformedRes.status)
    }
}
