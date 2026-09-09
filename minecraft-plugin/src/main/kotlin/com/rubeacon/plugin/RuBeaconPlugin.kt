package com.rubeacon.plugin

import com.rubeacon.common.transport.CommandRequestPayload
import com.rubeacon.common.transport.CommandResponsePayload
import com.rubeacon.common.transport.WebSocketFrame
import com.rubeacon.plugin.client.RuBeaconWebSocketClient
import com.rubeacon.plugin.config.RuBeaconConfig
import com.rubeacon.plugin.listener.MinecraftEventListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import org.bukkit.plugin.java.JavaPlugin
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.coroutines.resume

/**
 * Ru-Beacon Paper 마인크래프트 메인 플러그인.
 *
 * DEC-071 및 DEC-072 규격에 따라 비동기 WSS I/O와 메인 틱 동기 명령어 디스패치를 격리 및 보장함.
 * MockBukkit 프록시 서브클래싱 지원을 위해 open 선언됨.
 */
open class RuBeaconPlugin : JavaPlugin() {

    private val pluginJob = SupervisorJob()
    private val pluginScope = CoroutineScope(pluginJob + Dispatchers.IO)

    lateinit var ruConfig: RuBeaconConfig
        private set

    var webSocketClient: RuBeaconWebSocketClient? = null
        private set

    // 테스트 검증 및 최근 이벤트 디버깅용 아웃바운드 프레임 큐 (최대 50개 유지, 메모리 누수 방지)
    private val outboundQueue = ConcurrentLinkedQueue<WebSocketFrame>()

    companion object {
        private const val MAX_OUTBOUND_HISTORY = 50
    }

    override fun onEnable() {
        saveDefaultConfig()
        ruConfig = loadConfiguration()

        logger.info("[RuBeacon] 플러그인 활성화 중 (Tenant: ${ruConfig.tenantId}, Instance: ${ruConfig.instanceId})")

        // 1. WebSocket 클라이언트 초기화
        webSocketClient = RuBeaconWebSocketClient(
            config = ruConfig,
            scope = pluginScope,
            logger = logger,
            commandHandler = { payload, _ -> executeCommandOnMainTick(payload) }
        ).apply {
            start()
        }

        // 2. 이벤트 리스너 등록
        val listener = MinecraftEventListener(ruConfig) { frame ->
            enqueueOutboundFrame(frame)
        }
        server.pluginManager.registerEvents(listener, this)

        logger.info("[RuBeacon] 플러그인 활성화 완료.")
    }

    override fun onDisable() {
        logger.info("[RuBeacon] 플러그인 비활성화 중...")
        webSocketClient?.close()
        pluginScope.cancel()
        outboundQueue.clear()
        logger.info("[RuBeacon] 플러그인이 정상적으로 종료되었습니다.")
    }

    /**
     * 외부(이벤트 등)에서 생성된 프레임을 큐에 넣고 WSS 클라이언트로 전달함.
     * 메모리 누수를 방지하기 위해 최대 50개의 최근 프레임만 보관함.
     */
    fun enqueueOutboundFrame(frame: WebSocketFrame) {
        while (outboundQueue.size >= MAX_OUTBOUND_HISTORY) {
            outboundQueue.poll()
        }
        outboundQueue.add(frame)
        webSocketClient?.let { client ->
            pluginScope.launch {
                client.sendFrame(frame)
            }
        }
    }

    /**
     * 테스트 및 모니터링용 아웃바운드 큐 복사본 반환.
     */
    fun getOutboundQueue(): List<WebSocketFrame> = outboundQueue.toList()

    /**
     * WSS에서 수신된 COMMAND_REQ를 메인 틱 루프로 디스패치하여 안전하게 실행함.
     *
     * DEC-071: IllegalStateException: Asynchronous entity track 크래시 원천 방지.
     */
    suspend fun executeCommandOnMainTick(payload: CommandRequestPayload): CommandResponsePayload {
        if (!isEnabled) {
            return CommandResponsePayload(
                requestId = payload.requestId,
                success = false,
                output = "Plugin is disabled"
            )
        }
        return suspendCancellableCoroutine { continuation ->
            if (server.isPrimaryThread) {
                val result = runDispatchCommand(payload)
                continuation.resume(result)
            } else {
                server.scheduler.runTask(this, Runnable {
                    val result = runDispatchCommand(payload)
                    continuation.resume(result)
                })
            }
        }
    }

    private fun runDispatchCommand(payload: CommandRequestPayload): CommandResponsePayload {
        val fullCommand = if (payload.args.isEmpty()) {
            payload.command
        } else {
            "${payload.command} ${payload.args.joinToString(" ")}"
        }

        return try {
            val success = server.dispatchCommand(server.consoleSender, fullCommand)
            CommandResponsePayload(
                requestId = payload.requestId,
                success = success,
                output = if (success) "Executed: $fullCommand" else "Failed to execute: $fullCommand"
            )
        } catch (e: Exception) {
            logger.severe("[RuBeacon] 명령어 실행 중 예외 발생: $fullCommand, error=${e.message}")
            CommandResponsePayload(
                requestId = payload.requestId,
                success = false,
                output = "Error: ${e.message}"
            )
        }
    }

    private fun loadConfiguration(): RuBeaconConfig {
        val cfg = config
        return RuBeaconConfig(
            serverUrl = cfg.getString("server-url") ?: "ws://localhost:8080/ws/minecraft/v1",
            tenantId = cfg.getString("tenant-id") ?: "tenant_default",
            networkId = cfg.getString("network-id") ?: "net_default",
            instanceId = cfg.getString("instance-id") ?: "inst_default",
            instanceToken = cfg.getString("instance-token") ?: "token_default",
            pingIntervalMs = cfg.getLong("ping-interval-ms", 30_000L),
            reconnectInitialDelayMs = cfg.getLong("reconnect-initial-delay-ms", 1_000L),
            reconnectMaxDelayMs = cfg.getLong("reconnect-max-delay-ms", 60_000L),
            enabled = cfg.getBoolean("enabled", true)
        )
    }
}
