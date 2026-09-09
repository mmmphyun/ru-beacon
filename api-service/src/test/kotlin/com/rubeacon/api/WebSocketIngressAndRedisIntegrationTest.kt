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

            // 세션 종료
            close()
        }

        // 연결 종료 후 OFFLINE 전이 검증
        transaction(database) {
            val inst = MinecraftInstances.selectAll().where { MinecraftInstances.id eq instanceId }.single()
            assertEquals("OFFLINE", inst[MinecraftInstances.status])
        }
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
}
