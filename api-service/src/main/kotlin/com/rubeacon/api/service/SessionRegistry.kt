package com.rubeacon.api.service

import com.rubeacon.common.serialization.RuBeaconJson
import com.rubeacon.common.transport.WebSocketFrame
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.websocket.Frame
import kotlinx.serialization.encodeToString
import java.util.concurrent.ConcurrentHashMap

/**
 * 활성화된 마인크래프트 인스턴스 WebSocket 세션들을 관리하는 인메모리 레지스트리.
 */
class SessionRegistry {
    private val sessions = ConcurrentHashMap<String, DefaultWebSocketServerSession>()

    /**
     * 신규 인스턴스 세션을 등록함.
     * 동일 인스턴스 ID로 기존 연결이 존재하는 경우 덮어씌움.
     */
    fun register(instanceId: String, session: DefaultWebSocketServerSession) {
        sessions[instanceId] = session
    }

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
}
