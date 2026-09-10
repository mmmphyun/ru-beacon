package com.rubeacon.api.routes

import com.rubeacon.api.db.WorkflowVersions
import com.rubeacon.api.db.Workflows
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.andWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.time.OffsetDateTime
import java.util.UUID

@Serializable
data class CreateWorkflowRequest(
    val tenantId: String,
    val name: String,
    val description: String? = null,
    val definition: String
)

@Serializable
data class CreateVersionRequest(
    val definition: String
)

@Serializable
data class DeployWorkflowRequest(
    val version: Int? = null
)

@Serializable
data class RollbackWorkflowRequest(
    val targetVersion: Int
)

@Serializable
data class WorkflowSummaryDto(
    val id: String,
    val tenantId: String,
    val name: String,
    val description: String?,
    val activeVersion: Int?,
    val createdAt: String,
    val updatedAt: String
)

@Serializable
data class WorkflowVersionDto(
    val id: String,
    val version: Int,
    val status: String,
    val definition: String,
    val publishedAt: String?,
    val createdAt: String
)

@Serializable
data class WorkflowDetailDto(
    val id: String,
    val tenantId: String,
    val name: String,
    val description: String?,
    val activeVersion: Int?,
    val versions: List<WorkflowVersionDto>
)

@Serializable
data class CycleErrorResponse(
    val error: String,
    val message: String,
    val cycleNodes: List<String>
)

@Serializable
data class WorkflowActionResponse(
    val workflowId: String,
    val version: Int? = null,
    val activeVersion: Int? = null,
    val status: String
)

/**
 * 워크플로우 AST 내 노드 순환 참조(Cycle)를 탐지하는 DFS 유틸리티.
 * docs/WORKFLOW_ENGINE_SPEC.md §4 명세 준수.
 */
fun detectCycle(definitionJson: String): List<String>? {
    val json = try {
        Json.parseToJsonElement(definitionJson).jsonObject
    } catch (_: Exception) {
        return null
    }

    val adjacency = mutableMapOf<String, MutableList<String>>()
    val nodes = json["nodes"]?.jsonArray ?: JsonArray(emptyList())

    for (nodeEl in nodes) {
        val nodeObj = nodeEl.jsonObject
        val id = nodeObj["id"]?.jsonPrimitive?.content ?: continue
        val targets = adjacency.getOrPut(id) { mutableListOf() }

        // next_node_ids 수집
        nodeObj["next_node_ids"]?.jsonArray?.forEach {
            targets.add(it.jsonPrimitive.content)
        }

        // branches (on_true, on_false) 수집
        nodeObj["branches"]?.jsonObject?.values?.forEach { branchList ->
            branchList.jsonArray.forEach {
                targets.add(it.jsonPrimitive.content)
            }
        }
    }

    // DFS 기반 사이클 탐지
    val visited = mutableMapOf<String, Int>() // 0: unvisited, 1: visiting, 2: visited
    val path = mutableListOf<String>()

    fun dfs(current: String): List<String>? {
        visited[current] = 1
        path.add(current)

        for (next in adjacency[current] ?: emptyList()) {
            when (visited[next]) {
                1 -> {
                    val cycleStart = path.indexOf(next)
                    return path.subList(cycleStart, path.size) + next
                }
                null, 0 -> {
                    val res = dfs(next)
                    if (res != null) return res
                }
            }
        }

        path.removeAt(path.size - 1)
        visited[current] = 2
        return null
    }

    for (nodeId in adjacency.keys) {
        if (visited[nodeId] == null || visited[nodeId] == 0) {
            val cycle = dfs(nodeId)
            if (cycle != null) return cycle
        }
    }

    return null
}

fun Route.workflowRoutes() {
    route("/api/v1/workflows") {
        // 목록 조회
        get {
            val tenantId = call.request.queryParameters["tenantId"]
            if (tenantId.isNullOrBlank()) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "tenantId is required"))
                return@get
            }

            val list = transaction {
                Workflows.selectAll()
                    .where { Workflows.tenantId eq tenantId }
                    .orderBy(Workflows.updatedAt, SortOrder.DESC)
                    .map {
                        WorkflowSummaryDto(
                            id = it[Workflows.id],
                            tenantId = it[Workflows.tenantId],
                            name = it[Workflows.name],
                            description = it[Workflows.description],
                            activeVersion = it[Workflows.activeVersion],
                            createdAt = it[Workflows.createdAt].toString(),
                            updatedAt = it[Workflows.updatedAt].toString()
                        )
                    }
            }
            call.respond(HttpStatusCode.OK, list)
        }

        // 워크플로우 단건 상세 조회
        get("/{id}") {
            val workflowId = call.parameters["id"] ?: return@get call.respond(HttpStatusCode.BadRequest)

            val detail = transaction {
                val wf = Workflows.selectAll().where { Workflows.id eq workflowId }.singleOrNull()
                    ?: return@transaction null

                val versions = WorkflowVersions.selectAll()
                    .where { WorkflowVersions.workflowId eq workflowId }
                    .orderBy(WorkflowVersions.version, SortOrder.DESC)
                    .map {
                        WorkflowVersionDto(
                            id = it[WorkflowVersions.id],
                            version = it[WorkflowVersions.version],
                            status = it[WorkflowVersions.status],
                            definition = it[WorkflowVersions.definition],
                            publishedAt = it[WorkflowVersions.publishedAt]?.toString(),
                            createdAt = it[WorkflowVersions.createdAt].toString()
                        )
                    }

                WorkflowDetailDto(
                    id = wf[Workflows.id],
                    tenantId = wf[Workflows.tenantId],
                    name = wf[Workflows.name],
                    description = wf[Workflows.description],
                    activeVersion = wf[Workflows.activeVersion],
                    versions = versions
                )
            }

            if (detail == null) {
                call.respond(HttpStatusCode.NotFound, mapOf("error" to "Workflow not found"))
            } else {
                call.respond(HttpStatusCode.OK, detail)
            }
        }

        // 워크플로우 생성 (버전 1 DRAFT)
        post {
            val req = call.receive<CreateWorkflowRequest>()
            val workflowId = "wf_" + UUID.randomUUID().toString().replace("-", "").take(16)
            val versionId = "wfv_" + UUID.randomUUID().toString().replace("-", "").take(16)
            val now = OffsetDateTime.now()

            transaction {
                Workflows.insert {
                    it[id] = workflowId
                    it[tenantId] = req.tenantId
                    it[name] = req.name
                    it[description] = req.description
                    it[activeVersion] = null
                    it[createdAt] = now
                    it[updatedAt] = now
                }

                WorkflowVersions.insert {
                    it[id] = versionId
                    it[this.workflowId] = workflowId
                    it[tenantId] = req.tenantId
                    it[version] = 1
                    it[status] = "DRAFT"
                    it[definition] = req.definition
                    it[publishedAt] = null
                    it[createdAt] = now
                }
            }

            call.respond(HttpStatusCode.Created, WorkflowActionResponse(
                workflowId = workflowId,
                version = 1,
                status = "DRAFT"
            ))
        }

        // 새 버전 DRAFT 등록
        post("/{id}/versions") {
            val workflowId = call.parameters["id"] ?: return@post call.respond(HttpStatusCode.BadRequest)
            val req = call.receive<CreateVersionRequest>()
            val now = OffsetDateTime.now()

            val newVersion = transaction {
                val wf = Workflows.selectAll().where { Workflows.id eq workflowId }.singleOrNull()
                    ?: return@transaction null

                val maxVersion = WorkflowVersions.selectAll()
                    .where { WorkflowVersions.workflowId eq workflowId }
                    .maxOfOrNull { it[WorkflowVersions.version] } ?: 0

                val nextVer = maxVersion + 1
                val versionId = "wfv_" + UUID.randomUUID().toString().replace("-", "").take(16)

                WorkflowVersions.insert {
                    it[id] = versionId
                    it[this.workflowId] = workflowId
                    it[tenantId] = wf[Workflows.tenantId]
                    it[version] = nextVer
                    it[status] = "DRAFT"
                    it[definition] = req.definition
                    it[publishedAt] = null
                    it[createdAt] = now
                }

                Workflows.update({ Workflows.id eq workflowId }) {
                    it[updatedAt] = now
                }

                nextVer
            }

            if (newVersion == null) {
                call.respond(HttpStatusCode.NotFound, mapOf("error" to "Workflow not found"))
            } else {
                call.respond(HttpStatusCode.Created, WorkflowActionResponse(
                    workflowId = workflowId,
                    version = newVersion,
                    status = "DRAFT"
                ))
            }
        }

        // 워크플로우 배포 (PUBLISH / DEPLOY)
        post("/{id}/deploy") {
            val workflowId = call.parameters["id"] ?: return@post call.respond(HttpStatusCode.BadRequest)
            val req = try { call.receive<DeployWorkflowRequest>() } catch (_: Exception) { DeployWorkflowRequest() }
            val now = OffsetDateTime.now()

            // 1. 배포 대상 버전 확인 및 사이클 검증
            val targetVerRow = transaction {
                val query = WorkflowVersions.selectAll().where { WorkflowVersions.workflowId eq workflowId }
                if (req.version != null) {
                    query.andWhere { WorkflowVersions.version eq req.version }.singleOrNull()
                } else {
                    query.orderBy(WorkflowVersions.version, SortOrder.DESC).firstOrNull()
                }
            } ?: return@post call.respond(HttpStatusCode.NotFound, mapOf("error" to "Target version not found"))

            val definitionJson = targetVerRow[WorkflowVersions.definition]
            val cycle = detectCycle(definitionJson)
            if (cycle != null) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    CycleErrorResponse(
                        error = "WORKFLOW_CYCLE_DETECTED",
                        message = "Workflow contains cyclic loop in DAG",
                        cycleNodes = cycle
                    )
                )
                return@post
            }

            val targetVer = targetVerRow[WorkflowVersions.version]

            // 2. 상태 전이 및 activeVersion 갱신
            transaction {
                WorkflowVersions.update({
                    (WorkflowVersions.workflowId eq workflowId) and (WorkflowVersions.status eq "ACTIVE")
                }) {
                    it[status] = "INACTIVE"
                }

                WorkflowVersions.update({
                    (WorkflowVersions.workflowId eq workflowId) and (WorkflowVersions.version eq targetVer)
                }) {
                    it[status] = "ACTIVE"
                    it[publishedAt] = now
                }

                Workflows.update({ Workflows.id eq workflowId }) {
                    it[activeVersion] = targetVer
                    it[updatedAt] = now
                }
            }

            call.respond(HttpStatusCode.OK, WorkflowActionResponse(
                workflowId = workflowId,
                activeVersion = targetVer,
                status = "ACTIVE"
            ))
        }

        // 워크플로우 롤백 (ROLLBACK)
        post("/{id}/rollback") {
            val workflowId = call.parameters["id"] ?: return@post call.respond(HttpStatusCode.BadRequest)
            val req = call.receive<RollbackWorkflowRequest>()
            val now = OffsetDateTime.now()

            val targetVerRow = transaction {
                WorkflowVersions.selectAll()
                    .where { (WorkflowVersions.workflowId eq workflowId) and (WorkflowVersions.version eq req.targetVersion) }
                    .singleOrNull()
            } ?: return@post call.respond(HttpStatusCode.NotFound, mapOf("error" to "Rollback target version not found"))

            transaction {
                WorkflowVersions.update({
                    (WorkflowVersions.workflowId eq workflowId) and (WorkflowVersions.status eq "ACTIVE")
                }) {
                    it[status] = "INACTIVE"
                }

                WorkflowVersions.update({
                    (WorkflowVersions.workflowId eq workflowId) and (WorkflowVersions.version eq req.targetVersion)
                }) {
                    it[status] = "ACTIVE"
                    it[publishedAt] = now
                }

                Workflows.update({ Workflows.id eq workflowId }) {
                    it[activeVersion] = req.targetVersion
                    it[updatedAt] = now
                }
            }

            call.respond(HttpStatusCode.OK, WorkflowActionResponse(
                workflowId = workflowId,
                activeVersion = req.targetVersion,
                status = "ACTIVE"
            ))
        }
    }
}
