package com.rubeacon.common.event

import com.rubeacon.common.serialization.RuBeaconJson
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class EventEnvelopeSerializationTest {

    private val json = RuBeaconJson.default

    @Test
    fun `표준 이벤트 계약 샘플 JSON을 EventEnvelope 객체로 정상 역직렬화한다`() {
        val sampleJson = """
        {
          "event_id": "evt_01J00000000000000000000000",
          "event_type": "minecraft.player.level_up",
          "source": "minecraft",
          "source_instance_id": "backend-survival-01",
          "tenant_id": "tenant_01J00000000000000000000000",
          "minecraft_network_id": "network_01J00000000000000000000000",
          "occurred_at": "2026-08-21T00:00:00Z",
          "received_at": "2026-08-21T00:00:01Z",
          "correlation_id": "corr_01J00000000000000000000000",
          "causation_id": null,
          "idempotency_key": "tenant:workflow:event:subject",
          "actor": {
            "type": "minecraft_player",
            "id": "11111111-2222-3333-4444-555555555555"
          },
          "subject": {
            "type": "minecraft_player",
            "id": "11111111-2222-3333-4444-555555555555"
          },
          "schema_version": 1,
          "payload": {
            "prev_level": 9,
            "new_level": 10
          }
        }
        """.trimIndent()

        val envelope = json.decodeFromString<EventEnvelope>(sampleJson)

        assertEquals("evt_01J00000000000000000000000", envelope.eventId)
        assertEquals("minecraft.player.level_up", envelope.eventType)
        assertEquals("minecraft", envelope.source)
        assertEquals("backend-survival-01", envelope.sourceInstanceId)
        assertEquals("tenant_01J00000000000000000000000", envelope.tenantId)
        assertEquals("network_01J00000000000000000000000", envelope.minecraftNetworkId)
        assertEquals("2026-08-21T00:00:00Z", envelope.occurredAt)
        assertEquals("2026-08-21T00:00:01Z", envelope.receivedAt)
        assertEquals("corr_01J00000000000000000000000", envelope.correlationId)
        assertNull(envelope.causationId)
        assertEquals("tenant:workflow:event:subject", envelope.idempotencyKey)
        assertNotNull(envelope.actor)
        assertEquals("minecraft_player", envelope.actor?.type)
        assertEquals("11111111-2222-3333-4444-555555555555", envelope.actor?.id)
        assertNotNull(envelope.subject)
        assertEquals(1, envelope.schemaVersion)
        assertEquals("9", envelope.payload["prev_level"].toString())
        assertEquals("10", envelope.payload["new_level"].toString())
    }

    @Test
    fun `옵셔널 필드가 누락된 최소 형태의 EventEnvelope도 정상 직렬화 및 역직렬화된다`() {
        val minimalEnvelope = EventEnvelope(
            eventId = "evt_minimal_01",
            eventType = "system.attendance.reset",
            source = "system",
            sourceInstanceId = "worker-01",
            tenantId = "tenant_global",
            occurredAt = "2026-09-09T00:00:00Z",
            receivedAt = "2026-09-09T00:00:01Z",
            correlationId = "corr_min_01",
            idempotencyKey = "system:reset:2026-09-09",
            payload = buildJsonObject { put("reason", "daily_schedule") }
        )

        val serialized = json.encodeToString(minimalEnvelope)
        val deserialized = json.decodeFromString<EventEnvelope>(serialized)

        assertEquals(minimalEnvelope.eventId, deserialized.eventId)
        assertEquals(minimalEnvelope.eventType, deserialized.eventType)
        assertNull(deserialized.minecraftNetworkId)
        assertNull(deserialized.causationId)
        assertNull(deserialized.actor)
        assertNull(deserialized.subject)
        assertEquals(1, deserialized.schemaVersion)
        assertEquals(minimalEnvelope, deserialized)
    }

    @Test
    fun `미지의 신규 필드가 포함된 JSON도 역직렬화 시 예외 없이 무시된다`() {
        val jsonWithUnknownFields = """
        {
          "event_id": "evt_unknown_01",
          "event_type": "discord.user.message",
          "source": "discord",
          "source_instance_id": "bot-01",
          "tenant_id": "tenant_1",
          "occurred_at": "2026-09-09T00:00:00Z",
          "received_at": "2026-09-09T00:00:01Z",
          "correlation_id": "corr_01",
          "idempotency_key": "idemp_01",
          "schema_version": 2,
          "payload": {},
          "future_field_metadata": "ignored_value",
          "client_version": 999
        }
        """.trimIndent()

        val deserialized = json.decodeFromString<EventEnvelope>(jsonWithUnknownFields)
        assertEquals("evt_unknown_01", deserialized.eventId)
        assertEquals(2, deserialized.schemaVersion)
    }

    @Test
    fun `validate()는 정상 봉투에 대해 예외 없이 성공한다`() {
        val validEnvelope = EventEnvelope(
            eventId = "evt_val_01",
            eventType = "minecraft.player.level_up",
            source = EventEnvelope.SOURCE_MINECRAFT,
            sourceInstanceId = "server-01",
            tenantId = "tenant-01",
            minecraftNetworkId = "net-01",
            occurredAt = "2026-09-09T00:00:00Z",
            receivedAt = "2026-09-09T00:00:01Z",
            correlationId = "corr-01",
            idempotencyKey = "key-01"
        )

        validEnvelope.validate()
    }

    @Test
    fun `validate()는 필수 필드가 공백인 경우 예외를 발생시킨다`() {
        val blankIdEnvelope = EventEnvelope(
            eventId = "   ",
            eventType = "system.ping",
            source = EventEnvelope.SOURCE_SYSTEM,
            sourceInstanceId = "sys-01",
            tenantId = "tenant-01",
            occurredAt = "2026-09-09T00:00:00Z",
            receivedAt = "2026-09-09T00:00:01Z",
            correlationId = "corr-01",
            idempotencyKey = "key-01"
        )

        val ex = assertThrows(IllegalArgumentException::class.java) {
            blankIdEnvelope.validate()
        }
        assertEquals("event_id는 공백일 수 없습니다", ex.message)
    }

    @Test
    fun `validate()는 유효하지 않은 source 타입에 대해 예외를 발생시킨다`() {
        val invalidSourceEnvelope = EventEnvelope(
            eventId = "evt_01",
            eventType = "custom.action",
            source = "unsupported_source",
            sourceInstanceId = "inst-01",
            tenantId = "tenant-01",
            occurredAt = "2026-09-09T00:00:00Z",
            receivedAt = "2026-09-09T00:00:01Z",
            correlationId = "corr-01",
            idempotencyKey = "key-01"
        )

        assertThrows(IllegalArgumentException::class.java) {
            invalidSourceEnvelope.validate()
        }
    }

    @Test
    fun `validate()는 minecraft 소스에서 minecraft_network_id가 누락되면 예외를 발생시킨다`() {
        val missingNetworkEnvelope = EventEnvelope(
            eventId = "evt_01",
            eventType = "minecraft.player.level_up",
            source = EventEnvelope.SOURCE_MINECRAFT,
            sourceInstanceId = "server-01",
            tenantId = "tenant-01",
            minecraftNetworkId = null,
            occurredAt = "2026-09-09T00:00:00Z",
            receivedAt = "2026-09-09T00:00:01Z",
            correlationId = "corr-01",
            idempotencyKey = "key-01"
        )

        val ex = assertThrows(IllegalArgumentException::class.java) {
            missingNetworkEnvelope.validate()
        }
        assertEquals("minecraft source 이벤트는 minecraft_network_id가 필수입니다", ex.message)
    }

    @Serializable
    private data class LevelUpPayload(
        val prevLevel: Int,
        val newLevel: Int
    )

    @Test
    fun `decodePayload()를 통해 JsonObject 페이로드를 타입 안전하게 디코딩한다`() {
        val envelope = EventEnvelope(
            eventId = "evt_01",
            eventType = "minecraft.player.level_up",
            source = EventEnvelope.SOURCE_MINECRAFT,
            sourceInstanceId = "server-01",
            tenantId = "tenant-01",
            minecraftNetworkId = "net-01",
            occurredAt = "2026-09-09T00:00:00Z",
            receivedAt = "2026-09-09T00:00:01Z",
            correlationId = "corr-01",
            idempotencyKey = "key-01",
            payload = buildJsonObject {
                put("prevLevel", 10)
                put("newLevel", 11)
            }
        )

        val payload = envelope.decodePayload<LevelUpPayload>()
        assertEquals(10, payload.prevLevel)
        assertEquals(11, payload.newLevel)
    }
}
