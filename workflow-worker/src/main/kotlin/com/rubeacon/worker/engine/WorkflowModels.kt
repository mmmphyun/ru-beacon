package com.rubeacon.worker.engine

import com.rubeacon.common.event.EventEnvelope
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class WorkflowTrigger(
    val id: String,
    @SerialName("event_type") val eventType: String,
    @SerialName("next_node_ids") val nextNodeIds: List<String> = emptyList()
)

@Serializable
data class WorkflowNode(
    val id: String,
    @SerialName("node_type") val nodeType: String,
    val inputs: Map<String, JsonElement> = emptyMap(),
    val branches: Map<String, List<String>>? = null,
    @SerialName("next_node_ids") val nextNodeIds: List<String> = emptyList()
)

@Serializable
data class WorkflowDefinition(
    val version: Int = 1,
    val variables: Map<String, String> = emptyMap(),
    val trigger: WorkflowTrigger,
    val nodes: List<WorkflowNode> = emptyList()
) {
    fun findNode(id: String): WorkflowNode {
        return nodes.find { it.id == id }
            ?: throw IllegalArgumentException("Node with id '$id' not found in workflow definition")
    }
}

data class WorkflowContext(
    val tenantId: String,
    val correlationId: String,
    val initialEvent: EventEnvelope,
    val variables: Map<String, Any?> = emptyMap()
) {
    fun withVariable(key: String, value: Any?): WorkflowContext {
        return copy(variables = variables + (key to value))
    }
}

sealed interface NodeResult {
    data class Success(
        val outputVariables: Map<String, Any?> = emptyMap(),
        val branch: String? = null
    ) : NodeResult

    data class Failure(
        val reason: String,
        val errorCode: String
    ) : NodeResult
}

data class ExecutionSummary(
    val status: String, // 'SUCCESS', 'FAILURE', 'PARTIAL_FAILURE'
    val completedNodes: List<String>,
    val failedNodes: List<String>
)

class WorkflowCycleException(
    message: String,
    val cycleNodes: List<String>
) : RuntimeException(message)
