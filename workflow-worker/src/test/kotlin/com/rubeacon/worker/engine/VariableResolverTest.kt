package com.rubeacon.worker.engine

import com.rubeacon.common.event.EventEntity
import com.rubeacon.common.event.EventEnvelope
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VariableResolverTest {

    private val sampleEvent = EventEnvelope(
        eventId = "evt_01",
        eventType = "minecraft.player.level_up",
        source = "minecraft",
        sourceInstanceId = "inst_01",
        tenantId = "tenant_test",
        minecraftNetworkId = "net_survival",
        occurredAt = "2026-09-09T12:00:00Z",
        receivedAt = "2026-09-09T12:00:01Z",
        correlationId = "corr_01",
        idempotencyKey = "idemp_01",
        actor = EventEntity("minecraft_player", "Steve")
    )

    @Test
    fun `기본 예약 변수 치환이 올바르게 수행되어야 한다`() {
        val context = WorkflowContext(
            tenantId = "tenant_test",
            correlationId = "corr_01",
            initialEvent = sampleEvent
        )

        val template = "give {User_Nickname} diamond 3 on {Server_Name}"
        val resolved = VariableResolver.resolve(template, context)

        assertEquals("give Steve diamond 3 on net_survival", resolved)
    }

    @Test
    fun `사용자 정의 컨텍스트 변수 치환이 올바르게 수행되어야 한다`() {
        val context = WorkflowContext(
            tenantId = "tenant_test",
            correlationId = "corr_01",
            initialEvent = sampleEvent,
            variables = mapOf("Reward_Item" to "emerald", "Reward_Amount" to 5)
        )

        val template = "give {User_Nickname} {Reward_Item} {Reward_Amount}"
        val resolved = VariableResolver.resolve(template, context)

        assertEquals("give Steve emerald 5", resolved)
    }

    @Test
    fun `선언되지 않은 미정의 변수가 포함된 경우 즉시 예외가 발생해야 한다`() {
        val context = WorkflowContext(
            tenantId = "tenant_test",
            correlationId = "corr_01",
            initialEvent = sampleEvent
        )

        val template = "give {User_Nickname} {Undefined_Item}"
        val ex = assertThrows<IllegalArgumentException> {
            VariableResolver.resolve(template, context)
        }

        assertTrue(ex.message!!.contains("Undefined_Item"))
    }

    @Test
    fun `치환 대상 변수 값에 포함된 개행 문자가 공백으로 정제되어 명령어 인젝션이 차단되어야 한다`() {
        val maliciousNick = "Steve\nop Hacker\n"
        val context = WorkflowContext(
            tenantId = "tenant_test",
            correlationId = "corr_01",
            initialEvent = sampleEvent,
            variables = mapOf("User_Nickname" to maliciousNick)
        )

        val template = "give {User_Nickname} diamond 1"
        val resolved = VariableResolver.resolve(template, context)

        assertEquals("give Steve op Hacker diamond 1", resolved)
        assertTrue(!resolved.contains("\n") && !resolved.contains("\r"))
    }

    @Test
    fun `이벤트 페이로드로부터 newLevel advancementId custom_id 등이 안전하게 예약 변수로 바인딩되어야 한다`() {
        val payloadEvent = sampleEvent.copy(
            payload = kotlinx.serialization.json.buildJsonObject {
                put("newLevel", kotlinx.serialization.json.JsonPrimitive(30))
                put("playerName", kotlinx.serialization.json.JsonPrimitive("Alex"))
                put("advancementId", kotlinx.serialization.json.JsonPrimitive("minecraft:story/mine_diamond"))
                put("custom_id", kotlinx.serialization.json.JsonPrimitive("reward_claim_btn"))
                put("command", kotlinx.serialization.json.JsonPrimitive("daily"))
            }
        )
        val context = WorkflowContext(
            tenantId = "tenant_test",
            correlationId = "corr_02",
            initialEvent = payloadEvent
        )

        val template = "Player {User_Nickname} reached level {Level} with {Advancement} button={Custom_ID} cmd={Command}"
        val resolved = VariableResolver.resolve(template, context)

        assertEquals(
            "Player Alex reached level 30 with minecraft:story/mine_diamond button=reward_claim_btn cmd=daily",
            resolved
        )
    }

    @Test
    fun `위저드 형태의 field 및 expected_value 조건 분기가 정상 평가되어야 한다`() = kotlinx.coroutines.test.runTest {
        val payloadEvent = sampleEvent.copy(
            payload = kotlinx.serialization.json.buildJsonObject {
                put("newLevel", kotlinx.serialization.json.JsonPrimitive(42))
            }
        )
        val context = WorkflowContext(
            tenantId = "tenant_test",
            correlationId = "corr_03",
            initialEvent = payloadEvent
        )

        val executor = ConditionBranchExecutor()
        val inputs = mapOf(
            "field" to kotlinx.serialization.json.JsonPrimitive("Level"),
            "operator" to kotlinx.serialization.json.JsonPrimitive("GREATER_OR_EQUAL"),
            "expected_value" to kotlinx.serialization.json.JsonPrimitive("30")
        )

        val result = executor.execute(context, inputs) as NodeResult.Success
        assertEquals("on_true", result.branch)
        assertEquals(true, result.outputVariables["condition_result"])
    }
}
