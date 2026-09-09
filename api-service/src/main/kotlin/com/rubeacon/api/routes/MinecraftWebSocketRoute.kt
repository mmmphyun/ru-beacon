package com.rubeacon.api.routes

import com.rubeacon.api.redis.RedisEventPublisher
import com.rubeacon.api.service.InstanceAuthService
import com.rubeacon.api.service.SessionRegistry
import com.rubeacon.common.serialization.RuBeaconJson
import com.rubeacon.common.transport.Opcode
import com.rubeacon.common.transport.TransportConstants
import com.rubeacon.common.transport.WebSocketFrame
import io.ktor.server.routing.Route
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.serialization.encodeToString

/**
 * 마인크래프트 플러그인용 외부 WSS 인그레스 엔드포인트 라우트.
 *
 * docs/TRANSPORT_PROTOCOL_SPEC.md §1 규격을 준수함:
 * 1. 핸드셰이크 시 인스턴스 토큰 SHA-256 해시 검증 및 실패 시 Close Code 4003 반환
 * 2. 세션 수명주기 동안 온라인 상태 갱신 및 메모리 레지스트리 등록
 * 3. PING 프레임 수신 시 PONG 응답 및 하트비트 타임스탬프 갱신
 * 4. EVENT 프레임 수신 시 테넌트 Redis Streams(`stream:events:{tenant_id}`)로 발행
 */
fun Route.minecraftWebSocketRoutes(
    authService: InstanceAuthService,
    sessionRegistry: SessionRegistry,
    redisPublisher: RedisEventPublisher
) {
    webSocket(TransportConstants.WS_PATH_MINECRAFT_V1) {
        val tenantId = call.request.headers[TransportConstants.HEADER_TENANT_ID]
            ?: call.parameters["tenant_id"]
        val instanceId = call.request.headers[TransportConstants.HEADER_INSTANCE_ID]
            ?: call.parameters["instance_id"]
        val token = call.request.headers[TransportConstants.HEADER_INSTANCE_TOKEN]
            ?: call.parameters["token"]

        // 1. 필수 헤더 누락 또는 인증 실패 시 즉시 세션 종료 (Close Code 4003)
        if (tenantId.isNullOrBlank() || instanceId.isNullOrBlank() || token.isNullOrBlank() ||
            !authService.authenticate(tenantId, instanceId, token)
        ) {
            close(CloseReason(TransportConstants.CLOSE_CODE_FORBIDDEN.toShort(), "인증 실패: 유효하지 않은 인스턴스 토큰"))
            return@webSocket
        }

        // 2. 인증 성공: 세션 등록 및 상태를 ONLINE으로 전이
        sessionRegistry.register(instanceId, this)
        authService.updateStatus(instanceId, "ONLINE", updateHeartbeat = true)

        try {
            for (frame in incoming) {
                if (frame !is Frame.Text) continue
                val text = frame.readText()
                val wsFrame = try {
                    RuBeaconJson.default.decodeFromString<WebSocketFrame>(text)
                } catch (_: Exception) {
                    continue // 잘못된 프레임 무시
                }

                when (wsFrame.op) {
                    Opcode.PING -> {
                        authService.updateStatus(instanceId, "ONLINE", updateHeartbeat = true)
                        val pong = WebSocketFrame.pong(wsFrame.traceId)
                        send(Frame.Text(RuBeaconJson.default.encodeToString(pong)))
                    }

                    Opcode.EVENT -> {
                        // 수신된 비즈니스 이벤트를 Redis Streams로 발행하되, 일시적 Redis 장애 시에도 WSS 세션을 유지하도록 예외 격리
                        try {
                            redisPublisher.publishEvent(tenantId, wsFrame.payload.toString())
                        } catch (_: Exception) {
                            // 장애 격리: 세션 파괴 방지
                        }
                    }

                    else -> {
                        // COMMAND_RES 또는 미지원 프레임 무시
                    }
                }
            }
        } finally {
            // 3. 세션 종료 시 레지스트리에서 제거하고, 현재 세션이 마지막 활성 세션이었을 때만 OFFLINE으로 갱신
            // 재연결 시 이전 세션의 종료 처리가 신규 세션의 ONLINE 상태를 덮어쓰는 레이스 컨디션을 방지함.
            val removed = sessionRegistry.unregister(instanceId, this)
            if (removed) {
                authService.updateStatus(instanceId, "OFFLINE")
            }
        }
    }
}
