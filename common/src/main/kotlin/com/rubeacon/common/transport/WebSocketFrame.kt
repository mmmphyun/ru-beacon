package com.rubeacon.common.transport

import com.rubeacon.common.event.EventEnvelope
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject

/**
 * Ru-Beacon 외부 WSS 통신 프레임의 연산 코드(Opcode).
 */
@Serializable
enum class Opcode {
    @SerialName("EVENT")
    EVENT,

    @SerialName("COMMAND_REQ")
    COMMAND_REQ,

    @SerialName("COMMAND_RES")
    COMMAND_RES,

    @SerialName("PING")
    PING,

    @SerialName("PONG")
    PONG
}

/**
 * 마인크래프트 플러그인과 중앙 API 서버 간 아웃바운드 WebSocket 전송 프레임 DTO.
 *
 * docs/TRANSPORT_PROTOCOL_SPEC.md §1.2 규격을 따르며 UTF-8 JSON 텍스트 프레임으로 직렬화됨.
 *
 * @property op 프레임의 목적을 나타내는 Opcode
 * @property traceId 분산 환경 트랜잭션 추적 식별자
 * @property timestamp 에포크 밀리초 단위의 프레임 생성 시각
 * @property payload Opcode에 따른 가변 JSON 페이로드
 */
@Serializable
data class WebSocketFrame(
    val op: Opcode,
    @SerialName("trace_id") val traceId: String,
    val timestamp: Long,
    val payload: JsonObject = buildJsonObject {}
) {
    companion object {
        private val defaultJson = Json { ignoreUnknownKeys = true }

        fun ping(traceId: String, timestamp: Long = System.currentTimeMillis()): WebSocketFrame =
            WebSocketFrame(op = Opcode.PING, traceId = traceId, timestamp = timestamp)

        fun pong(traceId: String, timestamp: Long = System.currentTimeMillis()): WebSocketFrame =
            WebSocketFrame(op = Opcode.PONG, traceId = traceId, timestamp = timestamp)

        fun event(
            envelope: EventEnvelope,
            traceId: String = envelope.correlationId,
            timestamp: Long = System.currentTimeMillis(),
            json: Json = defaultJson
        ): WebSocketFrame = WebSocketFrame(
            op = Opcode.EVENT,
            traceId = traceId,
            timestamp = timestamp,
            payload = json.encodeToJsonElement(envelope).jsonObject
        )

        fun commandReq(
            payload: CommandRequestPayload,
            traceId: String,
            timestamp: Long = System.currentTimeMillis(),
            json: Json = defaultJson
        ): WebSocketFrame = WebSocketFrame(
            op = Opcode.COMMAND_REQ,
            traceId = traceId,
            timestamp = timestamp,
            payload = json.encodeToJsonElement(payload).jsonObject
        )

        fun commandRes(
            payload: CommandResponsePayload,
            traceId: String,
            timestamp: Long = System.currentTimeMillis(),
            json: Json = defaultJson
        ): WebSocketFrame = WebSocketFrame(
            op = Opcode.COMMAND_RES,
            traceId = traceId,
            timestamp = timestamp,
            payload = json.encodeToJsonElement(payload).jsonObject
        )
    }
}

/**
 * 중앙 서버가 마인크래프트 플러그인으로 메인 틱 실행을 위임하는 명령어 요청 페이로드.
 *
 * @property requestId 결과 매칭 및 멱등성 검증용 요청 ID
 * @property command 실행할 콘솔/플러그인 명령어 문자열
 * @property args 명령어 인자 목록
 */
@Serializable
data class CommandRequestPayload(
    @SerialName("request_id") val requestId: String,
    val command: String,
    val args: List<String> = emptyList()
)

/**
 * 마인크래프트 플러그인이 메인 틱 명령어 실행 후 중앙 서버로 반환하는 응답 페이로드.
 *
 * @property requestId 대응하는 CommandRequestPayload의 requestId
 * @property success 명령어 정상 완수 여부
 * @property output 실행 콘솔 출력 또는 오류 메시지 요약
 */
@Serializable
data class CommandResponsePayload(
    @SerialName("request_id") val requestId: String,
    val success: Boolean,
    val output: String = ""
)
