package com.rubeacon.worker

import com.rubeacon.common.event.EventEntity
import com.rubeacon.common.event.EventEnvelope
import com.rubeacon.common.redis.RedisNamespaces
import com.rubeacon.worker.db.AttendanceQuotas
import com.rubeacon.worker.db.AuditLogger
import com.rubeacon.worker.db.AuditLogs
import com.rubeacon.worker.db.RewardReservations
import com.rubeacon.worker.engine.AttendanceReservationExecutor
import com.rubeacon.worker.engine.ConditionBranchExecutor
import com.rubeacon.worker.engine.DagWorkflowDispatcher
import com.rubeacon.worker.engine.DiscordSendMessageExecutor
import com.rubeacon.worker.engine.MinecraftDispatchCommandExecutor
import com.rubeacon.worker.engine.WorkflowDefinition
import com.rubeacon.worker.engine.WorkflowNode
import com.rubeacon.worker.engine.WorkflowTrigger
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WorkflowTemplateIntegrationTest : BaseWorkerIntegrationTest() {

    private lateinit var auditLogger: AuditLogger
    private lateinit var mcCommandExecutor: MinecraftDispatchCommandExecutor
    private lateinit var discordMessageExecutor: DiscordSendMessageExecutor
    private lateinit var conditionExecutor: ConditionBranchExecutor
    private lateinit var attendanceExecutor: AttendanceReservationExecutor
    private lateinit var dispatcher: DagWorkflowDispatcher

    @BeforeEach
    fun setUp() {
        cleanupData()
        auditLogger = AuditLogger()
        mcCommandExecutor = MinecraftDispatchCommandExecutor(jedis)
        discordMessageExecutor = DiscordSendMessageExecutor(jedis)
        conditionExecutor = ConditionBranchExecutor()
        attendanceExecutor = AttendanceReservationExecutor()

        val executors = listOf(
            mcCommandExecutor,
            discordMessageExecutor,
            conditionExecutor,
            attendanceExecutor
        ).associateBy { it.supportedNodeType }

        dispatcher = DagWorkflowDispatcher(executors, auditLogger)
    }

    @Test
    fun `템플릿 A - 마인크래프트 레벨 30 달성 시 다이아몬드 지급 명령이 발행되고 감사 로그가 저장되어야 한다`() = runBlocking {
        val tenantId = "tenant_mc_01"
        val correlationId = "corr_level_up_01"
        ensureTenant(tenantId)

        // 레벨업 템플릿 A 정의
        val templateA = WorkflowDefinition(
            version = 1,
            trigger = WorkflowTrigger("trig_level", "minecraft.player.level_up", listOf("node_check_level")),
            nodes = listOf(
                WorkflowNode(
                    id = "node_check_level",
                    nodeType = "CONDITION_BRANCH",
                    inputs = mapOf(
                        "left" to JsonPrimitive("{Player_Level}"),
                        "operator" to JsonPrimitive("GREATER_THAN_OR_EQUAL"),
                        "right" to JsonPrimitive("30")
                    ),
                    branches = mapOf(
                        "on_true" to listOf("node_give_diamond"),
                        "on_false" to listOf("node_alert_msg")
                    )
                ),
                WorkflowNode(
                    id = "node_give_diamond",
                    nodeType = "MINECRAFT_DISPATCH_COMMAND",
                    inputs = mapOf(
                        "command" to JsonPrimitive("give {User_Nickname} diamond 3"),
                        "instance_id" to JsonPrimitive("survival-01")
                    )
                ),
                WorkflowNode(
                    id = "node_alert_msg",
                    nodeType = "DISCORD_SEND_MESSAGE",
                    inputs = mapOf(
                        "channel_id" to JsonPrimitive("123456789"),
                        "message" to JsonPrimitive("레벨이 부족합니다.")
                    )
                )
            )
        )

        val levelUpEvent = EventEnvelope(
            eventId = "evt_lvl_01",
            eventType = "minecraft.player.level_up",
            source = "minecraft",
            sourceInstanceId = "survival-01",
            tenantId = tenantId,
            minecraftNetworkId = "main_net",
            occurredAt = "2026-09-09T12:00:00Z",
            receivedAt = "2026-09-09T12:00:01Z",
            correlationId = correlationId,
            idempotencyKey = "idemp_lvl_01",
            actor = EventEntity("minecraft_player", "Alex")
        )

        // 레벨 30 변수 주입하여 실행
        val contextEvent = levelUpEvent
        val definitionWithVars = templateA.copy(variables = mapOf("Player_Level" to "30"))

        val summary = dispatcher.run(definitionWithVars, contextEvent)

        assertEquals("SUCCESS", summary.status)
        assertTrue(summary.completedNodes.contains("node_check_level"))
        assertTrue(summary.completedNodes.contains("node_give_diamond"))

        // Redis Streams commands:request 검증
        val messages = jedis.xrange(RedisNamespaces.STREAM_COMMANDS_REQUEST, "-", "+")
        assertTrue(messages.isNotEmpty())
        val lastMsg = messages.last().fields
        assertEquals("give Alex diamond 3", lastMsg["command"])
        assertEquals("survival-01", lastMsg["instance_id"])

        // DB 감사 로그 단 1회 기록 검증
        val logs = transaction(database) {
            AuditLogs.selectAll().where { AuditLogs.correlationId eq correlationId }.toList()
        }
        assertEquals(1, logs.size)
        assertEquals("SUCCESS", logs.first()[AuditLogs.status])
    }

    @Test
    fun `템플릿 B - 일일 출석 선착순 100명 원자적 예약 및 101번째 요청 차단 검증`() = runBlocking {
        val tenantId = "tenant_attend_01"
        val limit = 100
        ensureTenant(tenantId)

        val templateB = WorkflowDefinition(
            version = 1,
            trigger = WorkflowTrigger("trig_attend", "discord.button.clicked", listOf("node_reserve")),
            nodes = listOf(
                WorkflowNode(
                    id = "node_reserve",
                    nodeType = "ATTENDANCE_RESERVATION",
                    inputs = mapOf(
                        "action" to JsonPrimitive("RESERVE"),
                        "total_limit" to JsonPrimitive(limit.toString())
                    ),
                    nextNodeIds = listOf("node_commit")
                ),
                WorkflowNode(
                    id = "node_commit",
                    nodeType = "ATTENDANCE_RESERVATION",
                    inputs = mapOf(
                        "action" to JsonPrimitive("COMMIT")
                    )
                )
            )
        )

        // 100명 선착순 동시 요청 (코루틴 병렬 실행)
        val jobs = (1..limit).map { i ->
            async {
                val playerUuid = UUID.randomUUID().toString()
                val event = EventEnvelope(
                    eventId = "evt_att_$i",
                    eventType = "discord.button.clicked",
                    source = "discord",
                    sourceInstanceId = "bot_01",
                    tenantId = tenantId,
                    occurredAt = "2026-09-09T12:00:00Z",
                    receivedAt = "2026-09-09T12:00:01Z",
                    correlationId = "corr_att_$i",
                    idempotencyKey = "idemp_att_$i",
                    actor = EventEntity("minecraft_player", playerUuid)
                )
                dispatcher.run(templateB, event)
            }
        }

        val summaries = jobs.awaitAll()
        val successCount = summaries.count { it.status == "SUCCESS" }
        assertEquals(100, successCount)

        // 101번째 초과 요청 실행
        val overflowEvent = EventEnvelope(
            eventId = "evt_att_101",
            eventType = "discord.button.clicked",
            source = "discord",
            sourceInstanceId = "bot_01",
            tenantId = tenantId,
            occurredAt = "2026-09-09T12:00:00Z",
            receivedAt = "2026-09-09T12:00:01Z",
            correlationId = "corr_att_101",
            idempotencyKey = "idemp_att_101",
            actor = EventEntity("minecraft_player", UUID.randomUUID().toString())
        )
        val overflowSummary = dispatcher.run(templateB, overflowEvent)
        assertEquals("FAILURE", overflowSummary.status)
        assertTrue(overflowSummary.failedNodes.contains("node_reserve"))

        // DB 쿼터 상태 검증: reservedCount == 100, committedCount == 100
        val quotaRecord = transaction(database) {
            AttendanceQuotas.selectAll().where {
                (AttendanceQuotas.tenantId eq tenantId) and (AttendanceQuotas.rewardDate eq LocalDate.now())
            }.single()
        }
        assertEquals(100, quotaRecord[AttendanceQuotas.reservedCount])
        assertEquals(100, quotaRecord[AttendanceQuotas.committedCount])
    }

    @Test
    fun `템플릿 B - 예약 성공 후 중간 단계 실패 시 롤백(RELEASE)이 수행되어 카운트가 복구되어야 한다`() = runBlocking {
        val tenantId = "tenant_rollback_01"
        val playerUuid = UUID.randomUUID().toString()
        val correlationId = "corr_rollback_01"
        ensureTenant(tenantId)

        // 실패를 유발하는 잘못된 명령 노드가 포함된 롤백 흐름 템플릿
        val rollbackTemplate = WorkflowDefinition(
            version = 1,
            trigger = WorkflowTrigger("trig", "test", listOf("node_reserve")),
            nodes = listOf(
                WorkflowNode(
                    id = "node_reserve",
                    nodeType = "ATTENDANCE_RESERVATION",
                    inputs = mapOf("action" to JsonPrimitive("RESERVE"), "total_limit" to JsonPrimitive("10")),
                    nextNodeIds = listOf("node_fail_cmd")
                ),
                WorkflowNode(
                    id = "node_fail_cmd",
                    nodeType = "MINECRAFT_DISPATCH_COMMAND",
                    inputs = emptyMap(), // command 누락 -> Failure 유발
                    nextNodeIds = listOf("node_commit")
                ),
                WorkflowNode(
                    id = "node_commit",
                    nodeType = "ATTENDANCE_RESERVATION",
                    inputs = mapOf("action" to JsonPrimitive("COMMIT"))
                )
            )
        )

        val event = EventEnvelope(
            eventId = "evt_rb_01",
            eventType = "test",
            source = "test",
            sourceInstanceId = "inst",
            tenantId = tenantId,
            occurredAt = "2026-09-09T12:00:00Z",
            receivedAt = "2026-09-09T12:00:01Z",
            correlationId = correlationId,
            idempotencyKey = "idemp_rb_01",
            actor = EventEntity("minecraft_player", playerUuid)
        )

        val summary = dispatcher.run(rollbackTemplate, event)
        assertEquals("PARTIAL_FAILURE", summary.status)
        assertTrue(summary.completedNodes.contains("node_reserve"))
        assertTrue(summary.failedNodes.contains("node_fail_cmd"))

        // 보상 트랜잭션 롤백 실행 (RELEASE)
        val releaseResult = attendanceExecutor.execute(
            com.rubeacon.worker.engine.WorkflowContext(tenantId, correlationId, event, mapOf("Minecraft_UUID" to playerUuid)),
            mapOf("action" to JsonPrimitive("RELEASE"))
        )
        assertTrue(releaseResult is com.rubeacon.worker.engine.NodeResult.Success)

        // 쿼터 reservedCount가 1에서 0으로 복구되었는지 검증
        val quota = transaction(database) {
            AttendanceQuotas.selectAll().where {
                (AttendanceQuotas.tenantId eq tenantId) and (AttendanceQuotas.rewardDate eq LocalDate.now())
            }.single()
        }
        assertEquals(0, quota[AttendanceQuotas.reservedCount])

        val reservation = transaction(database) {
            RewardReservations.selectAll().where {
                (RewardReservations.tenantId eq tenantId) and (RewardReservations.playerUuid eq UUID.fromString(playerUuid))
            }.single()
        }
        assertEquals("RELEASED", reservation[RewardReservations.status])
    }

    @Test
    fun `동일 플레이어가 당일 2회 출석 보상 예약 시도 시 ALREADY_RESERVED 에러 코드로 즉시 차단되어야 한다`() = runBlocking {
        val tenantId = "tenant_duplicate_01"
        val playerUuid = UUID.randomUUID().toString()
        val correlationId1 = "corr_dup_01"
        val correlationId2 = "corr_dup_02"
        ensureTenant(tenantId)

        val template = WorkflowDefinition(
            version = 1,
            trigger = WorkflowTrigger("trig", "test", listOf("node_reserve")),
            nodes = listOf(
                WorkflowNode(
                    id = "node_reserve",
                    nodeType = "ATTENDANCE_RESERVATION",
                    inputs = mapOf("action" to JsonPrimitive("RESERVE"), "total_limit" to JsonPrimitive("10"))
                )
            )
        )

        val event1 = EventEnvelope(
            eventId = "evt_dup_01",
            eventType = "test",
            source = "test",
            sourceInstanceId = "inst",
            tenantId = tenantId,
            occurredAt = "2026-09-09T12:00:00Z",
            receivedAt = "2026-09-09T12:00:01Z",
            correlationId = correlationId1,
            idempotencyKey = "idemp_dup_01",
            actor = EventEntity("minecraft_player", playerUuid)
        )

        // 1회차 예약 성공
        val summary1 = dispatcher.run(template, event1)
        assertEquals("SUCCESS", summary1.status)

        // 2회차 동일 플레이어 동일 일자 중복 예약 시도
        val event2 = event1.copy(
            eventId = "evt_dup_02",
            correlationId = correlationId2,
            idempotencyKey = "idemp_dup_02"
        )
        val summary2 = dispatcher.run(template, event2)
        assertEquals("FAILURE", summary2.status)
        assertTrue(summary2.failedNodes.contains("node_reserve"))

        // 감사 로그에 실패 원인과 status가 정확히 기록되었는지 확인
        val auditLog = transaction(database) {
            AuditLogs.selectAll().where { AuditLogs.correlationId eq correlationId2 }.single()
        }
        assertEquals("FAILURE", auditLog[AuditLogs.status])
        assertTrue(auditLog[AuditLogs.details].contains("node_reserve"))
    }

    @Test
    fun `쿼터 RELEASE 중복 호출 시 언더플로우가 방어되어 reserved_count가 0 이하로 내려가지 않아야 한다`() = runBlocking {
        val tenantId = "tenant_underflow_01"
        val playerUuid = UUID.randomUUID().toString()
        val correlationId = "corr_underflow_01"
        ensureTenant(tenantId)

        val event = EventEnvelope(
            eventId = "evt_uf_01",
            eventType = "test",
            source = "test",
            sourceInstanceId = "inst",
            tenantId = tenantId,
            occurredAt = "2026-09-09T12:00:00Z",
            receivedAt = "2026-09-09T12:00:01Z",
            correlationId = correlationId,
            idempotencyKey = "idemp_uf_01",
            actor = EventEntity("minecraft_player", playerUuid)
        )

        val context = com.rubeacon.worker.engine.WorkflowContext(tenantId, correlationId, event, mapOf("Minecraft_UUID" to playerUuid))

        // 예약 1회
        attendanceExecutor.execute(context, mapOf("action" to JsonPrimitive("RESERVE"), "total_limit" to JsonPrimitive("10")))

        // 1회차 릴리즈 -> 0으로 감소
        attendanceExecutor.execute(context, mapOf("action" to JsonPrimitive("RELEASE")))

        // 2회차 무효 릴리즈 시도 -> 음수로 떨어지지 않고 0 유지
        attendanceExecutor.execute(context, mapOf("action" to JsonPrimitive("RELEASE")))

        val quota = transaction(database) {
            AttendanceQuotas.selectAll().where {
                (AttendanceQuotas.tenantId eq tenantId) and (AttendanceQuotas.rewardDate eq LocalDate.now())
            }.single()
        }
        assertEquals(0, quota[AttendanceQuotas.reservedCount])
    }
}
