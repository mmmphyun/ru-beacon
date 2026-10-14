# Ru-Beacon 워크플로우 엔진 명세서 (Workflow Engine Spec)

## 문서 역할
Workflow Worker 내부에서 구동되는 **인메모리 DAG 기반 워크플로우 런타임의 AST 구조, 노드 실행 계약, 순환 참조 검증, 변수 치환 엔진 규격**을 정의한다.

---

## 1. 워크플로우 AST (Abstract Syntax Tree) 스키마

PostgreSQL `workflow_versions.definition` 컬럼에 저장되는 JSON 스냅샷 스키마입니다.

```json
{
  "$schema": "http://json-schema.org/draft-07/schema#",
  "title": "WorkflowDefinition",
  "type": "object",
  "required": ["version", "trigger", "nodes"],
  "properties": {
    "version": { "type": "integer" },
    "variables": {
      "type": "object",
      "additionalProperties": { "type": "string" }
    },
    "trigger": {
      "type": "object",
      "required": ["id", "event_type"],
      "properties": {
        "id": { "type": "string" },
        "event_type": { "type": "string" },
        "next_node_ids": { "type": "array", "items": { "type": "string" } }
      }
    },
    "nodes": {
      "type": "array",
      "items": {
        "$ref": "#/definitions/WorkflowNode"
      }
    }
  },
  "definitions": {
    "WorkflowNode": {
      "type": "object",
      "required": ["id", "node_type", "next_node_ids"],
      "properties": {
        "id": { "type": "string" },
        "node_type": {
          "type": "string",
          "enum": [
            "CONDITION_BRANCH",
            "DISCORD_SEND_MESSAGE",
            "DISCORD_ADD_ROLE",
            "MINECRAFT_DISPATCH_COMMAND",
            "PARALLEL_BLOCK"
          ]
        },
        "inputs": { "type": "object" },
        "branches": {
          "type": "object",
          "properties": {
            "on_true": { "type": "array", "items": { "type": "string" } },
            "on_false": { "type": "array", "items": { "type": "string" } }
          }
        },
        "next_node_ids": { "type": "array", "items": { "type": "string" } }
      }
    }
  }
}
```

---

## 2. 노드 실행 계약 (Kotlin Interface)

모든 노드 구현체는 불변 컨텍스트를 입력받아 순수 함수 형태로 동작한다.

```kotlin
package com.rubeacon.worker.engine

import kotlinx.serialization.json.JsonElement

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
        val branch: String? = null // e.g. "on_true", "on_false"
    ) : NodeResult

    data class Failure(
        val reason: String,
        val errorCode: String
    ) : NodeResult
}

interface NodeExecutor {
    val supportedNodeType: String

    suspend fun execute(
        context: WorkflowContext,
        inputs: Map<String, JsonElement>
    ): NodeResult
}
```

---

## 3. 인메모리 DAG 실행 및 병렬 디스패처

```kotlin
class DagWorkflowDispatcher(
    private val executors: Map<String, NodeExecutor>,
    private val auditLogger: AuditLogger
) {
    suspend fun run(definition: WorkflowDefinition, event: EventEnvelope): ExecutionSummary {
        var context = WorkflowContext(
            tenantId = event.tenantId,
            correlationId = event.correlationId,
            initialEvent = event,
            variables = definition.variables + extractDefaultVariables(event)
        )

        val completedNodes = mutableListOf<String>()
        val failedNodes = mutableListOf<String>()

        suspend fun executeNode(node: WorkflowNode) {
            val executor = executors[node.nodeType] ?: error("Unknown executor: ${node.nodeType}")
            val resolvedInputs = resolveVariables(node.inputs, context)

            when (val result = executor.execute(context, resolvedInputs)) {
                is NodeResult.Success -> {
                    completedNodes.add(node.id)
                    result.outputVariables.forEach { (k, v) -> context = context.withVariable(k, v) }

                    // 다음 노드 디스패치 (Parallel 블록 처리)
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
                    // 순차 노드 실패 시 해당 브랜치 중단 (Parallel의 다른 브랜치는 계속 실행됨)
                }
            }
        }

        // 트리거의 다음 노드부터 순회 시작
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
        auditLogger.recordExecution(context, status, completedNodes, failedNodes)
        return ExecutionSummary(status, completedNodes, failedNodes)
    }
}
```

---

## 4. 순환 참조(Cycle) 사전 검증 (Tarjan / Kahn 알고리즘)

워크플로우를 `ACTIVE`로 배포(Publish)하기 전, API Service는 노드 그래프에 Cycle(무한 루프)이 존재하는지 반드시 검증한다.

- **원칙**: 모든 노드의 진입 차수(In-degree)와 진출 차수(Out-degree)를 기반으로 위상 정렬(Topological Sort)을 수행.
- **오류 처리**: Cycle이 발견되면 HTTP 400 에러 및 `WORKFLOW_CYCLE_DETECTED` 코드로 저장/배포를 거부하고, 사이클을 구성하는 노드 ID 목록을 반환.

---

## 5. 변수 치환(Variable Resolution) 엔진

메시지 본문이나 명령어 문자열 내의 플레이스홀더를 파싱한다.

- **정규식**: `\{([a-zA-Z0-9_]+)\}`
- **기본 제공 변수**:
  - `{User_Nickname}`: Minecraft 닉네임
  - `{Minecraft_UUID}`: 플레이어 UUID
  - `{Discord_User}`: Discord 유저명
  - `{Discord_User_ID}`: Discord 사용자 고유 ID
  - `{Server_Name}`: Minecraft 네트워크명
  - `{Current_Time}`: ISO-8601 시각
- **안전장치**: 선언되지 않은 변수가 템플릿에 존재할 경우, 빈 문자열로 묵인하지 않고 워크플로우 활성화 검증 단계에서 즉시 오류를 반환한다.
