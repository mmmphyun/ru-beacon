package com.rubeacon.worker.consumer

import com.rubeacon.common.event.EventEnvelope
import com.rubeacon.common.redis.RedisNamespaces
import com.rubeacon.common.serialization.RuBeaconJson
import com.rubeacon.worker.engine.DagWorkflowDispatcher
import com.rubeacon.worker.engine.WorkflowDefinition
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import redis.clients.jedis.JedisPooled
import redis.clients.jedis.StreamEntryID
import redis.clients.jedis.exceptions.JedisDataException
import redis.clients.jedis.params.XAutoClaimParams
import redis.clients.jedis.params.XReadGroupParams

/**
 * Redis Streams 이벤트 안전 소비 및 복구(XAUTOCLAIM) 컨슈머.
 * docs/MILESTONES.md [마일스톤 4] 및 TRANSPORT_PROTOCOL_SPEC.md §2 규격을 준수함.
 */
class RedisStreamsConsumer(
    private val jedis: JedisPooled,
    private val dispatcher: DagWorkflowDispatcher,
    private val workflowLookup: suspend (eventType: String, tenantId: String) -> WorkflowDefinition?,
    private val groupName: String = RedisNamespaces.GROUP_WORKER,
    private val consumerName: String = "worker_${java.util.UUID.randomUUID().toString().take(8)}"
) {

    /**
     * 컨슈머 그룹이 존재하지 않을 경우 자동 생성함 (MKSTREAM).
     */
    fun ensureConsumerGroup(stream: String) {
        try {
            jedis.xgroupCreate(stream, groupName, StreamEntryID("0-0"), true)
        } catch (e: JedisDataException) {
            if (e.message?.contains("BUSYGROUP") != true) {
                throw e
            }
        }
    }

    /**
     * 단일 배치 이벤트를 폴링하여 DAG 엔진으로 실행 후 XACK를 처리함.
     * 테스트 및 1회성 실행을 위해 단위 실행 가능하도록 분리.
     *
     * @return 처리된 메시지 개수
     */
    suspend fun processBatch(stream: String, count: Int = 10, blockMs: Long = 1000): Int {
        ensureConsumerGroup(stream)

        val streams = mapOf(stream to StreamEntryID.UNRECEIVED_ENTRY)
        val readParams = XReadGroupParams.xReadGroupParams().count(count).block(blockMs.toInt())
        val response = jedis.xreadGroup(groupName, consumerName, readParams, streams) ?: return 0

        var processedCount = 0
        for (streamEntry in response) {
            for (entry in streamEntry.value) {
                if (processEntry(stream, entry)) {
                    processedCount++
                }
            }
        }
        return processedCount
    }

    /**
     * 처리 중 비정상 종료 등으로 고아 상태가 된 Pending 메시지를 XAUTOCLAIM으로 안전하게 복구.
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

    private suspend fun processEntry(stream: String, entry: redis.clients.jedis.resps.StreamEntry): Boolean {
        val payloadJson = entry.fields["payload"] ?: entry.fields["data"] ?: entry.fields.values.firstOrNull()
        if (payloadJson != null) {
            try {
                val event = RuBeaconJson.default.decodeFromString<EventEnvelope>(payloadJson)
                val workflow = workflowLookup(event.eventType, event.tenantId)
                if (workflow != null) {
                    dispatcher.run(workflow, event)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 파싱 오류 혹은 poison pill 격리
            }
        }
        jedis.xack(stream, groupName, entry.id)
        return true
    }

    /**
     * 무한 폴링 루프 실행. 코루틴 Job 취소 시 정상 종료(Graceful Shutdown) 보장.
     */
    fun start(scope: CoroutineScope, stream: String): Job {
        return scope.launch(Dispatchers.IO) {
            ensureConsumerGroup(stream)
            while (isActive) {
                try {
                    processBatch(stream, count = 10, blockMs = 2000)
                    autoClaimStaleMessages(stream, minIdleMs = 60_000, count = 10)
                } catch (e: CancellationException) {
                    break
                } catch (e: Exception) {
                    delay(1000)
                }
            }
        }
    }
}
