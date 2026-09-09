package com.rubeacon.bot

import com.rubeacon.common.event.EventEnvelope
import com.rubeacon.common.serialization.RuBeaconJson
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class DiscordEventNormalizerTest {

    @Test
    fun `normalizeCommand should create valid EventEnvelope conforming to contract`() {
        val tenantId = "guild_123456789"
        val userId = "user_987654321"
        val commandName = "verify"
        val options = mapOf("code" to "998877")
        val interactionId = "inter_555"

        val envelope = DiscordEventNormalizer.normalizeCommand(
            tenantId = tenantId,
            userId = userId,
            commandName = commandName,
            options = options,
            interactionId = interactionId
        )

        assertEquals("discord.command.executed", envelope.eventType)
        assertEquals("discord", envelope.source)
        assertEquals(tenantId, envelope.tenantId)
        assertEquals(userId, envelope.actor?.id)
        assertEquals("discord_user", envelope.actor?.type)
        assertEquals("discord:interaction:$interactionId", envelope.idempotencyKey)
        assertEquals("corr_discord_$interactionId", envelope.correlationId)

        assertEquals("verify", envelope.payload["command"]?.jsonPrimitive?.content)
        assertEquals("998877", envelope.payload["code"]?.jsonPrimitive?.content)

        // JSON 직렬화 및 역직렬화 호환성 검증
        val json = RuBeaconJson.default.encodeToString(envelope)
        val decoded = RuBeaconJson.default.decodeFromString<EventEnvelope>(json)
        assertEquals(envelope.eventId, decoded.eventId)
        assertEquals(envelope.eventType, decoded.eventType)
    }

    @Test
    fun `normalizeButton should produce discord button clicked event`() {
        val tenantId = "guild_123456789"
        val userId = "user_987654321"
        val customId = "attend_claim_btn"
        val messageId = "msg_888"
        val interactionId = "inter_999"

        val envelope = DiscordEventNormalizer.normalizeButton(
            tenantId = tenantId,
            userId = userId,
            customId = customId,
            messageId = messageId,
            interactionId = interactionId
        )

        assertEquals("discord.button.clicked", envelope.eventType)
        assertEquals(customId, envelope.payload["custom_id"]?.jsonPrimitive?.content)
        assertEquals(messageId, envelope.payload["message_id"]?.jsonPrimitive?.content)
        assertEquals("discord:interaction:$interactionId", envelope.idempotencyKey)
    }

    @Test
    fun `normalizeModal should produce discord modal submitted event`() {
        val tenantId = "guild_123456789"
        val userId = "user_987654321"
        val modalId = "modal_verify_submit"
        val values = mapOf("verification_code" to "123456")
        val interactionId = "inter_modal_111"

        val envelope = DiscordEventNormalizer.normalizeModal(
            tenantId = tenantId,
            userId = userId,
            modalId = modalId,
            values = values,
            interactionId = interactionId
        )

        assertEquals("discord.modal.submitted", envelope.eventType)
        assertEquals(modalId, envelope.payload["modal_id"]?.jsonPrimitive?.content)
        assertEquals("123456", envelope.payload["verification_code"]?.jsonPrimitive?.content)
    }
}
