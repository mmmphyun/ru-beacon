package com.rubeacon.common.transport

import com.rubeacon.common.event.EventEnvelope
import com.rubeacon.common.serialization.RuBeaconJson
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class WebSocketFrameSerializationTest {

    private val json = RuBeaconJson.default

    @Test
    fun `PING 및 PONG 프레임 직렬화와 역직렬화가 정상 동작한다`() {
        val ping = WebSocketFrame.ping(traceId = "trc_ping_01", timestamp = 1757318800000L)
        val serializedPing = json.encodeToString(ping)
        val deserializedPing = json.decodeFromString<WebSocketFrame>(serializedPing)

        assertEquals(Opcode.PING, deserializedPing.op)
        assertEquals("trc_ping_01", deserializedPing.traceId)
        assertEquals(1757318800000L, deserializedPing.timestamp)
        assertTrue(deserializedPing.payload.isEmpty())

        val pong = WebSocketFrame.pong(traceId = "trc_pong_01", timestamp = 1757318800001L)
        val serializedPong = json.encodeToString(pong)
        val deserializedPong = json.decodeFromString<WebSocketFrame>(serializedPong)

        assertEquals(Opcode.PONG, deserializedPong.op)
        assertEquals("trc_pong_01", deserializedPong.traceId)
        assertEquals(1757318800001L, deserializedPong.timestamp)
    }

    @Test
    fun `EVENT 프레임에 EventEnvelope 페이로드가 안전하게 포장되고 복원된다`() {
        val envelope = EventEnvelope(
            eventId = "evt_01",
            eventType = "minecraft.player.level_up",
            source = "minecraft",
            sourceInstanceId = "backend-01",
            tenantId = "tenant-01",
            occurredAt = "2026-09-09T00:00:00Z",
            receivedAt = "2026-09-09T00:00:01Z",
            correlationId = "corr_evt_01",
            idempotencyKey = "key_01",
            payload = buildJsonObject { put("level", 50) }
        )

        val frame = WebSocketFrame.event(envelope = envelope, traceId = "trc_evt_01", timestamp = 1757318810000L)
        val serialized = json.encodeToString(frame)
        val deserializedFrame = json.decodeFromString<WebSocketFrame>(serialized)

        assertEquals(Opcode.EVENT, deserializedFrame.op)
        assertEquals("trc_evt_01", deserializedFrame.traceId)

        val restoredEnvelope = deserializedFrame.decodePayload<EventEnvelope>()
        assertEquals(envelope, restoredEnvelope)
    }

    @Test
    fun `COMMAND_REQ 및 COMMAND_RES 페이로드 직렬화가 정상 동작한다`() {
        val reqPayload = CommandRequestPayload(
            requestId = "req_cmd_01",
            command = "give",
            args = listOf("Steve", "diamond", "64")
        )
        val reqFrame = WebSocketFrame.commandReq(
            payload = reqPayload,
            traceId = "trc_cmd_req_01",
            timestamp = 1757318820000L
        )

        val serializedReq = json.encodeToString(reqFrame)
        val deserializedReqFrame = json.decodeFromString<WebSocketFrame>(serializedReq)
        assertEquals(Opcode.COMMAND_REQ, deserializedReqFrame.op)

        val restoredReqPayload = deserializedReqFrame.decodePayload<CommandRequestPayload>()
        assertEquals(reqPayload, restoredReqPayload)

        val resPayload = CommandResponsePayload(
            requestId = "req_cmd_01",
            success = true,
            output = "Gave 64 [Diamond] to Steve"
        )
        val resFrame = WebSocketFrame.commandRes(
            payload = resPayload,
            traceId = "trc_cmd_res_01",
            timestamp = 1757318821000L
        )

        val serializedRes = json.encodeToString(resFrame)
        val deserializedResFrame = json.decodeFromString<WebSocketFrame>(serializedRes)
        assertEquals(Opcode.COMMAND_RES, deserializedResFrame.op)

        val restoredResPayload = deserializedResFrame.decodePayload<CommandResponsePayload>()
        assertEquals(resPayload, restoredResPayload)
    }

    @Test
    fun `미지의 신규 필드가 포함된 WebSocketFrame JSON도 정상 역직렬화된다`() {
        val jsonWithUnknownFields = """
        {
          "op": "PING",
          "trace_id": "trc_ping_future",
          "timestamp": 1757318850000,
          "payload": {},
          "future_transport_header": "test_routing_info",
          "priority": "HIGH"
        }
        """.trimIndent()

        val frame = json.decodeFromString<WebSocketFrame>(jsonWithUnknownFields)
        assertEquals(Opcode.PING, frame.op)
        assertEquals("trc_ping_future", frame.traceId)
        assertEquals(1757318850000L, frame.timestamp)
    }

    @Serializable
    private data class CustomPayload(val message: String, val code: Int)

    @Test
    fun `WebSocketFrame_of 팩토리 메서드로 임의의 DTO를 페이로드로 포장하고 디코딩한다`() {
        val payload = CustomPayload(message = "custom_event", code = 200)
        val frame = WebSocketFrame.of(
            op = Opcode.EVENT,
            traceId = "trc_custom_01",
            payload = payload,
            timestamp = 1757318860000L
        )

        val serialized = json.encodeToString(frame)
        val deserialized = json.decodeFromString<WebSocketFrame>(serialized)

        assertEquals(Opcode.EVENT, deserialized.op)
        val decoded = deserialized.decodePayload<CustomPayload>()
        assertEquals(payload, decoded)
    }

    @Test
    fun `validate()는 정상 프레임에 대해 통과하고 빈 traceId 또는 음수 timestamp에 예외를 발생시킨다`() {
        val validFrame = WebSocketFrame.ping(traceId = "trc_valid")
        validFrame.validate()

        val blankTraceFrame = WebSocketFrame.ping(traceId = "   ")
        assertThrows(IllegalArgumentException::class.java) {
            blankTraceFrame.validate()
        }

        val invalidTimeFrame = WebSocketFrame(
            op = Opcode.PING,
            traceId = "trc_valid",
            timestamp = -1L
        )
        assertThrows(IllegalArgumentException::class.java) {
            invalidTimeFrame.validate()
        }
    }
}

