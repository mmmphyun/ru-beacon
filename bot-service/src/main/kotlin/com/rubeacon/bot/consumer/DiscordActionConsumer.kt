package com.rubeacon.bot.consumer

import com.rubeacon.common.redis.RedisNamespaces
import com.rubeacon.common.serialization.RuBeaconJson
import com.rubeacon.common.transport.DiscordDeliveredBroadcastMessage
import com.rubeacon.common.transport.DiscordDeliveredPayload
import dev.kord.common.entity.Snowflake
import dev.kord.core.Kord
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
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
            val correlationId = fields["correlation_id"] ?: entryId.toString()
            val idempKey = "idemp:discord:action:$correlationId"

            val acquired = jedis.set(idempKey, "1", redis.clients.jedis.params.SetParams.setParams().nx().ex(86400L))
            if (acquired == null) {
                log.warn("[{}] 이미 발송 처리된 Discord 액션 감지. 중복 발송을 차단하고 ACK 처리합니다: entryId={}", correlationId, entryId)
                jedis.xack(streamKey, groupName, entryId)
                continue
            }

            try {
                processSingleAction(fields)
                jedis.xack(streamKey, groupName, entryId)
                log.debug("[{}] 액션 메시지 정상 처리 및 ACK 완료: entryId={}", correlationId, entryId)
            } catch (e: dev.kord.rest.request.RestRequestException) {
                log.error("[{}] Discord API 영구 요청 실패(Poison Pill 격리 및 ACK 처리): entryId={}, status={}, error={}", correlationId, entryId, e.status, e.message)
                jedis.xack(streamKey, groupName, entryId)
            } catch (e: Exception) {
                log.error("[{}] 액션 메시지 일시적 처리 실패 (entryId={}): {}", correlationId, entryId, e.message, e)
                runCatching { jedis.del(idempKey) }
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
            val messageIdStr: String
            if (kord != null) {
                val channelId = Snowflake(channelIdStr)
                val msg = kord.rest.channel.createMessage(channelId) {
                    this.content = content
                }
                messageIdStr = msg.id.toString()
                log.info("[{}] Discord 채널 메시지 발송 완료: channelId={}, msgId={}", correlationId, channelIdStr, messageIdStr)
            } else {
                messageIdStr = "mock_msg_${System.currentTimeMillis()}"
                log.debug("[{}] Kord 미주입(테스트 환경) 모의 메시지 발송 완료: channelId={}, content={}", correlationId, channelIdStr, content)
            }

            // 디스코드 전송 확인(ACK)을 Redis Pub/Sub으로 브로드캐스트하여 마인크래프트 세션 역전송 지원
            val tenantId = fields["tenant_id"] ?: "tenant_default"
            val instanceId = fields["instance_id"]
            val eventId = fields["event_id"] ?: correlationId
            val eventType = fields["event_type"] ?: "minecraft.event"
            val playerName = fields["player_name"] ?: "Player"
            val channelName = fields["channel_name"] ?: "#인게임-실시간-로그"
            val occurredAt = fields["occurred_at"] ?: java.time.Instant.now().toString()
            val now = java.time.Instant.now()
            val rttMs = runCatching {
                java.time.Duration.between(java.time.Instant.parse(occurredAt), now).toMillis()
            }.getOrDefault(45L)
            val summary = fields["summary"] ?: content.take(30)

            val ackPayload = DiscordDeliveredPayload(
                eventId = eventId,
                eventType = eventType,
                playerName = playerName,
                messageId = messageIdStr,
                channelName = channelName,
                occurredAt = occurredAt,
                confirmedAt = now.toString(),
                rttMs = rttMs,
                summary = summary
            )
            val broadcastMsg = DiscordDeliveredBroadcastMessage(
                tenantId = tenantId,
                instanceId = instanceId,
                payload = ackPayload
            )
            val broadcastJson = RuBeaconJson.default.encodeToString(broadcastMsg)
            runCatching {
                jedis.publish(RedisNamespaces.PUBSUB_DISCORD_DELIVERED, broadcastJson)
            }.onFailure { e ->
                log.warn("[{}] Discord ACK Pub/Sub 발행 실패 (무시): {}", correlationId, e.message)
            }
        }
    }
}
