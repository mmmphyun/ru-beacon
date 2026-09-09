package com.rubeacon.bot

import com.rubeacon.common.event.EventEnvelope
import com.rubeacon.common.redis.RedisNamespaces
import com.rubeacon.common.serialization.RuBeaconJson
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import redis.clients.jedis.StreamEntryID
import redis.clients.jedis.params.XReadParams
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class DiscordEventPublisherTest : BaseBotIntegrationTest() {

    private lateinit var publisher: DiscordEventPublisher

    @BeforeEach
    fun setUp() {
        publisher = DiscordEventPublisher(jedis)
    }

    @Test
    fun `publish should write event to redis streams and allow parsing back`() {
        val tenantId = "test_guild_${UUID.randomUUID().toString().take(6)}"
        val envelope = DiscordEventNormalizer.normalizeCommand(
            tenantId = tenantId,
            userId = "user_111",
            commandName = "verify",
            options = mapOf("code" to "123456")
        )

        val entryId = publisher.publish(envelope)
        assertNotNull(entryId)

        val streamKey = RedisNamespaces.eventsStream(tenantId)
        val streamLength = jedis.xlen(streamKey)
        assertEquals(1L, streamLength)

        val readStreams = mapOf(streamKey to StreamEntryID("0-0"))
        val readResult = jedis.xread(XReadParams.xReadParams().count(1), readStreams)
        assertNotNull(readResult)
        assertEquals(1, readResult.size)

        val entry = readResult[0].value[0]
        assertEquals(envelope.eventId, entry.fields["event_id"])
        assertEquals("discord.command.executed", entry.fields["event_type"])

        val payloadJson = entry.fields["payload"]
        assertNotNull(payloadJson)
        val decoded = RuBeaconJson.default.decodeFromString<EventEnvelope>(payloadJson)
        assertEquals(envelope.eventId, decoded.eventId)
        assertEquals(tenantId, decoded.tenantId)
        assertEquals("verify", decoded.payload["command"]?.let {
            kotlinx.serialization.json.JsonPrimitive(it.toString()).content.replace("\"", "")
        })
    }
}
