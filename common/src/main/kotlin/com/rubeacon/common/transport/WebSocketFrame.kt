package com.rubeacon.common.transport

import com.rubeacon.common.event.EventEnvelope
import com.rubeacon.common.serialization.RuBeaconJson
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
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
    PONG,

    @SerialName("DISCORD_DELIVERED")
    DISCORD_DELIVERED
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
    /**
     * 프레임의 JSON 페이로드를 구체적인 데이터 클래스 타입으로 안전하게 역직렬화함.
     */
    inline fun <reified T> decodePayload(json: Json = RuBeaconJson.default): T =
        json.decodeFromJsonElement(payload)

    /**
     * WebSocket 수신 프레임의 최소 유효성(추적 ID 및 타임스탬프)을 검증함.
     */
    fun validate() {
        require(traceId.isNotBlank()) { "trace_id는 공백일 수 없습니다" }
        require(timestamp > 0L) { "timestamp는 양수여야 합니다: $timestamp" }
    }

    companion object {
        /**
         * 임의의 객체를 JsonObject 페이로드로 변환하여 표준 WebSocketFrame을 생성함.
         */
        inline fun <reified T> of(
            op: Opcode,
            traceId: String,
            payload: T,
            timestamp: Long = System.currentTimeMillis(),
            json: Json = RuBeaconJson.default
        ): WebSocketFrame = WebSocketFrame(
            op = op,
            traceId = traceId,
            timestamp = timestamp,
            payload = json.encodeToJsonElement(payload).jsonObject
        )

        fun ping(traceId: String, timestamp: Long = System.currentTimeMillis()): WebSocketFrame =
            WebSocketFrame(op = Opcode.PING, traceId = traceId, timestamp = timestamp)

        fun pong(traceId: String, timestamp: Long = System.currentTimeMillis()): WebSocketFrame =
            WebSocketFrame(op = Opcode.PONG, traceId = traceId, timestamp = timestamp)

        fun event(
            envelope: EventEnvelope,
            traceId: String = envelope.correlationId,
            timestamp: Long = System.currentTimeMillis(),
            json: Json = RuBeaconJson.default
        ): WebSocketFrame = of(
            op = Opcode.EVENT,
            traceId = traceId,
            payload = envelope,
            timestamp = timestamp,
            json = json
        )

        fun commandReq(
            payload: CommandRequestPayload,
            traceId: String,
            timestamp: Long = System.currentTimeMillis(),
            json: Json = RuBeaconJson.default
        ): WebSocketFrame = of(
            op = Opcode.COMMAND_REQ,
            traceId = traceId,
            payload = payload,
            timestamp = timestamp,
            json = json
        )

        fun commandRes(
            payload: CommandResponsePayload,
            traceId: String,
            timestamp: Long = System.currentTimeMillis(),
            json: Json = RuBeaconJson.default
        ): WebSocketFrame = of(
            op = Opcode.COMMAND_RES,
            traceId = traceId,
            payload = payload,
            timestamp = timestamp,
            json = json
        )

        fun discordDelivered(
            payload: DiscordDeliveredPayload,
            traceId: String,
            timestamp: Long = System.currentTimeMillis(),
            json: Json = RuBeaconJson.default
        ): WebSocketFrame = of(
            op = Opcode.DISCORD_DELIVERED,
            traceId = traceId,
            payload = payload,
            timestamp = timestamp,
            json = json
        )
    }
}

/**
 * 디스코드 메시지 전송 성공 후 마인크래프트 클라이언트로 역전송되는 확정(ACK) 페이로드.
 *
 * @property eventId 연계된 원천 비즈니스 이벤트 ID
 * @property eventType 이벤트 종류 ("minecraft.block.break", "minecraft.entity.kill", "minecraft.player.level_up" 등)
 * @property playerName 이벤트를 격발한 플레이어 이름
 * @property messageId 실제 디스코드에 생성된 메시지 Snowflake ID
 * @property channelName 메시지가 게시된 디스코드 채널 이름
 * @property occurredAt 원천 인게임 이벤트 발생 시각 (ISO-8601 UTC)
 * @property confirmedAt 디스코드 REST API 포스팅 완료 시각 (ISO-8601 UTC)
 * @property rttMs 원천 발생부터 디스코드 포스팅 완료까지의 실측 왕복 지연시간(밀리초)
 * @property summary 인게임 지도 카드 및 알림에 표시될 1줄 요약 문구
 */
@Serializable
data class DiscordDeliveredPayload(
    @SerialName("event_id") val eventId: String,
    @SerialName("event_type") val eventType: String,
    @SerialName("player_name") val playerName: String,
    @SerialName("message_id") val messageId: String,
    @SerialName("channel_name") val channelName: String,
    @SerialName("occurred_at") val occurredAt: String,
    @SerialName("confirmed_at") val confirmedAt: String,
    @SerialName("rtt_ms") val rttMs: Long,
    val summary: String
)

/**
 * 다중 API Pod 분산 세션으로 디스코드 확정 알림을 브로드캐스트하는 Redis Pub/Sub 메시지.
 */
@Serializable
data class DiscordDeliveredBroadcastMessage(
    @SerialName("tenant_id") val tenantId: String,
    @SerialName("instance_id") val instanceId: String? = null,
    val payload: DiscordDeliveredPayload
)

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

/**
 * 다중 API Service Pod 간 분산 세션 라우팅을 위해 Redis Pub/Sub으로 브로드캐스트하는 명령어 메시지.
 */
@Serializable
data class CommandBroadcastMessage(
    @SerialName("instance_id") val instanceId: String,
    @SerialName("tenant_id") val tenantId: String? = null,
    @SerialName("request_id") val requestId: String,
    val command: String,
    val args: List<String> = emptyList(),
    @SerialName("correlation_id") val correlationId: String? = null
)

