package com.rubeacon.worker.engine

import com.rubeacon.common.event.EventEntity
import com.rubeacon.common.event.EventEnvelope
import com.rubeacon.worker.BaseWorkerIntegrationTest
import com.rubeacon.worker.db.AuditLogger
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DagWorkflowDispatcherTest : BaseWorkerIntegrationTest() {

    private lateinit var auditLogger: AuditLogger
    private val counts = ConcurrentHashMap<String, AtomicInteger>()

    @BeforeEach
    fun setUp() {
        cleanupData()
        auditLogger = AuditLogger()
        counts.clear()
    }

    private fun testExecutor(failNode: String? = null) = object : NodeExecutor {
        override val supportedNodeType: String = "TEST_NODE"
        override suspend fun execute(context: WorkflowContext, inputs: Map<String, JsonElement>): NodeResult {
            val id = (inputs["id"] as? JsonPrimitive)?.content ?: "unknown"
            counts.computeIfAbsent(id) { AtomicInteger(0) }.incrementAndGet()
            delay(10)
            return if (id == failNode) NodeResult.Failure("simulated", "ERR") else NodeResult.Success()
        }
    }

    private fun testEvent(tenant: String, corr: String) = EventEnvelope(
        eventId = "evt_$corr", eventType = "test.event", source = "test", sourceInstanceId = "inst-01",
        tenantId = tenant, occurredAt = "2026-09-14T12:00:00Z", receivedAt = "2026-09-14T12:00:01Z",
        correlationId = corr, idempotencyKey = "idemp_$corr", actor = EventEntity("system", "tester")
    )

    private fun node(id: String, next: List<String> = emptyList()) = WorkflowNode(
        id = id, nodeType = "TEST_NODE", inputs = mapOf("id" to JsonPrimitive(id)), nextNodeIds = next
    )

    @Test
    fun `다이아몬드 DAG 합류 노드와 후속 노드는 정확히 1회만 실행되어야 한다`() = runBlocking {
        val tenant = "tenant_diamond_01"
        ensureTenant(tenant)
        val dispatcher = DagWorkflowDispatcher(mapOf("TEST_NODE" to testExecutor()), auditLogger)

        // A -> [B, C], B -> D, C -> D, D -> E
        val wf = WorkflowDefinition(
            trigger = WorkflowTrigger("trig", "test.event", listOf("a")),
            nodes = listOf(
                node("a", listOf("b", "c")),
                node("b", listOf("d")),
                node("c", listOf("d")),
                node("d", listOf("e")),
                node("e")
            )
        )
        val res = dispatcher.run(wf, testEvent(tenant, "corr_diamond_01"))

        assertEquals("SUCCESS", res.status)
        assertEquals(1, counts["a"]?.get())
        assertEquals(1, counts["b"]?.get())
        assertEquals(1, counts["c"]?.get())
        assertEquals(1, counts["d"]?.get(), "합류 노드 D는 1회만 실행")
        assertEquals(1, counts["e"]?.get(), "후속 노드 E는 1회만 실행")
    }

    @Test
    fun `한쪽 브랜치 실패 시 합류 노드는 실행되지 않고 PARTIAL_FAILURE로 종료되어야 한다`() = runBlocking {
        val tenant = "tenant_diamond_fail"
        ensureTenant(tenant)
        val dispatcher = DagWorkflowDispatcher(mapOf("TEST_NODE" to testExecutor(failNode = "b")), auditLogger)

        val wf = WorkflowDefinition(
            trigger = WorkflowTrigger("trig", "test.event", listOf("a")),
            nodes = listOf(node("a", listOf("b", "c")), node("b", listOf("d")), node("c", listOf("d")), node("d"))
        )
        val res = dispatcher.run(wf, testEvent(tenant, "corr_fail"))

        assertEquals("PARTIAL_FAILURE", res.status)
        assertEquals(1, counts["b"]?.get())
        assertEquals(1, counts["c"]?.get())
        assertNull(counts["d"], "B 실패로 In-Degree가 0이 되지 않아 D는 미실행")
    }

    @Test
    fun `조건 분기 후 합류 구조에서 미선택 브랜치가 prune되어 합류 노드가 정상 1회 실행되어야 한다`() = runBlocking {
        val tenant = "tenant_cond_join"
        ensureTenant(tenant)
        val executors = mapOf("CONDITION_BRANCH" to ConditionBranchExecutor(), "TEST_NODE" to testExecutor())
        val dispatcher = DagWorkflowDispatcher(executors, auditLogger)

        val wf = WorkflowDefinition(
            trigger = WorkflowTrigger("trig", "test.event", listOf("cond")),
            nodes = listOf(
                WorkflowNode(
                    id = "cond", nodeType = "CONDITION_BRANCH",
                    inputs = mapOf("left" to JsonPrimitive("10"), "operator" to JsonPrimitive("GREATER_THAN"), "right" to JsonPrimitive("5")),
                    branches = mapOf("on_true" to listOf("b"), "on_false" to listOf("c"))
                ),
                node("b", listOf("d")), node("c", listOf("d")), node("d")
            )
        )
        val res = dispatcher.run(wf, testEvent(tenant, "corr_cond"))

        assertEquals("SUCCESS", res.status)
        assertEquals(1, counts["b"]?.get())
        assertNull(counts["c"])
        assertEquals(1, counts["d"]?.get(), "미선택 브랜치 prune 후 D 1회 실행")
    }

    @Test
    fun `continueOnError가 true인 노드가 실패하더라도 후속 노드는 정상 실행되어야 한다`() = runBlocking {
        val tenant = "tenant_continue_on_err"
        ensureTenant(tenant)
        val dispatcher = DagWorkflowDispatcher(mapOf("TEST_NODE" to testExecutor(failNode = "b")), auditLogger)

        // a -> b (fail, continueOnError=true) -> c
        val wf = WorkflowDefinition(
            trigger = WorkflowTrigger("trig", "test.event", listOf("a")),
            nodes = listOf(
                node("a", listOf("b")),
                WorkflowNode("b", "TEST_NODE", inputs = mapOf("id" to JsonPrimitive("b")), nextNodeIds = listOf("c"), continueOnError = true),
                node("c")
            )
        )
        val res = dispatcher.run(wf, testEvent(tenant, "corr_continue"))

        assertEquals("PARTIAL_FAILURE", res.status)
        assertEquals(listOf("b"), res.failedNodes)
        assertEquals(listOf("a", "c"), res.completedNodes)
        assertEquals(1, counts["c"]?.get(), "b가 실패했으나 continueOnError로 인해 c가 정상 실행되어야 함")
    }

    @Test
    fun `모든 부모가 prune된 합류 노드는 차수가 0이 되더라도 실행되지 않아야 한다`() = runBlocking {
        val tenant = "tenant_all_pruned"
        ensureTenant(tenant)
        val executors = mapOf("CONDITION_BRANCH" to ConditionBranchExecutor(), "TEST_NODE" to testExecutor())
        val dispatcher = DagWorkflowDispatcher(executors, auditLogger)

        val wf = WorkflowDefinition(
            trigger = WorkflowTrigger("trig", "test.event", listOf("cond")),
            nodes = listOf(
                WorkflowNode(
                    id = "cond", nodeType = "CONDITION_BRANCH",
                    inputs = mapOf("left" to JsonPrimitive("10"), "operator" to JsonPrimitive("GREATER_THAN"), "right" to JsonPrimitive("5")),
                    branches = mapOf("on_true" to listOf("b"), "on_false" to listOf("c"))
                ),
                node("b"),
                node("c", listOf("d")),
                node("d")
            )
        )
        val res = dispatcher.run(wf, testEvent(tenant, "corr_prune_guard"))

        assertEquals("SUCCESS", res.status)
        assertEquals(1, counts["b"]?.get())
        assertNull(counts["c"], "c는 미선택으로 미실행")
        assertNull(counts["d"], "c가 prune되어 d 역시 prunedNodes 가드에 의해 절대 실행되지 않아야 함")
    }
}
