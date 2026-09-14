package com.rubeacon.api

import com.rubeacon.api.db.AccountLinks
import com.rubeacon.api.db.MinecraftInstances
import com.rubeacon.api.db.MinecraftNetworks
import com.rubeacon.api.db.Tenants
import com.rubeacon.api.db.Workflows
import com.rubeacon.api.redis.RedisCommandConsumer
import com.rubeacon.api.service.InstanceAuthService
import com.rubeacon.api.service.SessionRegistry
import com.rubeacon.common.redis.RedisNamespaces
import com.rubeacon.common.serialization.RuBeaconJson
import com.rubeacon.common.transport.CommandRequestPayload
import com.rubeacon.common.transport.CommandResponsePayload
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import org.jetbrains.exposed.sql.deleteAll
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import redis.clients.jedis.params.XAddParams
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DistributedSessionAndCommandRoutingIntegrationTest : BaseIntegrationTest() {

    private val authService = InstanceAuthService()

    @BeforeEach
    fun clearData() {
        transaction(database) {
            AccountLinks.deleteAll()
            MinecraftInstances.deleteAll()
            MinecraftNetworks.deleteAll()
            Workflows.deleteAll()
            Tenants.deleteAll()
        }
        jedis.del(RedisNamespaces.STREAM_COMMANDS_REQUEST)
        jedis.del(RedisNamespaces.STREAM_COMMANDS_RESULT)
    }

    private fun registerTestInstance(tenantId: String, networkId: String, instanceId: String, rawToken: String) {
        transaction(database) {
            Tenants.insert {
                it[id] = tenantId
                it[name] = "테스트 테넌트"
                it[discordGuildId] = "111222333444555666"
            }
            MinecraftNetworks.insert {
                it[id] = networkId
                it[this.tenantId] = tenantId
                it[name] = "테스트 네트워크"
            }
            MinecraftInstances.insert {
                it[id] = instanceId
                it[this.networkId] = networkId
                it[this.tenantId] = tenantId
                it[instanceType] = "BACKEND"
                it[name] = "테스트 인스턴스"
                it[tokenHash] = authService.hashToken(rawToken)
                it[status] = "OFFLINE"
            }
        }
    }

    @Test
    fun `로컬 세션 라우팅 - stream 명령 발행 시 로컬 WSS 클라이언트로 COMMAND_REQ 프레임이 전달되어야 한다`() = testApplication {
        val tenantId = "tenant_cmd_01"
        val networkId = "net_cmd_01"
        val instanceId = "inst_cmd_01"
        val rawToken = "cmd-secret-token"
        registerTestInstance(tenantId, networkId, instanceId, rawToken)

        val sessionRegistry = SessionRegistry()

        application {
            module(
                database = database,
                jedis = jedis,
                authService = authService,
                sessionRegistry = sessionRegistry,
                startRedisCommandConsumer = true
            )
        }

        val client = createClient { install(WebSockets) }

        client.webSocket("/ws/minecraft/v1", request = {
            header(TransportConstants.HEADER_TENANT_ID, tenantId)
            header(TransportConstants.HEADER_INSTANCE_ID, instanceId)
            header(TransportConstants.HEADER_INSTANCE_TOKEN, rawToken)
        }) {
            // 연결 확인 PING/PONG
            send(Frame.Text(RuBeaconJson.default.encodeToString(WebSocketFrame.ping("trc_ping"))))
            val pongFrame = withTimeout(5000) { incoming.receive() }
            assertTrue(pongFrame is Frame.Text)

            // Worker 입장에서 stream:commands:request 에 명령 발행
            val requestId = "req_${UUID.randomUUID().toString().take(8)}"
            val commandText = "give Steve diamond 1"
            jedis.xadd(
                RedisNamespaces.STREAM_COMMANDS_REQUEST,
                XAddParams.xAddParams().maxLen(1000L).approximateTrimming(),
                mapOf(
                    "request_id" to requestId,
                    "tenant_id" to tenantId,
                    "instance_id" to instanceId,
                    "command" to commandText,
                    "correlation_id" to "corr_test_01"
                )
            )

            // WSS 수신 대기 및 검증
            val cmdIncoming = withTimeout(5000) { incoming.receive() }
            assertTrue(cmdIncoming is Frame.Text)
            val receivedFrame = RuBeaconJson.default.decodeFromString<WebSocketFrame>(cmdIncoming.readText())
            assertEquals(Opcode.COMMAND_REQ, receivedFrame.op)
            assertEquals("corr_test_01", receivedFrame.traceId)

            val payload = receivedFrame.decodePayload<CommandRequestPayload>()
            assertEquals(requestId, payload.requestId)
            assertEquals(commandText, payload.command)

            close()
        }
    }

    @Test
    fun `결과 회신 파이프라인 - 플러그인이 COMMAND_RES 반환 시 stream commands result 스트림에 발행되어야 한다`() = testApplication {
        val tenantId = "tenant_res_01"
        val networkId = "net_res_01"
        val instanceId = "inst_res_01"
        val rawToken = "res-secret-token"
        registerTestInstance(tenantId, networkId, instanceId, rawToken)

        val sessionRegistry = SessionRegistry()

        application {
            module(
                database = database,
                jedis = jedis,
                authService = authService,
                sessionRegistry = sessionRegistry,
                startRedisCommandConsumer = false // 수동 테스트
            )
        }

        val client = createClient { install(WebSockets) }

        client.webSocket("/ws/minecraft/v1", request = {
            header(TransportConstants.HEADER_TENANT_ID, tenantId)
            header(TransportConstants.HEADER_INSTANCE_ID, instanceId)
            header(TransportConstants.HEADER_INSTANCE_TOKEN, rawToken)
        }) {
            // COMMAND_RES 전송
            val resPayload = CommandResponsePayload(
                requestId = "req_finish_01",
                success = true,
                output = "Given 1 [Diamond] to Steve"
            )
            val resFrame = WebSocketFrame.commandRes(resPayload, traceId = "trc_finish_01")
            send(Frame.Text(RuBeaconJson.default.encodeToString(resFrame)))

            // 동기화용 PING
            val syncPing = WebSocketFrame.ping("trc_sync")
            send(Frame.Text(RuBeaconJson.default.encodeToString(syncPing)))
            incoming.receive() // PONG

            close()
        }

        // stream:commands:result 검증
        val results = jedis.xrange(RedisNamespaces.STREAM_COMMANDS_RESULT, "-", "+")
        assertEquals(1, results.size)
        val entry = results.first().fields
        assertEquals("req_finish_01", entry["request_id"])
        assertEquals("true", entry["success"])
        assertEquals("Given 1 [Diamond] to Steve", entry["output"])
        assertEquals("trc_finish_01", entry["trace_id"])
        assertEquals(instanceId, entry["instance_id"])
    }

    @Test
    fun `분산 세션 라우팅 - 대상 세션이 로컬에 없으면 Redis PubSub 브로드캐스트를 거쳐 원격 Pod의 세션으로 라우팅되어야 한다`() = testApplication {
        val tenantId = "tenant_dist_01"
        val networkId = "net_dist_01"
        val remoteInstanceId = "inst_remote_pod_B"
        val rawTokenRemote = "token-remote-B"

        registerTestInstance(tenantId, networkId, remoteInstanceId, rawTokenRemote)

        // Pod A의 세션 레지스트리 (원격 인스턴스 세션 없음)
        val podASessionRegistry = SessionRegistry()
        // Pod B 시뮬레이션: 별도의 SessionRegistry 및 Pub/Sub 구독 활성화
        val podBSessionRegistry = SessionRegistry()
        val testScope = CoroutineScope(Dispatchers.IO)
        val podBJob = podBSessionRegistry.startBroadcastSubscriber(jedis, testScope)
        delay(300) // Pub/Sub 구독 확정 대기

        // Pod A의 컨슈머
        val podAConsumer = RedisCommandConsumer(jedis, podASessionRegistry, consumerId = "pod-a-consumer")

        application {
            module(
                database = database,
                jedis = jedis,
                authService = authService,
                sessionRegistry = podASessionRegistry,
                startRedisCommandConsumer = false
            )
        }

        val client = createClient { install(WebSockets) }

        // remoteInstanceId로 WSS 연결
        client.webSocket("/ws/minecraft/v1", request = {
            header(TransportConstants.HEADER_TENANT_ID, tenantId)
            header(TransportConstants.HEADER_INSTANCE_ID, remoteInstanceId)
            header(TransportConstants.HEADER_INSTANCE_TOKEN, rawTokenRemote)
        }) {
            send(Frame.Text(RuBeaconJson.default.encodeToString(WebSocketFrame.ping("trc_init"))))
            incoming.receive() // PONG

            // 이 세션을 Pod B에 존재하는 세션으로 시뮬레이션: Pod A 레지스트리에서 Pod B 레지스트리로 세션 이전
            val wsSession = podASessionRegistry.getSession(remoteInstanceId)
            assertNotNull(wsSession)
            podASessionRegistry.unregister(remoteInstanceId)
            podBSessionRegistry.register(remoteInstanceId, wsSession)

            assertFalse(podASessionRegistry.hasSession(remoteInstanceId))
            assertTrue(podBSessionRegistry.hasSession(remoteInstanceId))

            // Pod A가 stream:commands:request 에서 메시지를 수신했을 때:
            val requestId = "req_dist_${UUID.randomUUID().toString().take(8)}"
            val commandText = "tellraw @a {\"text\":\"Broadcasted to Pod B!\"}"
            jedis.xadd(
                RedisNamespaces.STREAM_COMMANDS_REQUEST,
                XAddParams.xAddParams().maxLen(1000L).approximateTrimming(),
                mapOf(
                    "request_id" to requestId,
                    "tenant_id" to tenantId,
                    "instance_id" to remoteInstanceId,
                    "command" to commandText,
                    "correlation_id" to "corr_dist_01"
                )
            )

            // Pod A 컨슈머가 배치 1회 처리 (로컬에 없으므로 Pub/Sub으로 발행 후 XACK)
            val processed = podAConsumer.processBatch(count = 1)
            assertEquals(1, processed)

            // Pod B가 Pub/Sub을 통해 메시지를 수신하여 WSS 클라이언트로 전달했는지 검증
            val cmdIncoming = withTimeout(5000) { incoming.receive() }
            assertTrue(cmdIncoming is Frame.Text)
            val receivedFrame = RuBeaconJson.default.decodeFromString<WebSocketFrame>(cmdIncoming.readText())
            assertEquals(Opcode.COMMAND_REQ, receivedFrame.op)
            assertEquals("corr_dist_01", receivedFrame.traceId)

            val payload = receivedFrame.decodePayload<CommandRequestPayload>()
            assertEquals(requestId, payload.requestId)
            assertEquals(commandText, payload.command)

            close()
        }

        podBSessionRegistry.stopBroadcastSubscriber()
        podBJob.cancel()
    }

    @Test
    fun `전체 브로드캐스트 - instance_id가 all인 경우 연결된 모든 세션에 COMMAND_REQ가 전달되어야 한다`() = testApplication {
        val tenantId = "tenant_all_01"
        val networkId = "net_all_01"
        val inst1 = "inst_all_01"
        val inst2 = "inst_all_02"
        val rawToken1 = "token-all-1"
        val rawToken2 = "token-all-2"

        registerTestInstance(tenantId, networkId, inst1, rawToken1)
        transaction(database) {
            MinecraftInstances.insert {
                it[id] = inst2
                it[this.networkId] = networkId
                it[this.tenantId] = tenantId
                it[instanceType] = "BACKEND"
                it[name] = "인스턴스 2"
                it[tokenHash] = authService.hashToken(rawToken2)
                it[status] = "OFFLINE"
            }
        }

        val sessionRegistry = SessionRegistry()

        application {
            module(
                database = database,
                jedis = jedis,
                authService = authService,
                sessionRegistry = sessionRegistry,
                startRedisCommandConsumer = true
            )
        }

        val client = createClient { install(WebSockets) }

        // 클라이언트 1 연결
        client.webSocket("/ws/minecraft/v1", request = {
            header(TransportConstants.HEADER_TENANT_ID, tenantId)
            header(TransportConstants.HEADER_INSTANCE_ID, inst1)
            header(TransportConstants.HEADER_INSTANCE_TOKEN, rawToken1)
        }) {
            send(Frame.Text(RuBeaconJson.default.encodeToString(WebSocketFrame.ping("trc_ping_1"))))
            incoming.receive() // PONG

            // 클라이언트 2 연결
            client.webSocket("/ws/minecraft/v1", request = {
                header(TransportConstants.HEADER_TENANT_ID, tenantId)
                header(TransportConstants.HEADER_INSTANCE_ID, inst2)
                header(TransportConstants.HEADER_INSTANCE_TOKEN, rawToken2)
            }) {
                send(Frame.Text(RuBeaconJson.default.encodeToString(WebSocketFrame.ping("trc_ping_2"))))
                incoming.receive() // PONG

                assertEquals(2, sessionRegistry.activeCount)

                // instance_id = "all" 명령 발행
                val requestId = "req_all_${UUID.randomUUID().toString().take(8)}"
                val commandText = "say Server maintenance in 5 minutes"
                jedis.xadd(
                    RedisNamespaces.STREAM_COMMANDS_REQUEST,
                    XAddParams.xAddParams().maxLen(1000L).approximateTrimming(),
                    mapOf(
                        "request_id" to requestId,
                        "tenant_id" to tenantId,
                        "instance_id" to "all",
                        "command" to commandText,
                        "correlation_id" to "corr_all_01"
                    )
                )

                // 클라이언트 2에서 COMMAND_REQ 수신 확인
                val inc2 = withTimeout(5000) { incoming.receive() }
                assertTrue(inc2 is Frame.Text)
                val frame2 = RuBeaconJson.default.decodeFromString<WebSocketFrame>(inc2.readText())
                assertEquals(Opcode.COMMAND_REQ, frame2.op)
                assertEquals(commandText, frame2.decodePayload<CommandRequestPayload>().command)

                close()
            }

            // 클라이언트 1에서도 COMMAND_REQ 수신 확인
            val inc1 = withTimeout(5000) { incoming.receive() }
            assertTrue(inc1 is Frame.Text)
            val frame1 = RuBeaconJson.default.decodeFromString<WebSocketFrame>(inc1.readText())
            assertEquals(Opcode.COMMAND_REQ, frame1.op)
            assertEquals("say Server maintenance in 5 minutes", frame1.decodePayload<CommandRequestPayload>().command)

            close()
        }
    }
}
