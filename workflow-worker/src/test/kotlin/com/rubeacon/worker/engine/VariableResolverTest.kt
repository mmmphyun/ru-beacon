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
}
