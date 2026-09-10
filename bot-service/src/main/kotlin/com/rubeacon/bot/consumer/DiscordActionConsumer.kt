package com.rubeacon.bot.consumer

import com.rubeacon.common.redis.RedisNamespaces
import dev.kord.common.entity.Snowflake
import dev.kord.core.Kord
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import redis.clients.jedis.JedisPooled
import redis.clients.jedis.params.XReadGroupParams
import redis.clients.jedis.resps.StreamEntry
import java.util.UUID

/**
 * Workflow Worker 등으로부터 'stream:discord:actions' 스트림에 인입된 Discord 액션 요청을 소비하여
 * Discord Gateway/Rest 채널로 메시지를 전송하는 컨슈머 루프.
 */
class DiscordActionConsumer(
    private val jedis: JedisPooled,
    private val kord: Kord? = null,
    private val groupName: String = RedisNamespaces.GROUP_BOT,
    private val consumerId: String = "bot-consumer-${UUID.randomUUID().toString().take(8)}"
) {
    private val log = LoggerFactory.getLogger(DiscordActionConsumer::class.java)
    private val streamKey = RedisNamespaces.STREAM_DISCORD_ACTIONS

    @Volatile
    private var isRunning = false
    private var consumerJob: Job? = null

    /**
     * 컨슈머 그룹 초기화 및 백그라운드 소비 코루틴 기동.
     */
    fun start(scope: CoroutineScope = CoroutineScope(Dispatchers.IO)): Job {
        initConsumerGroup()
        isRunning = true

        consumerJob = scope.launch {
            log.info("DiscordActionConsumer 루프 시작: stream={}, group={}, consumer={}", streamKey, groupName, consumerId)

            while (isActive && isRunning) {
                try {
                    // 1. 미처리 보류 메시지 장애 복구 (XAUTOCLAIM)
                    reclaimPendingMessages()

                    // 2. 신규 액션 메시지 수신 (Block 2초 대기)
                    pollNewMessages()
                } catch (e: CancellationException) {
                    log.info("DiscordActionConsumer 코루틴 취소 감지. 루프를 종료합니다.")
                    throw e
                } catch (e: Exception) {
                    log.error("DiscordActionConsumer 처리 루프 예외 발생: {}", e.message, e)
                    delay(1000)
                }
            }
        }
        return consumerJob!!
    }

    /**
     * 컨슈머 루프 안전 중단.
     */
    fun stop() {
        isRunning = false
        consumerJob?.cancel()
    }

    private fun initConsumerGroup() {
        runCatching {
            jedis.xgroupCreate(streamKey, groupName, redis.clients.jedis.StreamEntryID("0-0"), true)
            log.info("Redis Streams Consumer Group 생성 완료: stream={}, group={}", streamKey, groupName)
        }.onFailure { e ->
            if (e.message?.contains("BUSYGROUP") == true) {
                log.debug("Consumer Group 이미 존재: group={}", groupName)
            } else {
                log.warn("Consumer Group 생성 중 경고: {}", e.message)
            }
        }
    }

    private suspend fun reclaimPendingMessages() {
        try {
            val response = jedis.xautoclaim(
                streamKey,
                groupName,
                consumerId,
                60_000L,
                redis.clients.jedis.StreamEntryID("0-0"),
                redis.clients.jedis.params.XAutoClaimParams.xAutoClaimParams().count(10)
            )
            val claimedEntries = response?.value
            if (!claimedEntries.isNullOrEmpty()) {
                log.info("XAUTOCLAIM 미처리 액션 {} 건 복구 점유", claimedEntries.size)
                processEntries(claimedEntries)
            }
        } catch (e: Exception) {
            log.warn("XAUTOCLAIM 처리 실패: {}", e.message)
        }
    }

    private suspend fun pollNewMessages() {
        val streamMap = mapOf(streamKey to redis.clients.jedis.StreamEntryID.UNRECEIVED_ENTRY)
        val readParams = XReadGroupParams.xReadGroupParams().count(10).block(2000)
        val result = jedis.xreadGroup(groupName, consumerId, readParams, streamMap)

        if (result != null && result.isNotEmpty()) {
            for (streamEntryList in result) {
                processEntries(streamEntryList.value)
            }
        }
    }

    internal suspend fun processEntries(entries: List<StreamEntry>) {
        for (entry in entries) {
            val entryId = entry.id
            val fields = entry.fields
            val correlationId = fields["correlation_id"] ?: "unknown"
            try {
                processSingleAction(fields)
                jedis.xack(streamKey, groupName, entryId)
                log.debug("[{}] 액션 메시지 정상 처리 및 ACK 완료: entryId={}", correlationId, entryId)
            } catch (e: dev.kord.rest.request.RestRequestException) {
                log.error("[{}] Discord API 영구 요청 실패(Poison Pill 격리 및 ACK 처리): entryId={}, status={}, error={}", correlationId, entryId, e.status, e.message)
                jedis.xack(streamKey, groupName, entryId)
            } catch (e: Exception) {
                log.error("[{}] 액션 메시지 일시적 처리 실패 (entryId={}): {}", correlationId, entryId, e.message, e)
            }
        }
    }

    internal suspend fun processSingleAction(fields: Map<String, String>) {
        val action = fields["action"] ?: "SEND_MESSAGE"
        val channelIdStr = fields["channel_id"] ?: return
        val content = fields["content"] ?: return
        val correlationId = fields["correlation_id"] ?: "unknown"

        log.info("[{}] Discord 액션 실행: action={}, channelId={}", correlationId, action, channelIdStr)

        if (action == "SEND_MESSAGE") {
            if (kord != null) {
                val channelId = Snowflake(channelIdStr)
                kord.rest.channel.createMessage(channelId) {
                    this.content = content
                }
                log.info("[{}] Discord 채널 메시지 발송 완료: channelId={}", correlationId, channelIdStr)
            } else {
                log.debug("[{}] Kord 미주입(테스트 환경) 모의 메시지 발송 완료: channelId={}, content={}", correlationId, channelIdStr, content)
            }
        }
    }
}
