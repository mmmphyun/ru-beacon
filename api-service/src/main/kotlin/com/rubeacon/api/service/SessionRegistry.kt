package com.rubeacon.api.service

import com.rubeacon.common.redis.RedisNamespaces
import com.rubeacon.common.serialization.RuBeaconJson
import com.rubeacon.common.transport.CommandBroadcastMessage
import com.rubeacon.common.transport.CommandRequestPayload
import com.rubeacon.common.transport.WebSocketFrame
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.websocket.Frame
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import org.slf4j.LoggerFactory
import redis.clients.jedis.JedisPooled
import redis.clients.jedis.JedisPubSub
import java.util.concurrent.ConcurrentHashMap

/**
 * 활성화된 마인크래프트 인스턴스 WebSocket 세션들을 관리하는 인메모리 레지스트리.
 */
class SessionRegistry {
    private val log = LoggerFactory.getLogger(SessionRegistry::class.java)
    private val sessions = ConcurrentHashMap<String, DefaultWebSocketServerSession>()

    @Volatile
    private var pubSub: JedisPubSub? = null
    private var subscriberJob: Job? = null

    /**
     * 신규 인스턴스 세션을 등록함.
     * 동일 인스턴스 ID로 기존 연결이 존재하는 경우 덮어씌움.
     */
    fun register(instanceId: String, session: DefaultWebSocketServerSession) {
        sessions[instanceId] = session
    }

    /**
     * 특정 인스턴스 세션이 로컬에 존재하는지 확인함.
     */
    fun hasSession(instanceId: String): Boolean = sessions.containsKey(instanceId)

    /**
     * 특정 인스턴스의 활성 세션 객체를 조회함.
     */
    fun getSession(instanceId: String): DefaultWebSocketServerSession? = sessions[instanceId]

    /**
     * 연결이 끊긴 인스턴스 세션을 해제함.
     * 세션 객체가 전달된 경우, 현재 활성 세션과 일치할 때만 원자적으로 제거하여 재연결 시 신규 세션 오삭제를 방지함.
     *
     * @return 실제로 세션이 레지스트리에서 제거되었으면 true
     */
    fun unregister(instanceId: String, session: DefaultWebSocketServerSession? = null): Boolean {
        return if (session != null) {
            sessions.remove(instanceId, session)
        } else {
            sessions.remove(instanceId) != null
        }
    }

    /**
     * 특정 인스턴스로 WebSocketFrame을 비동기 전송함.
     */
    suspend fun send(instanceId: String, frame: WebSocketFrame): Boolean {
        val session = sessions[instanceId] ?: return false
        val text = RuBeaconJson.default.encodeToString(frame)
        return try {
            session.send(Frame.Text(text))
            true
        } catch (_: Exception) {
            unregister(instanceId, session)
            false
        }
    }

    /**
     * 현재 활성화된 세션 수.
     */
    val activeCount: Int
        get() = sessions.size

    /**
     * 등록된 인스턴스 목록.
     */
    val connectedInstanceIds: Set<String>
        get() = sessions.keys

    /**
     * Redis Pub/Sub 브로드캐스트 채널을 구독하여 다른 Pod에서 전달된 명령을 로컬 세션으로 라우팅함.
     */
    fun startBroadcastSubscriber(jedis: JedisPooled, scope: CoroutineScope): Job {
        val ps = object : JedisPubSub() {
            override fun onMessage(channel: String?, message: String?) {
                if (channel != RedisNamespaces.PUBSUB_COMMANDS_BROADCAST || message.isNullOrBlank()) return
                scope.launch {
                    try {
                        val broadcast = RuBeaconJson.default.decodeFromString<CommandBroadcastMessage>(message)
                        val frame = WebSocketFrame.commandReq(
                            payload = CommandRequestPayload(
                                requestId = broadcast.requestId,
                                command = broadcast.command,
                                args = broadcast.args
                            ),
                            traceId = broadcast.correlationId ?: broadcast.requestId
                        )
                        if (broadcast.instanceId == "all") {
                            for (id in sessions.keys) {
                                send(id, frame)
                            }
                        } else if (sessions.containsKey(broadcast.instanceId)) {
                            send(broadcast.instanceId, frame)
                        }
                    } catch (e: Exception) {
                        log.warn("브로드캐스트 명령 수신 처리 실패: {}", e.message)
                    }
                }
            }
        }
        pubSub = ps
        val job = scope.launch(Dispatchers.IO) {
            try {
                jedis.subscribe(ps, RedisNamespaces.PUBSUB_COMMANDS_BROADCAST)
            } catch (_: Exception) {
                // 구독 종료
            }
        }
        subscriberJob = job
        return job
    }

    /**
     * Pub/Sub 브로드캐스트 구독을 해제하고 백그라운드 작업을 중단함.
     */
    fun stopBroadcastSubscriber() {
        try {
            pubSub?.unsubscribe()
        } catch (_: Exception) {}
        subscriberJob?.cancel()
    }
}

