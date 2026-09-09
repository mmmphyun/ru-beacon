package com.rubeacon.worker.engine

import com.rubeacon.common.event.EventEnvelope
import com.rubeacon.worker.db.AuditLogger
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.Collections

/**
 * 인메모리 DAG 기반 코루틴 병렬 워크플로우 디스패처.
 * docs/WORKFLOW_ENGINE_SPEC.md §3 규격을 준수함.
 */
class DagWorkflowDispatcher(
    private val executors: Map<String, NodeExecutor>,
    private val auditLogger: AuditLogger
) {

    suspend fun run(definition: WorkflowDefinition, event: EventEnvelope): ExecutionSummary {
        // 1. 배포/실행 전 Tarjan 알고리즘으로 무한 루프(Cycle) 유무 사전 검증
        TarjanCycleDetector.validate(definition)

        val contextMutex = Mutex()
        var currentContext = WorkflowContext(
            tenantId = event.tenantId,
            correlationId = event.correlationId,
            initialEvent = event,
            variables = definition.variables
        )

        val completedNodes = Collections.synchronizedList(mutableListOf<String>())
        val failedNodes = Collections.synchronizedList(mutableListOf<String>())

        suspend fun executeNode(node: WorkflowNode) {
            val executor = executors[node.nodeType]
                ?: throw IllegalArgumentException("Unsupported node executor: '${node.nodeType}'")

            val snapshotContext = contextMutex.withLock { currentContext }
            val result = runCatching { executor.execute(snapshotContext, node.inputs) }
                .getOrElse { NodeResult.Failure(it.message ?: "Unknown error", "EXECUTION_EXCEPTION") }

            when (result) {
                is NodeResult.Success -> {
                    completedNodes.add(node.id)
                    if (result.outputVariables.isNotEmpty()) {
                        contextMutex.withLock {
                            currentContext = result.outputVariables.entries.fold(currentContext) { ctx, (k, v) ->
                                ctx.withVariable(k, v)
                            }
                        }
                    }

                    // 다음 분기 노드 결정
                    val nextIds = if (node.nodeType == "CONDITION_BRANCH") {
                        node.branches?.get(result.branch) ?: emptyList()
                    } else {
                        node.nextNodeIds
                    }

                    if (nextIds.size > 1) {
                        coroutineScope {
                            nextIds.map { nextId ->
                                async { executeNode(definition.findNode(nextId)) }
                            }.awaitAll()
                        }
                    } else if (nextIds.size == 1) {
                        executeNode(definition.findNode(nextIds.first()))
                    }
                }
                is NodeResult.Failure -> {
                    failedNodes.add(node.id)
                    // 현재 브랜치 중단 (병렬로 실행 중인 다른 브랜치는 격리되어 계속 수행)
                }
            }
        }

        // 트리거의 시작 노드 디스패치 (병렬 분기 지원)
        coroutineScope {
            definition.trigger.nextNodeIds.map { startId ->
                async { executeNode(definition.findNode(startId)) }
            }.awaitAll()
        }

        val status = when {
            failedNodes.isEmpty() -> "SUCCESS"
            completedNodes.isNotEmpty() -> "PARTIAL_FAILURE"
            else -> "FAILURE"
        }

        // 종단 단 1회 비동기 감사 로그 기록
        val detailsJson = buildJsonObject {
            put("status", status)
            put("completed_nodes", Json.encodeToString(completedNodes.toList()))
            put("failed_nodes", Json.encodeToString(failedNodes.toList()))
        }.toString()

        auditLogger.record(
            tenantId = event.tenantId,
            correlationId = event.correlationId,
            actorType = event.actor?.type ?: "SYSTEM",
            actorId = event.actor?.id ?: "system",
            action = "WORKFLOW_EXECUTE",
            status = status,
            detailsJson = detailsJson
        )

        return ExecutionSummary(
            status = status,
            completedNodes = completedNodes.toList(),
            failedNodes = failedNodes.toList()
        )
    }
}
