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
}
