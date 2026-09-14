package com.rubeacon.worker

import com.rubeacon.common.serialization.RuBeaconJson
import com.rubeacon.worker.db.Workflows
import com.rubeacon.worker.db.WorkflowVersions
import com.rubeacon.worker.engine.WorkflowDefinition
import com.rubeacon.worker.engine.WorkflowNode
import com.rubeacon.worker.engine.WorkflowTrigger
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonPrimitive
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.insertIgnore
import org.jetbrains.exposed.sql.update
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class CachedWorkflowLookupTest : BaseWorkerIntegrationTest() {

    private val tenantId = "tenant_cache_test"

    @BeforeEach
    fun setUp() {
        cleanupData()
        ensureTenant(tenantId)
    }

    private fun insertWorkflow(
        id: String,
        eventType: String,
        status: String = "ACTIVE"
    ): WorkflowDefinition {
        val def = WorkflowDefinition(
            version = 1,
            trigger = WorkflowTrigger("trig_1", eventType, listOf("node_1")),
            nodes = listOf(
                WorkflowNode("node_1", "MINECRAFT_DISPATCH_COMMAND", mapOf("command" to JsonPrimitive("say hello")))
            )
        )
        val jsonStr = RuBeaconJson.default.encodeToString(def)

        transaction(database) {
            val wfId = "wf_$id"
            Workflows.insertIgnore {
                it[Workflows.id] = wfId
                it[Workflows.tenantId] = this@CachedWorkflowLookupTest.tenantId
                it[name] = "Workflow $id"
                it[activeVersion] = 1
            }
            WorkflowVersions.insert {
                it[WorkflowVersions.id] = id
                it[workflowId] = wfId
                it[WorkflowVersions.tenantId] = this@CachedWorkflowLookupTest.tenantId
                it[version] = 1
                it[WorkflowVersions.status] = status
                it[definition] = jsonStr
            }
        }
        return def
    }

    @Test
    fun `동일한 이벤트에 대한 2차 조회 시 DB를 재조회하지 않고 Caffeine 캐시에서 반환되어야 한다`() {
        insertWorkflow("wf_ver_1", "minecraft.player.level_up")

        val missCounter = AtomicInteger(0)
        val lookup = CachedWorkflowLookup(
            database = database,
            maxSize = 100,
            expireDuration = Duration.ofMinutes(5),
            onCacheMiss = { missCounter.incrementAndGet() }
        )

        // 1차 조회: Cache Miss -> DB 조회
        val firstResult = lookup("minecraft.player.level_up", tenantId)
        assertNotNull(firstResult)
        assertEquals("minecraft.player.level_up", firstResult.trigger.eventType)
        assertEquals(1, missCounter.get())

        // 2차 조회: Cache Hit -> DB 조회 건너뜀
        val secondResult = lookup("minecraft.player.level_up", tenantId)
        assertNotNull(secondResult)
        assertEquals(firstResult.trigger.eventType, secondResult.trigger.eventType)
        assertEquals(1, missCounter.get(), "2차 조회 시에는 캐시 히트되어 onCacheMiss가 호출되지 않아야 함")
    }

    @Test
    fun `캐시 무효화(invalidate) 후에는 DB를 다시 조회해야 한다`() {
        insertWorkflow("wf_ver_2", "minecraft.player.join")

        val missCounter = AtomicInteger(0)
        val lookup = CachedWorkflowLookup(
            database = database,
            maxSize = 100,
            expireDuration = Duration.ofMinutes(5),
            onCacheMiss = { missCounter.incrementAndGet() }
        )

        val first = lookup("minecraft.player.join", tenantId)
        assertNotNull(first)
        assertEquals(1, missCounter.get())

        // 캐시 무효화
        lookup.invalidate(tenantId, "minecraft.player.join")

        // 3차 조회: 캐시 무효화 후이므로 다시 Miss 발생
        val reloaded = lookup("minecraft.player.join", tenantId)
        assertNotNull(reloaded)
        assertEquals(2, missCounter.get(), "무효화 후 조회 시 onCacheMiss가 다시 호출되어야 함")
    }

    @Test
    fun `존재하지 않는 워크플로우는 null을 반환하고 빈 결과도 캐싱되어 반복적 DB 부하를 방지해야 한다`() {
        val missCounter = AtomicInteger(0)
        val lookup = CachedWorkflowLookup(
            database = database,
            maxSize = 100,
            expireDuration = Duration.ofMinutes(5),
            onCacheMiss = { missCounter.incrementAndGet() }
        )

        val first = lookup("non_existent_event", tenantId)
        assertNull(first)
        assertEquals(1, missCounter.get())

        val second = lookup("non_existent_event", tenantId)
        assertNull(second)
        assertEquals(1, missCounter.get(), "존재하지 않는 이벤트도 캐싱되어 DB 재조회를 방지해야 함")
    }

    @Test
    fun `버전 불일치 감지 시 캐시를 강제 무효화하고 최신 워크플로우 버전을 DB에서 갱신해야 한다`() {
        val wfId = "wf_version_guard"
        val eventType = "minecraft.player.death"

        // 버전 1 삽입 및 캐싱
        val defV1 = WorkflowDefinition(
            version = 1,
            trigger = WorkflowTrigger("trig_1", eventType, listOf("node_1")),
            nodes = listOf(WorkflowNode("node_1", "MINECRAFT_DISPATCH_COMMAND", mapOf("cmd" to JsonPrimitive("v1"))))
        )
        transaction(database) {
            Workflows.insertIgnore {
                it[id] = wfId
                it[tenantId] = this@CachedWorkflowLookupTest.tenantId
                it[name] = "Version Test"
                it[activeVersion] = 1
            }
            WorkflowVersions.insert {
                it[id] = "${wfId}_v1"
                it[workflowId] = wfId
                it[tenantId] = this@CachedWorkflowLookupTest.tenantId
                it[version] = 1
                it[status] = "ACTIVE"
                it[definition] = RuBeaconJson.default.encodeToString(defV1)
            }
        }

        val missCounter = AtomicInteger(0)
        val lookup = CachedWorkflowLookup(
            database = database,
            maxSize = 100,
            expireDuration = Duration.ofMinutes(5),
            onCacheMiss = { missCounter.incrementAndGet() }
        )

        // 1차 조회 -> v1 캐싱
        val loadedV1 = lookup(eventType, tenantId)
        assertNotNull(loadedV1)
        assertEquals(1, loadedV1.version)
        assertEquals(1, missCounter.get())

        // DB에서 버전 2 활성화 (기존 v1은 INACTIVE로 변경)
        val defV2 = WorkflowDefinition(
            version = 2,
            trigger = WorkflowTrigger("trig_1", eventType, listOf("node_1")),
            nodes = listOf(WorkflowNode("node_1", "MINECRAFT_DISPATCH_COMMAND", mapOf("cmd" to JsonPrimitive("v2"))))
        )
        transaction(database) {
            WorkflowVersions.update({ (WorkflowVersions.workflowId eq wfId) and (WorkflowVersions.version eq 1) }) {
                it[status] = "INACTIVE"
            }
            WorkflowVersions.insert {
                it[id] = "${wfId}_v2"
                it[workflowId] = wfId
                it[tenantId] = this@CachedWorkflowLookupTest.tenantId
                it[version] = 2
                it[status] = "ACTIVE"
                it[definition] = RuBeaconJson.default.encodeToString(defV2)
            }
        }

        // Pub/Sub Invalidation이 유실되었으나 이벤트 봉투에 expectedVersion=2가 명시된 상황 시뮬레이션
        val guarded = lookup.getOrRefreshIfVersionMismatch(eventType, tenantId, expectedVersion = 2)
        assertNotNull(guarded)
        assertEquals(2, guarded.version, "버전 불일치 감지 후 v2로 정상 갱신되어야 함")
        assertEquals(2, missCounter.get(), "불일치 시 캐시 무효화 후 DB 재조회가 발생해야 함")
    }
}
