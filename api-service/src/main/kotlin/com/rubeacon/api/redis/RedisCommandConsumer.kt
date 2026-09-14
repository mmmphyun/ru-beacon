package com.rubeacon.api.redis

import com.rubeacon.api.service.SessionRegistry
import com.rubeacon.common.redis.RedisNamespaces
import com.rubeacon.common.serialization.RuBeaconJson
import com.rubeacon.common.transport.CommandBroadcastMessage
import com.rubeacon.common.transport.CommandRequestPayload
import com.rubeacon.common.transport.WebSocketFrame
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import org.slf4j.LoggerFactory
import redis.clients.jedis.JedisPooled
import redis.clients.jedis.StreamEntryID
import redis.clients.jedis.exceptions.JedisDataException
import redis.clients.jedis.params.XReadGroupParams
import redis.clients.jedis.resps.StreamEntry
import java.util.UUID

/**
 * stream:commands:request 스트림을 Consumer Group(api-ingress-group)으로 폴링하여,
 * 대상 인스턴스 세션이 로컬에 존재하면 즉시 전송하고, 없으면 Redis Pub/Sub으로 브로드캐스트하는 분산 라우터.
 */
class RedisCommandConsumer(
    private val jedis: JedisPooled,
    private val sessionRegistry: SessionRegistry,
    val consumerId: String = "api-consumer-${UUID.randomUUID().toString().take(8)}"
) {
    private val log = LoggerFactory.getLogger(RedisCommandConsumer::class.java)

    /**
     * Consumer Group이 없으면 최초 생성함 (BUSYGROUP 발생 시 안전하게 무시).
     */
    fun ensureConsumerGroup() {
        try {
            jedis.xgroupCreate(
                RedisNamespaces.STREAM_COMMANDS_REQUEST,
                RedisNamespaces.GROUP_API_INGRESS,
                StreamEntryID("0-0"),
                true
            )
        } catch (e: JedisDataException) {
            if (e.message?.contains("BUSYGROUP") != true) {
                throw e
            }
        }
    }

    /**
     * 단일 배치 메시지를 폴링하여 라우팅 처리함 (테스트 및 수동 실행 지원).
     *
     * @return 처리된 메시지 수
     */
    suspend fun processBatch(count: Int = 10, blockMs: Long = 1000): Int {
        ensureConsumerGroup()

        val streams = mapOf(RedisNamespaces.STREAM_COMMANDS_REQUEST to StreamEntryID.UNRECEIVED_ENTRY)
        val readParams = XReadGroupParams.xReadGroupParams().count(count).block(blockMs.toInt())
        val response = jedis.xreadGroup(
            RedisNamespaces.GROUP_API_INGRESS,
            consumerId,
            readParams,
            streams
        ) ?: return 0

        var processedCount = 0
        for (streamResult in response) {
            for (entry in streamResult.value) {
                if (processEntry(entry)) {
                    processedCount++
                }
            }
        }
        return processedCount
    }

    /**
     * 단일 스트림 엔트리를 로컬 세션 또는 Pub/Sub 채널로 라우팅 후 XACK 처리함.
     */
    suspend fun processEntry(entry: StreamEntry): Boolean {
        return try {
            val fields = entry.fields
            val requestId = fields["request_id"] ?: UUID.randomUUID().toString()
            val instanceId = fields["instance_id"] ?: "all"
            val command = fields["command"] ?: ""
            val correlationId = fields["correlation_id"] ?: requestId

            val frame = WebSocketFrame.commandReq(
                payload = CommandRequestPayload(
                    requestId = requestId,
                    command = command
                ),
                traceId = correlationId
            )

            if (instanceId != "all" && sessionRegistry.hasSession(instanceId)) {
                // 1. 대상 인스턴스 세션이 로컬에 존재: 즉시 WebSocket 전송
                sessionRegistry.send(instanceId, frame)
            } else {
                // 2. 로컬에 미존재하거나 "all" 전체 대상: Pub/Sub 브로드캐스트
                val broadcast = CommandBroadcastMessage(
                    instanceId = instanceId,
                    requestId = requestId,
                    command = command,
                    correlationId = correlationId
                )
                withContext(Dispatchers.IO) {
                    jedis.publish(
                        RedisNamespaces.PUBSUB_COMMANDS_BROADCAST,
                        RuBeaconJson.default.encodeToString(broadcast)
                    )
                }
            }

            // 3. 정상 처리 완료 후 ACK
            withContext(Dispatchers.IO) {
                jedis.xack(
                    RedisNamespaces.STREAM_COMMANDS_REQUEST,
                    RedisNamespaces.GROUP_API_INGRESS,
                    entry.id
                )
            }
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.error("RedisCommandConsumer entry 처리 실패: id={}, err={}", entry.id, e.message, e)
            false
        }
    }

    /**
     * 백그라운드 코루틴으로 무한 스트림 폴링 루프를 시작함.
     */
    fun start(scope: CoroutineScope): Job = scope.launch(Dispatchers.IO) {
        ensureConsumerGroup()
        log.info("RedisCommandConsumer 폴링 시작: consumerId={}", consumerId)

        val streams = mapOf(RedisNamespaces.STREAM_COMMANDS_REQUEST to StreamEntryID.UNRECEIVED_ENTRY)
        val readParams = XReadGroupParams.xReadGroupParams().count(10).block(1000)

        while (isActive) {
            try {
                val response = jedis.xreadGroup(
                    RedisNamespaces.GROUP_API_INGRESS,
                    consumerId,
                    readParams,
                    streams
                ) ?: continue

                for (streamResult in response) {
                    for (entry in streamResult.value) {
                        processEntry(entry)
                    }
                }
            } catch (e: CancellationException) {
                break
            } catch (e: Exception) {
                if (!isActive) break
                log.warn("RedisCommandConsumer 폴링 중 일시적 오류: {}", e.message)
            }
        }
        log.info("RedisCommandConsumer 폴링 종료: consumerId={}", consumerId)
    }
}
