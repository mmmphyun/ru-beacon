package com.rubeacon.common.event

import com.rubeacon.common.serialization.RuBeaconJson
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement

/**
 * 이벤트의 주체(Actor) 또는 대상(Subject)을 식별하는 엔티티 정보.
 *
 * @property type 식별 대상의 유형 (예: "minecraft_player", "discord_user", "system")
 * @property id 해당 도메인에서의 고유 식별자 (UUID, Discord Snowflake ID 등)
 */
@Serializable
data class EventEntity(
    val type: String,
    val id: String
)

/**
 * Ru-Beacon 전역 표준 이벤트 봉투(Envelope).
 *
 * 마인크래프트, 디스코드, 시스템 내부 워크플로 전반에서 전달되는 모든 비즈니스 이벤트의 표준 래퍼.
 * 필드 계약의 세부 사양은 docs/EVENT_CONTRACTS.md를 준수함.
 *
 * @property eventId 프로듀서가 발급하는 전역 고유 식별자 (ULID 또는 UUID 권장)
 * @property eventType 이벤트 종류를 구분하는 안정적인 계층형 이름 ("source.subject.action")
 * @property source 이벤트 발생 원천 도메인 ("minecraft", "discord", "system", "external")
 * @property sourceInstanceId 물리/논리적 발신 인스턴스 ID (플러그인 인스턴스, 서버 컨테이너 등)
 * @property tenantId 멀티테넌트 데이터 및 권한 격리 기준 식별자
 * @property minecraftNetworkId 마인크래프트 관련 이벤트인 경우 네트워크 클러스터 식별자
 * @property occurredAt 원천 발송지 기준 이벤트 발생 시각 (ISO-8601 UTC)
 * @property receivedAt 중앙 Ru-Beacon 인그레스 접수 시각 (ISO-8601 UTC)
 * @property correlationId 동일 유저 플로우 또는 상위 트랜잭션 추적용 상관관계 ID
 * @property causationId 현재 이벤트를 유발한 직전 인과 이벤트/노드 ID (원천 이벤트면 null)
 * @property idempotencyKey 중복 처리 방지를 위한 멱등성 검증 키
 * @property actor 행위를 수행한 주체 엔티티 (시스템 자동 처리 시 null 허용)
 * @property subject 행위의 대상이 된 엔티티 (전역 브로드캐스트 등 대상 부재 시 null 허용)
 * @property schemaVersion 페이로드 하위 호환성을 보장하기 위한 스키마 버전 (기본값 1)
 * @property payload 이벤트 도메인별 세부 데이터 (봉투 필드와의 중복 배제)
 */
@Serializable
data class EventEnvelope(
    @SerialName("event_id") val eventId: String,
    @SerialName("event_type") val eventType: String,
    val source: String,
    @SerialName("source_instance_id") val sourceInstanceId: String,
    @SerialName("tenant_id") val tenantId: String,
    @SerialName("minecraft_network_id") val minecraftNetworkId: String? = null,
    @SerialName("occurred_at") val occurredAt: String,
    @SerialName("received_at") val receivedAt: String,
    @SerialName("correlation_id") val correlationId: String,
    @SerialName("causation_id") val causationId: String? = null,
    @SerialName("idempotency_key") val idempotencyKey: String,
    val actor: EventEntity? = null,
    val subject: EventEntity? = null,
    @SerialName("schema_version") val schemaVersion: Int = 1,
    val payload: JsonObject = buildJsonObject {}
) {
    /**
     * 페이로드를 구체적인 데이터 클래스 타입으로 안전하게 역직렬화함.
     */
    inline fun <reified T> decodePayload(json: Json = RuBeaconJson.default): T =
        json.decodeFromJsonElement(payload)

    /**
     * 외부 경계 접수 시 이벤트 계약의 필수 제약 조건 및 정합성을 검증함.
     * 계약 위반 시 IllegalArgumentException을 발생시킴.
     */
    fun validate() {
        require(eventId.isNotBlank()) { "event_id는 공백일 수 없습니다" }
        require(eventType.isNotBlank()) { "event_type은 공백일 수 없습니다" }
        require(source in VALID_SOURCES) { "유효하지 않은 source: $source (허용: $VALID_SOURCES)" }
        require(sourceInstanceId.isNotBlank()) { "source_instance_id는 공백일 수 없습니다" }
        require(tenantId.isNotBlank()) { "tenant_id는 공백일 수 없습니다" }
        require(occurredAt.isNotBlank()) { "occurred_at은 공백일 수 없습니다" }
        require(receivedAt.isNotBlank()) { "received_at은 공백일 수 없습니다" }
        require(correlationId.isNotBlank()) { "correlation_id는 공백일 수 없습니다" }
        require(idempotencyKey.isNotBlank()) { "idempotency_key는 공백일 수 없습니다" }
        require(schemaVersion >= 1) { "schema_version은 1 이상이어야 합니다: $schemaVersion" }

        if (source == SOURCE_MINECRAFT) {
            require(!minecraftNetworkId.isNullOrBlank()) {
                "minecraft source 이벤트는 minecraft_network_id가 필수입니다"
            }
        }
    }

    companion object {
        const val SOURCE_MINECRAFT = "minecraft"
        const val SOURCE_DISCORD = "discord"
        const val SOURCE_SYSTEM = "system"
        const val SOURCE_EXTERNAL = "external"

        val VALID_SOURCES = setOf(
            SOURCE_MINECRAFT,
            SOURCE_DISCORD,
            SOURCE_SYSTEM,
            SOURCE_EXTERNAL
        )
    }
}
