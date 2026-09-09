package com.rubeacon.worker.engine

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TarjanCycleDetectorTest {

    @Test
    fun `정상적인 비순환 DAG는 검증을 통과해야 한다`() {
        val definition = WorkflowDefinition(
            version = 1,
            trigger = WorkflowTrigger("trigger_1", "minecraft.player.level_up", listOf("node_branch")),
            nodes = listOf(
                WorkflowNode(
                    id = "node_branch",
                    nodeType = "CONDITION_BRANCH",
                    branches = mapOf(
                        "on_true" to listOf("node_cmd"),
                        "on_false" to listOf("node_msg")
                    )
                ),
                WorkflowNode(
                    id = "node_cmd",
                    nodeType = "MINECRAFT_DISPATCH_COMMAND"
                ),
                WorkflowNode(
                    id = "node_msg",
                    nodeType = "DISCORD_SEND_MESSAGE"
                )
            )
        )

        assertDoesNotThrow {
            TarjanCycleDetector.validate(definition)
        }
    }

    @Test
    fun `단일 노드의 자기 참조 순환은 사이클로 감지되어야 한다`() {
        val definition = WorkflowDefinition(
            version = 1,
            trigger = WorkflowTrigger("trig", "test", listOf("loop_node")),
            nodes = listOf(
                WorkflowNode(
                    id = "loop_node",
                    nodeType = "PARALLEL_BLOCK",
                    nextNodeIds = listOf("loop_node")
                )
            )
        )

        val ex = assertThrows<WorkflowCycleException> {
            TarjanCycleDetector.validate(definition)
        }
        assertTrue(ex.cycleNodes.contains("loop_node"))
    }

    @Test
    fun `다중 노드 간의 상호 참조 사이클은 감지되어야 한다`() {
        val definition = WorkflowDefinition(
            version = 1,
            trigger = WorkflowTrigger("trig", "test", listOf("node_a")),
            nodes = listOf(
                WorkflowNode("node_a", "CUSTOM", nextNodeIds = listOf("node_b")),
                WorkflowNode("node_b", "CUSTOM", nextNodeIds = listOf("node_c")),
                WorkflowNode("node_c", "CUSTOM", nextNodeIds = listOf("node_a"))
            )
        )

        val ex = assertThrows<WorkflowCycleException> {
            TarjanCycleDetector.validate(definition)
        }
        assertEquals(3, ex.cycleNodes.size)
        assertTrue(ex.cycleNodes.containsAll(listOf("node_a", "node_b", "node_c")))
    }

    @Test
    fun `CONDITION_BRANCH 분기를 통한 순환도 감지되어야 한다`() {
        val definition = WorkflowDefinition(
            version = 1,
            trigger = WorkflowTrigger("trig", "test", listOf("cond_1")),
            nodes = listOf(
                WorkflowNode(
                    id = "cond_1",
                    nodeType = "CONDITION_BRANCH",
                    branches = mapOf("on_true" to listOf("action_1"))
                ),
                WorkflowNode(
                    id = "action_1",
                    nodeType = "MINECRAFT_DISPATCH_COMMAND",
                    nextNodeIds = listOf("cond_1") // 사이클 형성
                )
            )
        )

        val ex = assertThrows<WorkflowCycleException> {
            TarjanCycleDetector.validate(definition)
        }
        assertTrue(ex.cycleNodes.contains("cond_1"))
        assertTrue(ex.cycleNodes.contains("action_1"))
    }
}
