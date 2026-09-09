package com.rubeacon.common.event

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class EventEnvelopeSerializationTest {

    private val json = Json {
        prettyPrint = false
        ignoreUnknownKeys = true
    }

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
}
