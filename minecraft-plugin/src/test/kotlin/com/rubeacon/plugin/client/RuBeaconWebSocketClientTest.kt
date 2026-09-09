package com.rubeacon.plugin.client

import com.rubeacon.common.serialization.RuBeaconJson
import com.rubeacon.common.transport.CommandRequestPayload
import com.rubeacon.common.transport.CommandResponsePayload
import com.rubeacon.common.transport.Opcode
import com.rubeacon.common.transport.WebSocketFrame
import com.rubeacon.plugin.config.RuBeaconConfig
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.netty.NettyApplicationEngine
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.ServerSocket
import java.util.logging.Logger
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RuBeaconWebSocketClientTest {

    private var serverPort: Int = 0
    private var server: NettyApplicationEngine? = null
    private val clientScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val receivedHeaders = mutableMapOf<String, String?>()
    private val serverIncomingFrames = Channel<WebSocketFrame>(Channel.BUFFERED)
    private val serverOutgoingFrames = Channel<WebSocketFrame>(Channel.BUFFERED)

    @BeforeEach
    fun setUp() {
        serverPort = ServerSocket(0).use { it.localPort }

        server = embeddedServer(Netty, port = serverPort) {
            install(WebSockets)
            routing {
                webSocket("/ws/minecraft/v1") {
                    receivedHeaders["X-Tenant-Id"] = call.request.headers["X-Tenant-Id"]
                    receivedHeaders["X-Network-Id"] = call.request.headers["X-Network-Id"]
                    receivedHeaders["X-Instance-Id"] = call.request.headers["X-Instance-Id"]
                    receivedHeaders["X-Instance-Token"] = call.request.headers["X-Instance-Token"]

                    // 송신 루프
                    val sendJob = clientScope.launch {
                        for (frame in serverOutgoingFrames) {
                            send(Frame.Text(RuBeaconJson.default.encodeToString(frame)))
                        }
                    }

                    // 수신 루프
                    try {
                        for (frame in incoming) {
                            if (frame is Frame.Text) {
                                val parsed = RuBeaconJson.default.decodeFromString<WebSocketFrame>(frame.readText())
                                serverIncomingFrames.send(parsed)
                            }
                        }
                    } finally {
                        sendJob.cancel()
                    }
                }
            }
        }.start(wait = false)
    }

    @AfterEach
    fun tearDown() {
        clientScope.cancel()
        serverIncomingFrames.close()
        serverOutgoingFrames.close()
        server?.stop(100, 500)
    }

    @Test
    fun `WSS 클라이언트는 올바른 인증 헤더로 연결하고 명령어 요청을 수신하여 응답을 반환해야 한다`() = runBlocking {
        val config = RuBeaconConfig(
            serverUrl = "ws://127.0.0.1:$serverPort/ws/minecraft/v1",
            tenantId = "tenant_test_01",
            networkId = "net_test_01",
            instanceId = "inst_test_01",
            instanceToken = "token_secret_123",
            pingIntervalMs = 5000L
        )

        val commandExecuted = CompletableDeferred<CommandRequestPayload>()

        val client = RuBeaconWebSocketClient(
            config = config,
            scope = clientScope,
            logger = Logger.getLogger("TestClient"),
            commandHandler = { payload, _ ->
                commandExecuted.complete(payload)
                CommandResponsePayload(
                    requestId = payload.requestId,
                    success = true,
                    output = "Test command executed successfully"
                )
            }
        )

        client.start()

        withTimeout(5000) {
            // 1. 서버가 클라이언트에게 COMMAND_REQ를 보냄
            val reqPayload = CommandRequestPayload(
                requestId = "req_1001",
                command = "say Hello RuBeacon",
                args = emptyList()
            )
            val reqFrame = WebSocketFrame.commandReq(
                payload = reqPayload,
                traceId = "trc_req_01"
            )

            // 서버 송신
            serverOutgoingFrames.send(reqFrame)

            // 클라이언트 핸들러 실행 확인
            val executedPayload = commandExecuted.await()
            assertEquals("req_1001", executedPayload.requestId)
            assertEquals("say Hello RuBeacon", executedPayload.command)

            // 서버가 COMMAND_RES를 회신받았는지 검증
            val resFrame = serverIncomingFrames.receive()
            assertEquals(Opcode.COMMAND_RES, resFrame.op)
            assertEquals("trc_req_01", resFrame.traceId)

            val resPayload = resFrame.decodePayload<CommandResponsePayload>()
            assertEquals("req_1001", resPayload.requestId)
            assertTrue(resPayload.success)

            // 2. 인증 헤더 검증
            assertEquals("tenant_test_01", receivedHeaders["X-Tenant-Id"])
            assertEquals("net_test_01", receivedHeaders["X-Network-Id"])
            assertEquals("inst_test_01", receivedHeaders["X-Instance-Id"])
            assertEquals("token_secret_123", receivedHeaders["X-Instance-Token"])
        }

        client.close()
    }

    @Test
    fun `서버가 PING을 전송하면 클라이언트는 동일한 traceId로 PONG을 즉시 회신해야 한다`() = runBlocking {
        val config = RuBeaconConfig(
            serverUrl = "ws://127.0.0.1:$serverPort/ws/minecraft/v1",
            tenantId = "tenant_test_02",
            networkId = "net_test_02",
            instanceId = "inst_test_02",
            instanceToken = "token_secret_456",
            pingIntervalMs = 10000L
        )

        val client = RuBeaconWebSocketClient(
            config = config,
            scope = clientScope,
            logger = Logger.getLogger("TestPingClient"),
            commandHandler = { payload, _ ->
                CommandResponsePayload(payload.requestId, true, "OK")
            }
        )

        client.start()

        withTimeout(5000) {
            val pingFrame = WebSocketFrame.ping(traceId = "trc_ping_test_99")
            serverOutgoingFrames.send(pingFrame)

            val pongFrame = serverIncomingFrames.receive()
            assertEquals(Opcode.PONG, pongFrame.op)
            assertEquals("trc_ping_test_99", pongFrame.traceId)
        }

        client.close()
    }
}
