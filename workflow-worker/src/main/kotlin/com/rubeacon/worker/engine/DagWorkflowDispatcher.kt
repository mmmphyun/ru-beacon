package com.rubeacon.worker.engine

import com.rubeacon.common.event.EventEnvelope
import com.rubeacon.worker.db.AuditLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

/**
 * 인메모리 DAG 기반 코루틴 병렬 워크플로우 디스패처.
 * docs/WORKFLOW_ENGINE_SPEC.md §3 규격을 준수함.
 */
class DagWorkflowDispatcher(
    private val executors: Map<String, NodeExecutor>,
    private val auditLogger: AuditLogger
) {
    private val log = LoggerFactory.getLogger(DagWorkflowDispatcher::class.java)

    suspend fun run(definition: WorkflowDefinition, event: EventEnvelope): ExecutionSummary {
        log.info("[{}] Starting workflow: triggerId={}, triggerType={}, tenant={}", event.correlationId, definition.trigger.id, definition.trigger.eventType, event.tenantId)

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
        val prunedNodes = Collections.synchronizedSet(mutableSetOf<String>())

        // 2. DAG 각 노드의 In-degree (진입 차수) 계산하여 다이아몬드 합류 노드 1회 실행 보장
        val inDegreeMap = definition.nodes.associate { it.id to AtomicInteger(0) }
        definition.trigger.nextNodeIds.forEach { inDegreeMap[it]?.incrementAndGet() }
        for (node in definition.nodes) {
            node.nextNodeIds.forEach { inDegreeMap[it]?.incrementAndGet() }
            node.branches?.values?.flatten()?.forEach { inDegreeMap[it]?.incrementAndGet() }
        }

        fun prune(nodeId: String) {
            if (inDegreeMap[nodeId]?.decrementAndGet() == 0) {
                prunedNodes.add(nodeId)
                val node = definition.nodes.find { it.id == nodeId } ?: return
                node.nextNodeIds.forEach { prune(it) }
                node.branches?.values?.flatten()?.forEach { prune(it) }
            }
        }

        lateinit var dispatchNode: suspend (String) -> Unit

        suspend fun executeNode(node: WorkflowNode) {
            log.info("[{}] Executing node: id={}, type={}", currentContext.correlationId, node.id, node.nodeType)
            val executor = executors[node.nodeType]
                ?: throw IllegalArgumentException("Unsupported node executor: '${node.nodeType}'")

            val snapshotContext = contextMutex.withLock { currentContext }
            val result = try {
                executor.execute(snapshotContext, node.inputs)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                NodeResult.Failure(e.message ?: "Unknown error", "EXECUTION_EXCEPTION")
            }

            when (result) {
                is NodeResult.Success -> {
                    log.info("[{}] Node completed: id={}", currentContext.correlationId, node.id)
                    completedNodes.add(node.id)
                    if (result.outputVariables.isNotEmpty()) {
                        contextMutex.withLock {
                            currentContext = currentContext.copy(
                                variables = currentContext.variables + result.outputVariables
                            )
                        }
                    }

                    // 다음 분기 노드 결정 및 In-degree Barrier 기반 디스패치
                    val nextIds = if (node.nodeType == "CONDITION_BRANCH") {
                        node.branches?.forEach { (branch, targets) ->
                            if (branch != result.branch) targets.forEach { prune(it) }
                        }
                        node.branches?.get(result.branch) ?: emptyList()
                    } else {
                        node.nextNodeIds
                    }

                    if (nextIds.size > 1) {
                        coroutineScope {
                            nextIds.map { nextId -> async { dispatchNode(nextId) } }.awaitAll()
                        }
                    } else if (nextIds.size == 1) {
                        dispatchNode(nextIds.first())
                    }
                }
                is NodeResult.Failure -> {
                    log.warn("[{}] Node failed: id={}, reason={}, code={}", currentContext.correlationId, node.id, result.reason, result.errorCode)
                    failedNodes.add(node.id)

                    // continueOnError 설정 시 실패를 기록하되 후속 파이프라인으로 In-degree 감쇄 및 진행 허용
                    if (node.continueOnError && node.nextNodeIds.isNotEmpty()) {
                        if (node.nextNodeIds.size > 1) {
                            coroutineScope {
                                node.nextNodeIds.map { nextId -> async { dispatchNode(nextId) } }.awaitAll()
                            }
                        } else {
                            dispatchNode(node.nextNodeIds.first())
                        }
                    }
                }
            }
        }

        dispatchNode = { nodeId ->
            val rem = inDegreeMap[nodeId]?.decrementAndGet()
            if (rem == 0 && !prunedNodes.contains(nodeId)) {
                executeNode(definition.findNode(nodeId))
            }
        }

        // 트리거의 시작 노드 디스패치 (병렬 분기 지원)
        coroutineScope {
            definition.trigger.nextNodeIds.map { startId ->
                async { dispatchNode(startId) }
            }.awaitAll()
        }

        val status = when {
            failedNodes.isEmpty() -> "SUCCESS"
            completedNodes.isNotEmpty() -> "PARTIAL_FAILURE"
            else -> "FAILURE"
        }

        log.info("[{}] Workflow finished: status={}, completed={}, failed={}", event.correlationId, status, completedNodes.size, failedNodes.size)

        // 종단 단 1회 비동기 감사 로그 기록
        val detailsJson = buildJsonObject {
            put("status", status)
            put("completed_nodes", buildJsonArray { completedNodes.forEach { add(JsonPrimitive(it)) } })
            put("failed_nodes", buildJsonArray { failedNodes.forEach { add(JsonPrimitive(it)) } })
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
