package com.rubeacon.api

import com.rubeacon.api.db.AccountLinks
import com.rubeacon.api.db.MinecraftInstances
import com.rubeacon.api.db.MinecraftNetworks
import com.rubeacon.api.db.Tenants
import com.rubeacon.api.db.Workflows
import com.rubeacon.api.service.InstanceAuthService
import com.rubeacon.api.service.SessionRegistry
import com.rubeacon.common.event.EventEntity
import com.rubeacon.common.event.EventEnvelope
import com.rubeacon.common.redis.RedisNamespaces
import com.rubeacon.common.serialization.RuBeaconJson
import com.rubeacon.common.transport.Opcode
import com.rubeacon.common.transport.TransportConstants
import com.rubeacon.common.transport.WebSocketFrame
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.header
import io.ktor.server.testing.testApplication
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.jetbrains.exposed.sql.deleteAll
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WebSocketIngressAndRedisIntegrationTest : BaseIntegrationTest() {

    private val authService = InstanceAuthService()
    private val sessionRegistry = SessionRegistry()

    @BeforeEach
    fun clearData() {
        transaction(database) {
            AccountLinks.deleteAll()
            MinecraftInstances.deleteAll()
            MinecraftNetworks.deleteAll()
            Workflows.deleteAll()
            Tenants.deleteAll()
        }
    }

    private fun awaitStatus(instanceId: String, expectedStatus: String, timeoutMs: Long = 3000) {
        val start = System.currentTimeMillis()
        var currentStatus = ""
        while (System.currentTimeMillis() - start < timeoutMs) {
            currentStatus = transaction(database) {
                MinecraftInstances.selectAll().where { MinecraftInstances.id eq instanceId }
                    .singleOrNull()?.get(MinecraftInstances.status) ?: ""
            }
            if (currentStatus == expectedStatus) return
            Thread.sleep(50)
        }
        assertEquals(expectedStatus, currentStatus, "인스턴스 상태가 ${timeoutMs}ms 내에 $expectedStatus(으)로 전이되어야 함")
    }

    @Test
    fun `유효하지 않은 토큰으로 접속 시 WebSocket Close 4003 코드로 차단되어야 한다`() = testApplication {
        application {
            module(
                database = database,
                jedis = jedis,
                authService = authService,
                sessionRegistry = sessionRegistry
            )
        }

        val client = createClient {
            install(WebSockets)
        }

        client.webSocket("/ws/minecraft/v1", request = {
            header(TransportConstants.HEADER_TENANT_ID, "invalid_tenant")
            header(TransportConstants.HEADER_INSTANCE_ID, "invalid_instance")
            header(TransportConstants.HEADER_INSTANCE_TOKEN, "invalid_token")
        }) {
            val closeReason = closeReason.await()
            assertNotNull(closeReason)
            assertEquals(TransportConstants.CLOSE_CODE_FORBIDDEN.toShort(), closeReason.code)
        }
    }

    @Test
    fun `정상 토큰으로 접속 후 PING 프레임 전송 시 PONG 응답을 수신하고 상태가 ONLINE이어야 한다`() = testApplication {
        val tenantId = "tenant_ws_01"
        val networkId = "net_ws_01"
        val instanceId = "inst_ws_01"
        val rawToken = "valid-secret-token"

        transaction(database) {
            Tenants.insert {
                it[id] = tenantId
                it[name] = "웹소켓 테스트 테넌트"
                it[discordGuildId] = "111222333444555666"
            }
            MinecraftNetworks.insert {
                it[id] = networkId
                it[this.tenantId] = tenantId
                it[name] = "네트워크"
            }
            MinecraftInstances.insert {
                it[id] = instanceId
                it[this.networkId] = networkId
                it[this.tenantId] = tenantId
                it[instanceType] = "BACKEND"
                it[name] = "서버 1"
                it[tokenHash] = authService.hashToken(rawToken)
                it[status] = "OFFLINE"
            }
        }

        application {
            module(
                database = database,
                jedis = jedis,
                authService = authService,
                sessionRegistry = sessionRegistry
            )
        }

        val client = createClient {
            install(WebSockets)
        }

        client.webSocket("/ws/minecraft/v1", request = {
            header(TransportConstants.HEADER_TENANT_ID, tenantId)
            header(TransportConstants.HEADER_INSTANCE_ID, instanceId)
            header(TransportConstants.HEADER_INSTANCE_TOKEN, rawToken)
        }) {
            // PING 전송
            val pingFrame = WebSocketFrame.ping(traceId = "trc_ping_01")
            send(Frame.Text(RuBeaconJson.default.encodeToString(pingFrame)))

            // PONG 수신 검증
            val incomingFrame = withTimeout(5000) { incoming.receive() }
            assertTrue(incomingFrame is Frame.Text)
            val resFrame = RuBeaconJson.default.decodeFromString<WebSocketFrame>(incomingFrame.readText())
            assertEquals(Opcode.PONG, resFrame.op)
            assertEquals("trc_ping_01", resFrame.traceId)

            // PONG 수신 후 활성 세션 수 및 DB ONLINE 상태 검증
            assertEquals(1, sessionRegistry.activeCount)
            transaction(database) {
                val inst = MinecraftInstances.selectAll().where { MinecraftInstances.id eq instanceId }.single()
                assertEquals("ONLINE", inst[MinecraftInstances.status])
            }

            // Redis Presence TTL(60초) 검증 (결함 8: 하트비트 Redis 격리)
            val presenceKey = RedisNamespaces.instanceHeartbeatKey(instanceId)
            val ttl = jedis.ttl(presenceKey)
            assertTrue(ttl in 1..60, "하트비트 presence 키의 TTL이 1~60초 범위여야 함 (현재: $ttl)")

            // 세션 종료
            close()
        }

        // 연결 종료 후 OFFLINE 전이 검증 (비동기 완료 대기)
        awaitStatus(instanceId, "OFFLINE")

        // 세션 종료 후 Redis presence 키 즉시 삭제 검증
        val presenceKey = RedisNamespaces.instanceHeartbeatKey(instanceId)
        assertNull(jedis.get(presenceKey), "세션 종료 후 presence 키가 삭제되어야 함")
    }

    @Test
    fun `플러그인이 EVENT 프레임 전송 시 테넌트 Redis Streams에 이벤트가 발행되어야 한다`() = testApplication {
        val tenantId = "tenant_stream_01"
        val networkId = "net_stream_01"
        val instanceId = "inst_stream_01"
        val rawToken = "stream-test-token"

        transaction(database) {
            Tenants.insert {
                it[id] = tenantId
                it[name] = "스트림 테스트 테넌트"
                it[discordGuildId] = "999888777666555444"
            }
            MinecraftNetworks.insert {
                it[id] = networkId
                it[this.tenantId] = tenantId
                it[name] = "스트림 네트워크"
            }
            MinecraftInstances.insert {
                it[id] = instanceId
                it[this.networkId] = networkId
                it[this.tenantId] = tenantId
                it[instanceType] = "BACKEND"
                it[name] = "스트림 인스턴스"
                it[tokenHash] = authService.hashToken(rawToken)
                it[status] = "OFFLINE"
            }
        }

        application {
            module(
                database = database,
                jedis = jedis,
                authService = authService,
                sessionRegistry = sessionRegistry
            )
        }

        val client = createClient {
            install(WebSockets)
        }

        val streamKey = RedisNamespaces.eventsStream(tenantId)
        jedis.del(streamKey) // 기존 스트림 정리

        client.webSocket("/ws/minecraft/v1", request = {
            header(TransportConstants.HEADER_TENANT_ID, tenantId)
            header(TransportConstants.HEADER_INSTANCE_ID, instanceId)
            header(TransportConstants.HEADER_INSTANCE_TOKEN, rawToken)
        }) {
            val envelope = EventEnvelope(
                eventId = "evt_test_${UUID.randomUUID()}",
                eventType = "minecraft.player.level_up",
                source = "minecraft",
                sourceInstanceId = instanceId,
                tenantId = tenantId,
                minecraftNetworkId = networkId,
                occurredAt = "2026-09-09T06:00:00Z",
                receivedAt = "2026-09-09T06:00:00Z",
                correlationId = "corr_ws_test",
                idempotencyKey = "mc:lvl:${UUID.randomUUID()}:30",
                actor = EventEntity(
                    type = "minecraft_player",
                    id = UUID.randomUUID().toString()
                ),
                payload = buildJsonObject {
                    put("player_name", "Steve")
                    put("level", 30)
                }
            )

            val eventFrame = WebSocketFrame.event(envelope)
            send(Frame.Text(RuBeaconJson.default.encodeToString(eventFrame)))

            // PING을 전송하여 이전 EVENT가 서버에서 완전 처리되었음을 동기화 확인
            val syncPing = WebSocketFrame.ping(traceId = "trc_sync_event")
            send(Frame.Text(RuBeaconJson.default.encodeToString(syncPing)))
            val syncPong = withTimeout(5000) { incoming.receive() }
            assertTrue(syncPong is Frame.Text)

            close()
        }

        // Redis Streams 검증
        val streamEntries = jedis.xrange(streamKey, "-", "+")
        assertEquals(1, streamEntries.size)
        val entry = streamEntries.first()
        val payloadInRedis = entry.fields["payload"]
        assertNotNull(payloadInRedis)
        assertTrue(payloadInRedis.contains("minecraft.player.level_up"))
        assertTrue(payloadInRedis.contains("Steve"))
    }

    @Test
    fun `동일 인스턴스 재연결 시 이전 세션의 종료 처리가 신규 세션의 ONLINE 상태를 덮어쓰지 않아야 한다`() = testApplication {
        val tenantId = "tenant_reconnect_01"
        val networkId = "net_reconnect_01"
        val instanceId = "inst_reconnect_01"
        val rawToken = "reconnect-token"

        transaction(database) {
            Tenants.insert {
                it[id] = tenantId
                it[name] = "재연결 테스트 테넌트"
                it[discordGuildId] = "444555666777888999"
            }
            MinecraftNetworks.insert {
                it[id] = networkId
                it[this.tenantId] = tenantId
                it[name] = "재연결 네트워크"
            }
            MinecraftInstances.insert {
                it[id] = instanceId
                it[this.networkId] = networkId
                it[this.tenantId] = tenantId
                it[instanceType] = "BACKEND"
                it[name] = "재연결 인스턴스"
                it[tokenHash] = authService.hashToken(rawToken)
                it[status] = "OFFLINE"
            }
        }

        application {
            module(
                database = database,
                jedis = jedis,
                authService = authService,
                sessionRegistry = sessionRegistry
            )
        }

        val client = createClient {
            install(WebSockets)
        }

        // 1. Session 1 연결
        client.webSocket("/ws/minecraft/v1", request = {
            header(TransportConstants.HEADER_TENANT_ID, tenantId)
            header(TransportConstants.HEADER_INSTANCE_ID, instanceId)
            header(TransportConstants.HEADER_INSTANCE_TOKEN, rawToken)
        }) {
            send(Frame.Text(RuBeaconJson.default.encodeToString(WebSocketFrame.ping(traceId = "trc_s1"))))
            incoming.receive() // PONG 수신

            // 2. Session 1이 살아있는 상태에서 Session 2 재연결
            client.webSocket("/ws/minecraft/v1", request = {
                header(TransportConstants.HEADER_TENANT_ID, tenantId)
                header(TransportConstants.HEADER_INSTANCE_ID, instanceId)
                header(TransportConstants.HEADER_INSTANCE_TOKEN, rawToken)
            }) {
                send(Frame.Text(RuBeaconJson.default.encodeToString(WebSocketFrame.ping(traceId = "trc_s2"))))
                incoming.receive() // PONG 수신

                // Session 2가 정상 등록되어 활성 세션 수는 1이고 상태는 ONLINE 유지
                assertEquals(1, sessionRegistry.activeCount)
                transaction(database) {
                    val inst = MinecraftInstances.selectAll().where { MinecraftInstances.id eq instanceId }.single()
                    assertEquals("ONLINE", inst[MinecraftInstances.status])
                }

                close()
            }
            close()
        }

        // 모든 세션이 종료된 후에는 OFFLINE으로 정상 전이 (비동기 완료 대기)
        awaitStatus(instanceId, "OFFLINE")
    }

    @Test
    fun `64KB 초과 대용량 프레임 인입 시 웹소켓 프레임 가드에 의해 세션이 차단되어야 한다`() = testApplication {
        val tenantId = "tenant_dos_01"
        val networkId = "net_dos_01"
        val instanceId = "inst_dos_01"
        val rawToken = "dos-test-token"

        transaction(database) {
            Tenants.insert {
                it[id] = tenantId
                it[name] = "보안 가드 테스트 테넌트"
                it[discordGuildId] = "999888777666555444"
            }
            MinecraftNetworks.insert {
                it[id] = networkId
                it[this.tenantId] = tenantId
                it[name] = "보안 네트워크"
            }
            MinecraftInstances.insert {
                it[id] = instanceId
                it[this.networkId] = networkId
                it[this.tenantId] = tenantId
                it[instanceType] = "BACKEND"
                it[name] = "보안 검증 서버"
                it[tokenHash] = authService.hashToken(rawToken)
                it[status] = "OFFLINE"
            }
        }

        application {
            module(
                database = database,
                jedis = jedis,
                authService = authService,
                sessionRegistry = sessionRegistry
            )
        }

        val client = createClient {
            install(WebSockets) {
                maxFrameSize = Long.MAX_VALUE // 클라이언트는 64KB 초과 전송 허용
            }
        }

        // 300KB 크기의 비인가 대용량 페이로드 전송 시도 (256KB 제한 초과)
        val hugePadding = "X".repeat(300 * 1024)
        val hugeFrameText = """{"op":1,"t":"EVENT","d":{"msg":"$hugePadding"},"ts":${System.currentTimeMillis()},"trace_id":"trc_dos"}"""

        runCatching {
            withTimeout(2000) {
                client.webSocket("/ws/minecraft/v1", request = {
                    header(TransportConstants.HEADER_TENANT_ID, tenantId)
                    header(TransportConstants.HEADER_INSTANCE_ID, instanceId)
                    header(TransportConstants.HEADER_INSTANCE_TOKEN, rawToken)
                }) {
                    send(Frame.Text(hugeFrameText))
                    try {
                        incoming.receive()
                    } catch (_: Exception) {
                        // FrameTooBigException에 의한 세션 차단 정상 예외
                    }
                    close()
                }
            }
        }

        // 서버의 maxFrameSize(256KB) 제한으로 인해 세션이 즉시 종료되고 OFFLINE으로 전이되어야 함
        awaitStatus(instanceId, "OFFLINE")
        assertEquals(0, sessionRegistry.activeCount)
    }

    @Test
    fun `reapStaleInstances는 Redis presence 키가 없는 ONLINE 인스턴스를 STALE로 일괄 전이해야 한다`() {
        val tenantId = "tenant_reap_01"
        val networkId = "net_reap_01"
        val deadInstanceId = "inst_dead_01"
        val aliveInstanceId = "inst_alive_01"

        transaction(database) {
            Tenants.insert {
                it[id] = tenantId
                it[name] = "리퍼 테스트 테넌트"
                it[discordGuildId] = "123123123123123123"
            }
            MinecraftNetworks.insert {
                it[id] = networkId
                it[this.tenantId] = tenantId
                it[name] = "리퍼 네트워크"
            }
            MinecraftInstances.insert {
                it[id] = deadInstanceId
                it[this.networkId] = networkId
                it[this.tenantId] = tenantId
                it[instanceType] = "BACKEND"
                it[name] = "고아 서버"
                it[tokenHash] = authService.hashToken("token-dead")
                it[status] = "ONLINE" // 비정상 종료로 남아있는 좀비
            }
            MinecraftInstances.insert {
                it[id] = aliveInstanceId
                it[this.networkId] = networkId
                it[this.tenantId] = tenantId
                it[instanceType] = "BACKEND"
                it[name] = "살아있는 서버"
                it[tokenHash] = authService.hashToken("token-alive")
                it[status] = "ONLINE"
            }
        }

        // aliveInstanceId만 Redis presence 등록 (deadInstanceId는 키 없음)
        jedis.setex(RedisNamespaces.instanceHeartbeatKey(aliveInstanceId), 60, System.currentTimeMillis().toString())

        // 리퍼 실행
        val reaped = authService.reapStaleInstances(jedis)
        assertEquals(1, reaped)

        transaction(database) {
            val dead = MinecraftInstances.selectAll().where { MinecraftInstances.id eq deadInstanceId }.single()
            assertEquals("STALE", dead[MinecraftInstances.status])

            val alive = MinecraftInstances.selectAll().where { MinecraftInstances.id eq aliveInstanceId }.single()
            assertEquals("ONLINE", alive[MinecraftInstances.status])
        }

        // 정리
        jedis.del(RedisNamespaces.instanceHeartbeatKey(aliveInstanceId))
    }
}
