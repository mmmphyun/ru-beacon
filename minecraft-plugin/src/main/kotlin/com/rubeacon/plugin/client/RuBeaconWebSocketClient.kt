package com.rubeacon.plugin.client

import com.rubeacon.common.serialization.RuBeaconJson
import com.rubeacon.common.transport.CommandRequestPayload
import com.rubeacon.common.transport.CommandResponsePayload
import com.rubeacon.common.transport.Opcode
import com.rubeacon.common.transport.WebSocketFrame
import com.rubeacon.plugin.config.RuBeaconConfig
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.header
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.logging.Logger
import kotlin.math.min
import kotlin.random.Random

/**
 * Paper 플러그인과 중앙 API 서버 간의 전용 비동기 WSS 클라이언트.
 *
 * docs/TRANSPORT_PROTOCOL_SPEC.md §1 규격을 준수하며,
 * 메인 틱 블로킹을 배제하기 위해 독립된 백그라운드 코루틴 스코프에서 구동됨.
 *
 * @param config WSS 접속 주소 및 테넌트/인스턴스 인증 정보
 * @param scope 백그라운드 코루틴 스코프
 * @param logger 로깅용 Logger
 * @param commandHandler 수신된 COMMAND_REQ를 메인 틱에서 실행하고 결과를 반환하는 서스펜딩 핸들러
 */
class RuBeaconWebSocketClient(
    private val config: RuBeaconConfig,
    private val scope: CoroutineScope,
    private val logger: Logger,
    private val commandHandler: suspend (CommandRequestPayload, String) -> CommandResponsePayload,
    private val httpClient: HttpClient = HttpClient(CIO) {
        install(WebSockets)
    }
) : AutoCloseable {

    private val isRunning = AtomicBoolean(false)
    private val isConnected = AtomicBoolean(false)
    private val lastPongReceivedAt = AtomicLong(0L)

    private val outboundChannel = Channel<WebSocketFrame>(capacity = Channel.BUFFERED)
    private var connectionJob: Job? = null

    val connected: Boolean get() = isConnected.get()

    /**
     * WSS 비동기 재연결 루프를 시작함.
     */
    fun start() {
        if (!config.enabled) {
            logger.info("[RuBeacon] WSS 클라이언트가 설정상 비활성화되어 있습니다.")
            return
        }
        if (isRunning.compareAndSet(false, true)) {
            connectionJob = scope.launch {
                runConnectionLoop()
            }
        }
    }

    /**
     * 외부(이벤트 리스너 등)에서 생성된 프레임을 WSS 송신 큐에 적재함.
     */
    suspend fun sendFrame(frame: WebSocketFrame) {
        outboundChannel.send(frame)
    }

    /**
     * 논블로킹 방식으로 프레임 송신을 시도함 (버퍼 초과 시 false 반환).
     */
    fun trySendFrame(frame: WebSocketFrame): Boolean {
        return outboundChannel.trySend(frame).isSuccess
    }

    private suspend fun runConnectionLoop() {
        var currentDelayMs = config.reconnectInitialDelayMs

        while (isRunning.get() && scope.isActive) {
            try {
                logger.info("[RuBeacon] WSS 서버 연결 시도: ${config.serverUrl}")
                httpClient.webSocket(
                    urlString = config.serverUrl,
                    request = {
                        header("X-Tenant-Id", config.tenantId)
                        header("X-Network-Id", config.networkId)
                        header("X-Instance-Id", config.instanceId)
                        header("X-Instance-Token", config.instanceToken)
                    }
                ) {
                    isConnected.set(true)
                    currentDelayMs = config.reconnectInitialDelayMs
                    logger.info("[RuBeacon] WSS 서버와 연결 수립 완료 (인스턴스: ${config.instanceId})")

                    handleSession(this)
                }
            } catch (e: CancellationException) {
                logger.info("[RuBeacon] WSS 클라이언트 루프가 취소되었습니다.")
                break
            } catch (e: Exception) {
                isConnected.set(false)
                logger.warning("[RuBeacon] WSS 연결 실패 또는 세션 단절: ${e.message}")
            } finally {
                isConnected.set(false)
            }

            if (!isRunning.get() || !scope.isActive) break

            val jitter = Random.nextLong(0, 500)
            val sleepTime = currentDelayMs + jitter
            logger.info("[RuBeacon] ${sleepTime}ms 후 WSS 재연결을 시도합니다...")
            delay(sleepTime)
            currentDelayMs = min(config.reconnectMaxDelayMs, currentDelayMs * 2)
        }
    }

    private suspend fun handleSession(session: DefaultClientWebSocketSession) {
        val pingJob = scope.launch {
            while (isActive && isRunning.get()) {
                delay(config.pingIntervalMs)
                val pingFrame = WebSocketFrame.ping(traceId = "trc_ping_${UUID.randomUUID().toString().take(8)}")
                val text = RuBeaconJson.default.encodeToString(pingFrame)
                session.send(Frame.Text(text))
            }
        }

        val sendJob = scope.launch {
            for (frame in outboundChannel) {
                try {
                    val text = RuBeaconJson.default.encodeToString(frame)
                    session.send(Frame.Text(text))
                } catch (e: Exception) {
                    logger.warning("[RuBeacon] 프레임 전송 실패: ${e.message}")
                    break
                }
            }
        }

        try {
            for (incomingFrame in session.incoming) {
                if (incomingFrame is Frame.Text) {
                    val text = incomingFrame.readText()
                    handleIncomingText(session, text)
                }
            }
        } finally {
            pingJob.cancel()
            sendJob.cancel()
        }
    }

    private suspend fun handleIncomingText(session: DefaultClientWebSocketSession, text: String) {
        try {
            val frame = RuBeaconJson.default.decodeFromString<WebSocketFrame>(text)
            frame.validate()

            when (frame.op) {
                Opcode.PING -> {
                    val pong = WebSocketFrame.pong(traceId = frame.traceId)
                    session.send(Frame.Text(RuBeaconJson.default.encodeToString(pong)))
                }
                Opcode.PONG -> {
                    lastPongReceivedAt.set(System.currentTimeMillis())
                }
                Opcode.COMMAND_REQ -> {
                    val payload = frame.decodePayload<CommandRequestPayload>()
                    val responsePayload = commandHandler(payload, frame.traceId)
                    val responseFrame = WebSocketFrame.commandRes(
                        payload = responsePayload,
                        traceId = frame.traceId
                    )
                    session.send(Frame.Text(RuBeaconJson.default.encodeToString(responseFrame)))
                }
                Opcode.COMMAND_RES -> {
                    logger.info("[RuBeacon] 비정상 수신된 COMMAND_RES 수신 (무시): ${frame.traceId}")
                }
                Opcode.EVENT -> {
                    logger.info("[RuBeacon] 비정상 수신된 EVENT 프레임 (무시): ${frame.traceId}")
                }
            }
        } catch (e: Exception) {
            logger.warning("[RuBeacon] WSS 수신 메시지 파싱/처리 실패: ${e.message}")
        }
    }

    override fun close() {
        if (isRunning.compareAndSet(true, false)) {
            isConnected.set(false)
            connectionJob?.cancel()
            outboundChannel.close()
            httpClient.close()
            logger.info("[RuBeacon] WSS 클라이언트가 종료되었습니다.")
        }
    }
}
