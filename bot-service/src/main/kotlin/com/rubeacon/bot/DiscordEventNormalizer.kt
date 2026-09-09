package com.rubeacon.bot

import com.rubeacon.common.event.EventEntity
import com.rubeacon.common.event.EventEnvelope
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant
import java.util.UUID

/**
 * Discord Gateway 인터랙션(슬래시 명령어, 버튼, 모달)을 Ru-Beacon 표준 이벤트 봉투(EventEnvelope)로 정규화하는 엔진.
 * docs/EVENT_CONTRACTS.md §4.2 참조.
 */
object DiscordEventNormalizer {

    private const val SOURCE = "discord"
    private const val SOURCE_INSTANCE_ID = "bot-service-01"

    /**
     * 슬래시 명령어 실행 이벤트를 EventEnvelope로 정규화한다.
     *
     * @param tenantId Discord 길드 ID 또는 기본 테넌트 ID
     * @param userId 명령어를 실행한 Discord 사용자 ID
     * @param commandName 실행된 명령어 이름 (예: "verify", "attend")
     * @param options 전달된 인자 맵
     * @param interactionId Discord Interaction Snowflake ID (멱등 키 기반)
     */
    fun normalizeCommand(
        tenantId: String,
        userId: String,
        commandName: String,
        options: Map<String, String> = emptyMap(),
        interactionId: String = UUID.randomUUID().toString()
    ): EventEnvelope {
        val now = Instant.now().toString()
        val payload = buildJsonObject {
            put("command", commandName)
            put("user_id", userId)
            options.forEach { (k, v) -> put(k, v) }
        }

        return EventEnvelope(
            eventId = UUID.randomUUID().toString(),
            eventType = "discord.command.executed",
            source = SOURCE,
            sourceInstanceId = SOURCE_INSTANCE_ID,
            tenantId = tenantId,
            occurredAt = now,
            receivedAt = now,
            correlationId = "corr_discord_$interactionId",
            causationId = null,
            idempotencyKey = "discord:interaction:$interactionId",
            actor = EventEntity(type = "discord_user", id = userId),
            subject = EventEntity(type = "discord_user", id = userId),
            schemaVersion = 1,
            payload = payload
        )
    }

    /**
     * 버튼 클릭 인터랙션 이벤트를 EventEnvelope로 정규화한다.
     *
     * @param tenantId Discord 길드 ID 또는 기본 테넌트 ID
     * @param userId 버튼을 클릭한 Discord 사용자 ID
     * @param customId 버튼 컴포넌트의 커스텀 ID (예: "attend_claim_btn", "reward_claim_btn:101")
     * @param messageId 컴포넌트가 부착된 Discord 메시지 ID
     * @param interactionId Discord Interaction Snowflake ID
     */
    fun normalizeButton(
        tenantId: String,
        userId: String,
        customId: String,
        messageId: String? = null,
        interactionId: String = UUID.randomUUID().toString()
    ): EventEnvelope {
        val now = Instant.now().toString()
        val payload = buildJsonObject {
            put("custom_id", customId)
            put("user_id", userId)
            if (messageId != null) {
                put("message_id", messageId)
            }
        }

        return EventEnvelope(
            eventId = UUID.randomUUID().toString(),
            eventType = "discord.button.clicked",
            source = SOURCE,
            sourceInstanceId = SOURCE_INSTANCE_ID,
            tenantId = tenantId,
            occurredAt = now,
            receivedAt = now,
            correlationId = "corr_discord_$interactionId",
            causationId = null,
            idempotencyKey = "discord:interaction:$interactionId",
            actor = EventEntity(type = "discord_user", id = userId),
            subject = EventEntity(type = "discord_user", id = userId),
            schemaVersion = 1,
            payload = payload
        )
    }

    /**
     * 모달 제출 인터랙션 이벤트를 EventEnvelope로 정규화한다.
     *
     * @param tenantId Discord 길드 ID 또는 기본 테넌트 ID
     * @param userId 모달을 제출한 Discord 사용자 ID
     * @param modalId 모달의 커스텀 ID (예: "modal_verify_submit")
     * @param values 모달 입력 필드 키-값 쌍
     * @param interactionId Discord Interaction Snowflake ID
     */
    fun normalizeModal(
        tenantId: String,
        userId: String,
        modalId: String,
        values: Map<String, String> = emptyMap(),
        interactionId: String = UUID.randomUUID().toString()
    ): EventEnvelope {
        val now = Instant.now().toString()
        val payload = buildJsonObject {
            put("modal_id", modalId)
            put("user_id", userId)
            values.forEach { (k, v) -> put(k, v) }
        }

        return EventEnvelope(
            eventId = UUID.randomUUID().toString(),
            eventType = "discord.modal.submitted",
            source = SOURCE,
            sourceInstanceId = SOURCE_INSTANCE_ID,
            tenantId = tenantId,
            occurredAt = now,
            receivedAt = now,
            correlationId = "corr_discord_$interactionId",
            causationId = null,
            idempotencyKey = "discord:interaction:$interactionId",
            actor = EventEntity(type = "discord_user", id = userId),
            subject = EventEntity(type = "discord_user", id = userId),
            schemaVersion = 1,
            payload = payload
        )
    }
}
