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

    @Test
    fun `동일한 correlation_id를 가진 중복 액션 메시지는 멱등 락에 의해 중복 실행이 차단되어야 한다`() = runBlocking {
        val streamKey = RedisNamespaces.STREAM_DISCORD_ACTIONS
        val group = RedisNamespaces.GROUP_BOT

        runCatching {
            jedis.xgroupCreate(streamKey, group, StreamEntryID("0-0"), true)
        }

        val actionData = mapOf(
            "action" to "SEND_MESSAGE",
            "channel_id" to "1122334455",
            "content" to "중복 테스트 메시지",
            "correlation_id" to "corr_dup_01"
        )

        val id1 = jedis.xadd(streamKey, XAddParams.xAddParams(), actionData)
        val id2 = jedis.xadd(streamKey, XAddParams.xAddParams(), actionData)

        val readParams = XReadGroupParams.xReadGroupParams().count(2).block(1000)
        val readResult = jedis.xreadGroup(group, "test-consumer", readParams, mapOf(streamKey to StreamEntryID.UNRECEIVED_ENTRY))

        val entries = readResult[0].value
        assertEquals(2, entries.size)

        // 2개 엔트리 일괄 처리 (첫 번째는 성공, 두 번째는 멱등성 중복 감지로 스킵되어야 함)
        consumer.processEntries(entries)

        // 두 엔트리 모두 ACK되어 pending은 0건이어야 함
        val pending = jedis.xpending(streamKey, group, XPendingParams.xPendingParams().count(10))
        assertEquals(0, pending.size)

        // 멱등성 키가 생성되어 있어야 함
        val idempKey = "idemp:discord:action:corr_dup_01"
        assertEquals("1", jedis.get(idempKey))
    }
}
