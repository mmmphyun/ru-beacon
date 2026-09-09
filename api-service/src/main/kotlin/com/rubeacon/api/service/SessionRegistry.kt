package com.rubeacon.api.service

import com.rubeacon.common.serialization.RuBeaconJson
import com.rubeacon.common.transport.WebSocketFrame
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.websocket.Frame
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import java.util.concurrent.ConcurrentHashMap

/**
 * 활성화된 마인크래프트 인스턴스 WebSocket 세션들을 관리하는 인메모리 레지스트리.
 */
class SessionRegistry {
    private val sessions = ConcurrentHashMap<String, DefaultWebSocketServerSession>()
    private val mutex = Mutex()

    /**
     * 신규 인스턴스 세션을 등록함.
     * 동일 인스턴스 ID로 기존 연결이 존재하는 경우 덮어씌움.
     */
    fun register(instanceId: String, session: DefaultWebSocketServerSession) {
        sessions[instanceId] = session
    }

    /**
     * 연결이 끊긴 인스턴스 세션을 해제함.
     */
    fun unregister(instanceId: String) {
        sessions.remove(instanceId)
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
            unregister(instanceId)
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
}
