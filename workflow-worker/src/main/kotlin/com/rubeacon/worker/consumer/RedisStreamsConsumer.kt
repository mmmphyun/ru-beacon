package com.rubeacon.worker.consumer

import com.rubeacon.common.event.EventEnvelope
import com.rubeacon.common.redis.RedisNamespaces
import com.rubeacon.common.serialization.RuBeaconJson
import com.rubeacon.worker.engine.DagWorkflowDispatcher
import com.rubeacon.worker.engine.WorkflowDefinition
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import redis.clients.jedis.JedisPooled
import redis.clients.jedis.StreamEntryID
import redis.clients.jedis.exceptions.JedisDataException
import redis.clients.jedis.params.XAutoClaimParams
import redis.clients.jedis.params.XPendingParams
import redis.clients.jedis.params.XReadGroupParams

import java.util.concurrent.ConcurrentHashMap
import redis.clients.jedis.params.XAddParams
import com.rubeacon.worker.db.AuditLogger

/**
 * Redis Streams 이벤트 안전 소비 및 복구(XAUTOCLAIM) 컨슈머.
 * docs/MILESTONES.md [마일스톤 4], docs/REFACTORING_PLAN.md [Batch 2] 규격을 준수함.
 */
class RedisStreamsConsumer(
    private val jedis: JedisPooled,
    private val dispatcher: DagWorkflowDispatcher,
    private val workflowLookup: suspend (eventType: String, tenantId: String) -> WorkflowDefinition?,
    private val groupName: String = RedisNamespaces.GROUP_WORKER,
    private val consumerName: String = "worker_${java.util.UUID.randomUUID().toString().take(8)}",
    private val dlqStream: String = DEFAULT_DLQ_STREAM,
    private val maxDeliveries: Long = 3,
    private val auditLogger: AuditLogger? = null
) {
    companion object {
        const val DEFAULT_DLQ_STREAM = "stream:events:dlq"
    }

    private val log = LoggerFactory.getLogger(RedisStreamsConsumer::class.java)
    private val initializedGroups = ConcurrentHashMap.newKeySet<String>()

    /**
     * 컨슈머 그룹이 존재하지 않을 경우 자동 생성함 (MKSTREAM).
     */
    fun ensureConsumerGroup(stream: String) {
        if (initializedGroups.contains(stream)) return
        try {
            jedis.xgroupCreate(stream, groupName, StreamEntryID("0-0"), true)
            initializedGroups.add(stream)
        } catch (e: JedisDataException) {
            if (e.message?.contains("BUSYGROUP") == true) {
                initializedGroups.add(stream)
            } else {
                throw e
            }
        }
    }

    /**
     * 단일 스트림 배치 이벤트를 폴링하여 DAG 엔진으로 실행.
     */
    suspend fun processBatch(stream: String, count: Int = 10, blockMs: Long = 1000): Int =
        processBatch(listOf(stream), count, blockMs)

    /**
     * 다중 스트림 배치 이벤트를 폴링하여 DAG 엔진으로 실행.
     *
     * @return 성공적으로 실행 완료된 메시지 개수
     */
    suspend fun processBatch(streams: List<String>, count: Int = 10, blockMs: Long = 1000): Int {
        if (streams.isEmpty()) return 0
        for (stream in streams) {
            ensureConsumerGroup(stream)
        }

        val streamsMap = streams.associateWith { StreamEntryID.UNRECEIVED_ENTRY }
        val readParams = XReadGroupParams.xReadGroupParams().count(count).block(blockMs.toInt())
        val response = jedis.xreadGroup(groupName, consumerName, readParams, streamsMap) ?: return 0

        var processedCount = 0
        for (streamEntry in response) {
            val streamKey = streamEntry.key
            for (entry in streamEntry.value) {
                if (processEntry(streamKey, entry)) {
                    processedCount++
                }
            }
        }
        return processedCount
    }

    /**
     * 단일 스트림의 고아 Pending 메시지를 XAUTOCLAIM으로 안전하게 복구.
     */
    suspend fun autoClaimStaleMessages(stream: String, minIdleMs: Long = 60_000, count: Int = 10): Int {
        ensureConsumerGroup(stream)

        val params = XAutoClaimParams.xAutoClaimParams().count(count)
        val claimResult = runCatching {
            jedis.xautoclaim(stream, groupName, consumerName, minIdleMs, StreamEntryID("0-0"), params)
        }.getOrNull() ?: return 0

        val entries = claimResult.value ?: return 0
        var recoveredCount = 0

        for (entry in entries) {
            if (processEntry(stream, entry)) {
                recoveredCount++
            }
        }
        return recoveredCount
    }

    /**
     * 다중 스트림의 고아 Pending 메시지들을 XAUTOCLAIM으로 안전하게 복구.
     */
    suspend fun autoClaimStaleMessages(streams: List<String>, minIdleMs: Long = 60_000, count: Int = 10): Int {
        var recoveredCount = 0
        for (stream in streams) {
            recoveredCount += autoClaimStaleMessages(stream, minIdleMs, count)
        }
        return recoveredCount
    }

    /**
     * 단일 스트림 엔트리 처리:
     * - 성공 시: xack 호출 및 true 반환.
     * - Poison Pill(페이로드 null/공백 또는 역직렬화 에러): DLQ로 격리 후 xack 호출 및 false 반환.
     * - 장애 시: Pending 유지 (xack 미수행). 단, maxDeliveries 초과 시 DLQ 격리 후 xack.
     */
    private suspend fun processEntry(stream: String, entry: redis.clients.jedis.resps.StreamEntry): Boolean {
        val payloadJson = entry.fields["payload"] ?: entry.fields["data"]
        val tenantFromStream = stream.removePrefix("stream:events:")
        if (payloadJson.isNullOrBlank()) {
            log.error("Stream entry {} in {} payload is null or blank -> routing to DLQ", entry.id, stream)
            routeToDlqAndAck(stream, entry, payloadJson, "Payload is null or blank", "unknown", tenantFromStream)
            return false
        }

        val event = try {
            RuBeaconJson.default.decodeFromString<EventEnvelope>(payloadJson)
        } catch (e: Exception) {
            log.error("Failed to parse EventEnvelope for entry {} in {} (Poison Pill) -> routing to DLQ: {}", entry.id, stream, e.message)
            routeToDlqAndAck(stream, entry, payloadJson, "Deserialization failure: ${e.message}", "unknown", tenantFromStream)
            return false
        }

        return try {
            log.info("[{}] Processing stream event: type={}, tenant={}, entryId={}", event.correlationId, event.eventType, event.tenantId, entry.id)
            val workflow = workflowLookup(event.eventType, event.tenantId)
            if (workflow != null) {
                dispatcher.run(workflow, event)
            } else {
                log.warn("[{}] No workflow found for event type={}", event.correlationId, event.eventType)
            }
            jedis.xack(stream, groupName, entry.id)
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.error("[{}] Workflow execution failed for entry {} in {}: {}", event.correlationId, entry.id, stream, e.message, e)
            val deliveries = getDeliveryCount(stream, entry.id)
            if (deliveries >= maxDeliveries) {
                log.error("[{}] Entry {} exceeded max deliveries ({}) -> routing to DLQ", event.correlationId, entry.id, maxDeliveries)
                routeToDlqAndAck(stream, entry, payloadJson, "Exceeded max deliveries ($deliveries): ${e.message}", event.correlationId, event.tenantId)
            }
            // 일시적 장애는 ACK하지 않고 Pending 유지
            false
        }
    }

    private fun getDeliveryCount(stream: String, entryId: StreamEntryID): Long {
        return runCatching {
            val pendingEntries = jedis.xpending(
                stream,
                groupName,
                XPendingParams.xPendingParams().count(100)
            )
            pendingEntries.firstOrNull { it.id == entryId }?.deliveredTimes ?: 1L
        }.getOrDefault(1L)
    }

    private suspend fun routeToDlqAndAck(
        stream: String,
        entry: redis.clients.jedis.resps.StreamEntry,
        payloadJson: String?,
        reason: String,
        correlationId: String = "unknown",
        tenantId: String = stream.removePrefix("stream:events:")
    ) {
        try {
            val dlqFields = mutableMapOf(
                "original_stream" to stream,
                "original_entry_id" to entry.id.toString(),
                "reason" to reason,
                "failed_at" to java.time.Instant.now().toString()
            )
            if (payloadJson != null) {
                dlqFields["payload"] = payloadJson
            }
            jedis.xadd(dlqStream, XAddParams.xAddParams().maxLen(RedisNamespaces.STREAM_MAX_LEN).approximateTrimming(), dlqFields)

            // 운영자 감사 로그 영속화 (JSONB 문법 및 외래키 정합성 보장)
            val safeDetailsJson = buildJsonObject {
                put("error_reason", reason)
                put("failure_type", "PERMANENT_FAILURE")
                put("original_stream", stream)
                put("original_entry_id", entry.id.toString())
                if (payloadJson != null) {
                    try {
                        put("payload", RuBeaconJson.default.parseToJsonElement(payloadJson))
                    } catch (_: Exception) {
                        put("raw_payload", payloadJson)
                    }
                }
            }.toString()

            auditLogger?.record(
                tenantId = tenantId,
                correlationId = correlationId,
                actorType = "SYSTEM",
                actorId = "worker_dlq",
                action = "EVENT_ROUTED_TO_DLQ",
                status = "FAILURE",
                detailsJson = safeDetailsJson
            )
        } catch (e: Exception) {
            log.error("Failed to write entry {} to DLQ {}: {}", entry.id, dlqStream, e.message, e)
        } finally {
            try {
                jedis.xack(stream, groupName, entry.id)
            } catch (e: Exception) {
                log.error("Failed to ACK entry {} after routing to DLQ: {}", entry.id, e.message, e)
            }
        }
    }

    /**
     * 다중 스트림 동적 폴링 루프 실행.
     */
    fun start(scope: CoroutineScope, streamsSupplier: () -> List<String>): Job {
        return scope.launch(Dispatchers.IO) {
            while (isActive) {
                try {
                    val streams = streamsSupplier()
                    if (streams.isNotEmpty()) {
                        processBatch(streams, count = 10, blockMs = 2000)
                        autoClaimStaleMessages(streams, minIdleMs = 60_000, count = 10)
                    } else {
                        delay(1000)
                    }
                } catch (e: CancellationException) {
                    break
                } catch (e: Exception) {
                    log.error("RedisStreamsConsumer loop error: {}", e.message, e)
                    delay(1000)
                }
            }
        }
    }

    /**
     * 정적 다중 스트림 폴링 루프 실행.
     */
    fun start(scope: CoroutineScope, streams: List<String>): Job =
        start(scope) { streams }

    /**
     * 단일 스트림 폴링 루프 실행 (하위 호환).
     */
    fun start(scope: CoroutineScope, stream: String): Job =
        start(scope, listOf(stream))
}
