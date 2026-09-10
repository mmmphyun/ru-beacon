package com.rubeacon.bot.consumer

import com.rubeacon.bot.BaseBotIntegrationTest
import com.rubeacon.common.redis.RedisNamespaces
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import redis.clients.jedis.StreamEntryID
import redis.clients.jedis.params.XAddParams
import redis.clients.jedis.params.XPendingParams
import redis.clients.jedis.params.XReadGroupParams
import kotlin.test.assertEquals

class DiscordActionConsumerTest : BaseBotIntegrationTest() {

    private lateinit var consumer: DiscordActionConsumer

    @BeforeEach
    fun setUp() {
        jedis.del(RedisNamespaces.STREAM_DISCORD_ACTIONS)
        consumer = DiscordActionConsumer(jedis = jedis, kord = null)
    }

    @Test
    fun `processSingleAction should process message and ack successfully`() = runBlocking {
        val streamKey = RedisNamespaces.STREAM_DISCORD_ACTIONS
        val group = RedisNamespaces.GROUP_BOT

        runCatching {
            jedis.xgroupCreate(streamKey, group, StreamEntryID("0-0"), true)
        }

        // Action 메시지 발행
        jedis.xadd(
            streamKey,
            XAddParams.xAddParams(),
            mapOf(
                "action" to "SEND_MESSAGE",
                "channel_id" to "1122334455",
                "content" to "환영합니다! 마인크래프트와 연동되었습니다.",
                "tenant_id" to "test_guild",
                "correlation_id" to "corr_test_01"
            )
        )

        // ReadGroup으로 메시지 인입 후 처리
        val readParams = XReadGroupParams.xReadGroupParams().count(1).block(1000)
        val readResult = jedis.xreadGroup(group, "test-consumer", readParams, mapOf(streamKey to StreamEntryID.UNRECEIVED_ENTRY))

        val entries = readResult[0].value
        assertEquals(1, entries.size)

        // 메시지 처리 및 ACK
        consumer.processEntries(entries)

        // XACK 완료 확인 (Pending 메시지 수가 0이어야 함)
        val pending = jedis.xpending(streamKey, group, XPendingParams.xPendingParams().count(10))
        assertEquals(0, pending.size)
    }

    @Test
    fun `processSingleAction should gracefully handle missing channelId or content without crashing`() = runBlocking {
        val streamKey = RedisNamespaces.STREAM_DISCORD_ACTIONS
        val group = RedisNamespaces.GROUP_BOT

        runCatching {
            jedis.xgroupCreate(streamKey, group, StreamEntryID("0-0"), true)
        }

        // channel_id가 누락된 비정상 메시지
        jedis.xadd(
            streamKey,
            XAddParams.xAddParams(),
            mapOf("action" to "SEND_MESSAGE")
        )

        val readParams = XReadGroupParams.xReadGroupParams().count(1).block(1000)
        val readResult = jedis.xreadGroup(group, "test-consumer", readParams, mapOf(streamKey to StreamEntryID.UNRECEIVED_ENTRY))

        val entries = readResult[0].value
        consumer.processEntries(entries)

        // 오류 없이 정상 처리(스킵) 및 ACK되어 pending 0건이어야 함
        val pending = jedis.xpending(streamKey, group, XPendingParams.xPendingParams().count(10))
        assertEquals(0, pending.size)
    }
}
