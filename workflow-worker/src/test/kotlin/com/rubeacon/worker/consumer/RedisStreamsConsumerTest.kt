package com.rubeacon.worker.consumer

import com.rubeacon.common.event.EventEntity
import com.rubeacon.common.event.EventEnvelope
import com.rubeacon.common.redis.RedisNamespaces
import com.rubeacon.common.serialization.RuBeaconJson
import com.rubeacon.worker.BaseWorkerIntegrationTest
import com.rubeacon.worker.db.AuditLogger
import com.rubeacon.worker.engine.ConditionBranchExecutor
import com.rubeacon.worker.engine.DagWorkflowDispatcher
import com.rubeacon.worker.engine.MinecraftDispatchCommandExecutor
import com.rubeacon.worker.engine.WorkflowDefinition
import com.rubeacon.worker.engine.WorkflowNode
import com.rubeacon.worker.engine.WorkflowTrigger
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import redis.clients.jedis.params.XAddParams
import redis.clients.jedis.params.XPendingParams
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RedisStreamsConsumerTest : BaseWorkerIntegrationTest() {

    private lateinit var auditLogger: AuditLogger
    private lateinit var dispatcher: DagWorkflowDispatcher
    private lateinit var consumer: RedisStreamsConsumer
    private val executedWorkflows = mutableListOf<String>()

    @BeforeEach
    fun setUp() {
        cleanupData()
        auditLogger = AuditLogger()
        val executors = listOf(
            MinecraftDispatchCommandExecutor(jedis),
            ConditionBranchExecutor()
        ).associateBy { it.supportedNodeType }

        dispatcher = DagWorkflowDispatcher(executors, auditLogger)

        val testWorkflow = WorkflowDefinition(
            version = 1,
            trigger = WorkflowTrigger("trig", "minecraft.player.level_up", listOf("node_cmd")),
            nodes = listOf(
                WorkflowNode(
                    id = "node_cmd",
                    nodeType = "MINECRAFT_DISPATCH_COMMAND",
                    inputs = mapOf("command" to JsonPrimitive("say hello"))
                )
            )
        )

        consumer = RedisStreamsConsumer(
            jedis = jedis,
            dispatcher = dispatcher,
            workflowLookup = { eventType, _ ->
                if (eventType == "minecraft.player.level_up") {
                    executedWorkflows.add(eventType)
                    testWorkflow
                } else null
            },
            groupName = "test-worker-group",
            consumerName = "test-worker-01"
        )
    }

    @Test
    fun `Redis Streams로부터 이벤트를 소비하고 XACK가 정상 처리되어야 한다`() = runBlocking {
        val tenantId = "tenant_stream_01"
        ensureTenant(tenantId)
        val streamKey = RedisNamespaces.eventsStream(tenantId)

        val event = EventEnvelope(
            eventId = "evt_stream_01",
            eventType = "minecraft.player.level_up",
            source = "minecraft",
            sourceInstanceId = "inst_01",
            tenantId = tenantId,
            occurredAt = "2026-09-09T12:00:00Z",
            receivedAt = "2026-09-09T12:00:01Z",
            correlationId = "corr_stream_01",
            idempotencyKey = "idemp_stream_01",
            actor = EventEntity("minecraft_player", "Steve")
        )

        // 스트림에 이벤트 발행
        val eventJson = RuBeaconJson.default.encodeToString(event)
        jedis.xadd(streamKey, XAddParams.xAddParams(), mapOf("payload" to eventJson))

        // 컨슈머 배치 1회 처리
        val processed = consumer.processBatch(streamKey, count = 10, blockMs = 1000)
        assertEquals(1, processed)
        assertEquals(1, executedWorkflows.size)

        // XACK 완료 검증: Pending 메시지가 0개여야 함
        val pending = jedis.xpending(streamKey, "test-worker-group", XPendingParams.xPendingParams().count(10))
        assertTrue(pending.isEmpty())
    }

    @Test
    fun `런타임 장애 시 XACK되지 않고 Pending 상태가 유지되어야 한다`() = runBlocking {
        val tenantId = "tenant_failure_01"
        ensureTenant(tenantId)
        val streamKey = RedisNamespaces.eventsStream(tenantId)

        // 실패를 유발하는 컨슈머 인스턴스 생성
        val failingConsumer = RedisStreamsConsumer(
            jedis = jedis,
            dispatcher = dispatcher,
            workflowLookup = { _, _ ->
                throw RuntimeException("일시적 데이터베이스 연결 장애")
            },
            groupName = "test-worker-group-fail",
            consumerName = "test-worker-fail-01"
        )

        val event = EventEnvelope(
            eventId = "evt_fail_01",
            eventType = "minecraft.player.level_up",
            source = "minecraft",
            sourceInstanceId = "inst_01",
            tenantId = tenantId,
            occurredAt = "2026-09-09T12:00:00Z",
            receivedAt = "2026-09-09T12:00:01Z",
            correlationId = "corr_fail_01",
            idempotencyKey = "idemp_fail_01",
            actor = EventEntity("minecraft_player", "Steve")
        )

        val eventJson = RuBeaconJson.default.encodeToString(event)
        jedis.xadd(streamKey, XAddParams.xAddParams(), mapOf("payload" to eventJson))

        // 컨슈머 배치 실행 -> 실패 발생
        val processed = failingConsumer.processBatch(streamKey, count = 10, blockMs = 1000)
        assertEquals(0, processed)

        // XACK되지 않고 Pending 목록에 유지되는지 검증
        val pending = jedis.xpending(streamKey, "test-worker-group-fail", XPendingParams.xPendingParams().count(10))
        assertEquals(1, pending.size, "장애 발생 시 메시지가 ACK되지 않고 Pending 상태로 남아있어야 함")
    }

    @Test
    fun `Poison Pill(잘못된 JSON) 수신 시 DLQ로 격리되고 원본 스트림에서 XACK되어야 한다`() = runBlocking {
        val tenantId = "tenant_poison_01"
        ensureTenant(tenantId)
        val streamKey = RedisNamespaces.eventsStream(tenantId)
        val dlqKey = "stream:events:dlq:test"

        val dlqConsumer = RedisStreamsConsumer(
            jedis = jedis,
            dispatcher = dispatcher,
            workflowLookup = { _, _ -> null },
            groupName = "test-worker-group-dlq",
            consumerName = "test-worker-dlq-01",
            dlqStream = dlqKey
        )

        // 깨진 JSON (Poison Pill) 스트림 발행
        val malformedJson = "{ invalid_json: true, broken... "
        jedis.xadd(streamKey, XAddParams.xAddParams(), mapOf("payload" to malformedJson))

        val processed = dlqConsumer.processBatch(streamKey, count = 10, blockMs = 1000)
        assertEquals(0, processed)

        // 1. 원본 스트림에서는 ACK되어 Pending이 없어야 함 (무한 루프 방지)
        val pendingOriginal = jedis.xpending(streamKey, "test-worker-group-dlq", XPendingParams.xPendingParams().count(10))
        assertTrue(pendingOriginal.isEmpty(), "Poison Pill은 원본 스트림에서 ACK되어야 함")

        // 2. DLQ 스트림에 적재되었는지 검증
        val dlqEntries = jedis.xrange(dlqKey, "-", "+", 10)
        assertEquals(1, dlqEntries.size, "DLQ 스트림에 1개의 항목이 저장되어야 함")
        assertEquals(malformedJson, dlqEntries.first().fields["payload"])
        assertTrue(dlqEntries.first().fields["reason"]?.contains("Deserialization failure") == true)
    }

    @Test
    fun `페이로드가 비어있는 메시지는 DLQ로 격리되고 원본 스트림에서 XACK되어야 한다`() = runBlocking {
        val tenantId = "tenant_empty_01"
        ensureTenant(tenantId)
        val streamKey = RedisNamespaces.eventsStream(tenantId)
        val dlqKey = "stream:events:dlq:empty"

        val dlqConsumer = RedisStreamsConsumer(
            jedis = jedis,
            dispatcher = dispatcher,
            workflowLookup = { _, _ -> null },
            groupName = "test-worker-group-empty",
            consumerName = "test-worker-empty-01",
            dlqStream = dlqKey
        )

        jedis.xadd(streamKey, XAddParams.xAddParams(), mapOf("dummy" to "no_payload_here"))

        val processed = dlqConsumer.processBatch(streamKey, count = 10, blockMs = 1000)
        assertEquals(0, processed)

        val pending = jedis.xpending(streamKey, "test-worker-group-empty", XPendingParams.xPendingParams().count(10))
        assertTrue(pending.isEmpty())

        val dlqEntries = jedis.xrange(dlqKey, "-", "+", 10)
        assertEquals(1, dlqEntries.size)
        assertEquals("Payload is null or blank", dlqEntries.first().fields["reason"])
    }

    @Test
    fun `다중 테넌트 스트림으로부터 동시에 이벤트를 소비할 수 있어야 한다`() = runBlocking {
        val tenantA = "tenant_multi_a"
        val tenantB = "tenant_multi_b"
        ensureTenant(tenantA)
        ensureTenant(tenantB)

        val streamA = RedisNamespaces.eventsStream(tenantA)
        val streamB = RedisNamespaces.eventsStream(tenantB)

        val eventA = EventEnvelope(
            eventId = "evt_a_01",
            eventType = "minecraft.player.level_up",
            source = "minecraft",
            sourceInstanceId = "inst_a",
            tenantId = tenantA,
            occurredAt = "2026-09-09T12:00:00Z",
            receivedAt = "2026-09-09T12:00:01Z",
            correlationId = "corr_a_01",
            idempotencyKey = "idemp_a_01",
            actor = EventEntity("minecraft_player", "Alex")
        )

        val eventB = EventEnvelope(
            eventId = "evt_b_01",
            eventType = "minecraft.player.level_up",
            source = "minecraft",
            sourceInstanceId = "inst_b",
            tenantId = tenantB,
            occurredAt = "2026-09-09T12:00:00Z",
            receivedAt = "2026-09-09T12:00:01Z",
            correlationId = "corr_b_01",
            idempotencyKey = "idemp_b_01",
            actor = EventEntity("minecraft_player", "Steve")
        )

        jedis.xadd(streamA, XAddParams.xAddParams(), mapOf("payload" to RuBeaconJson.default.encodeToString(eventA)))
        jedis.xadd(streamB, XAddParams.xAddParams(), mapOf("payload" to RuBeaconJson.default.encodeToString(eventB)))

        // 2개의 서로 다른 테넌트 스트림을 동시 폴링
        val processed = consumer.processBatch(listOf(streamA, streamB), count = 10, blockMs = 1000)
        assertEquals(2, processed, "두 테넌트의 이벤트가 모두 처리되어야 함")
        assertEquals(2, executedWorkflows.size)
    }
}
