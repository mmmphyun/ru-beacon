package com.rubeacon.api

import com.rubeacon.api.db.Admin2faPolicies
import com.rubeacon.api.db.MinecraftInstances
import com.rubeacon.api.db.MinecraftNetworks
import com.rubeacon.api.db.Tenants
import com.rubeacon.api.db.WorkflowVersions
import com.rubeacon.api.db.Workflows
import com.rubeacon.api.routes.CreateVersionRequest
import com.rubeacon.api.routes.CreateWorkflowRequest
import com.rubeacon.api.routes.DeployWorkflowRequest
import com.rubeacon.api.routes.IssueInstanceTokenRequest
import com.rubeacon.api.routes.OnboardingRequest
import com.rubeacon.api.routes.RollbackWorkflowRequest
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.sql.deleteAll
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class WorkflowRoutesTest : BaseIntegrationTest() {

    private val tenantId = "tenant_wf_test_01"

    @BeforeEach
    fun clearData() {
        transaction(database) {
            WorkflowVersions.deleteAll()
            Workflows.deleteAll()
            MinecraftInstances.deleteAll()
            Admin2faPolicies.deleteAll()
            MinecraftNetworks.deleteAll()
            Tenants.deleteAll()

            Tenants.insert {
                it[id] = tenantId
                it[name] = "워크플로우 테스트 테넌트"
                it[discordGuildId] = "999888777666555444"
                it[maxAccountLinksPerUser] = 2
            }
        }
    }

    @Test
    fun `워크플로우 생성, 새 버전 등록, 배포 및 롤백이 정상 동작해야 한다`() = testApplication {
        application {
            module(database = database, jedis = jedis)
        }
        val client = createClient {}

        val validDagJson = """
            {
              "version": 1,
              "trigger": { "id": "t1", "event_type": "MINECRAFT_LEVEL_UP", "next_node_ids": ["c1"] },
              "nodes": [
                {
                  "id": "c1",
                  "node_type": "CONDITION_BRANCH",
                  "branches": { "on_true": ["a1"], "on_false": [] },
                  "next_node_ids": []
                },
                {
                  "id": "a1",
                  "node_type": "DISCORD_SEND_MESSAGE",
                  "inputs": { "channel_id": "111", "message": "축하합니다!" },
                  "next_node_ids": []
                }
              ]
            }
        """.trimIndent()

        // 1. 워크플로우 생성 (POST /api/v1/workflows)
        val createRes = client.post("/api/v1/workflows") {
            contentType(ContentType.Application.Json)
            setBody(Json.encodeToString(CreateWorkflowRequest(
                tenantId = tenantId,
                name = "레벨업 축하 알림",
                description = "플레이어 레벨업 시 디스코드 채널에 알림 전송",
                definition = validDagJson
            )))
        }
        assertEquals(HttpStatusCode.Created, createRes.status)
        val createObj = Json.parseToJsonElement(createRes.bodyAsText()).jsonObject
        val wfId = createObj["workflowId"]!!.jsonPrimitive.content
        assertEquals(1, createObj["version"]!!.jsonPrimitive.content.toInt())

        // 2. 새 버전 등록 (POST /api/v1/workflows/{id}/versions)
        val v2DagJson = validDagJson.replace("축하합니다!", "레벨업 달성을 축하합니다!")
        val v2Res = client.post("/api/v1/workflows/$wfId/versions") {
            contentType(ContentType.Application.Json)
            setBody(Json.encodeToString(CreateVersionRequest(definition = v2DagJson)))
        }
        assertEquals(HttpStatusCode.Created, v2Res.status)
        val v2Obj = Json.parseToJsonElement(v2Res.bodyAsText()).jsonObject
        assertEquals(2, v2Obj["version"]!!.jsonPrimitive.content.toInt())

        // 3. 워크플로우 v2 배포 (POST /api/v1/workflows/{id}/deploy)
        val deployRes = client.post("/api/v1/workflows/$wfId/deploy") {
            contentType(ContentType.Application.Json)
            setBody(Json.encodeToString(DeployWorkflowRequest(version = 2)))
        }
        assertEquals(HttpStatusCode.OK, deployRes.status)
        val deployObj = Json.parseToJsonElement(deployRes.bodyAsText()).jsonObject
        assertEquals(2, deployObj["activeVersion"]!!.jsonPrimitive.content.toInt())

        // 4. 워크플로우 단건 상세 조회 (GET /api/v1/workflows/{id})
        val getRes = client.get("/api/v1/workflows/$wfId")
        assertEquals(HttpStatusCode.OK, getRes.status)
        assertTrue(getRes.bodyAsText().contains("레벨업 달성을 축하합니다!"))

        // 5. 이전 버전으로 롤백 (POST /api/v1/workflows/{id}/rollback)
        val rollbackRes = client.post("/api/v1/workflows/$wfId/rollback") {
            contentType(ContentType.Application.Json)
            setBody(Json.encodeToString(RollbackWorkflowRequest(targetVersion = 1)))
        }
        assertEquals(HttpStatusCode.OK, rollbackRes.status)
        val rollbackObj = Json.parseToJsonElement(rollbackRes.bodyAsText()).jsonObject
        assertEquals(1, rollbackObj["activeVersion"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun `순환 참조가 포함된 워크플로우 배포 시 400 및 WORKFLOW_CYCLE_DETECTED를 반환해야 한다`() = testApplication {
        application {
            module(database = database, jedis = jedis)
        }
        val client = createClient {}

        // 순환 참조: n1 -> n2 -> n1
        val cycleDagJson = """
            {
              "version": 1,
              "trigger": { "id": "t1", "event_type": "MINECRAFT_LEVEL_UP", "next_node_ids": ["n1"] },
              "nodes": [
                {
                  "id": "n1",
                  "node_type": "DISCORD_SEND_MESSAGE",
                  "next_node_ids": ["n2"]
                },
                {
                  "id": "n2",
                  "node_type": "DISCORD_SEND_MESSAGE",
                  "next_node_ids": ["n1"]
                }
              ]
            }
        """.trimIndent()

        val createRes = client.post("/api/v1/workflows") {
            contentType(ContentType.Application.Json)
            setBody(Json.encodeToString(CreateWorkflowRequest(
                tenantId = tenantId,
                name = "사이클 워크플로우",
                definition = cycleDagJson
            )))
        }
        assertEquals(HttpStatusCode.Created, createRes.status)
        val wfId = Json.parseToJsonElement(createRes.bodyAsText()).jsonObject["workflowId"]!!.jsonPrimitive.content

        // 배포 시도 시 400 Bad Request 확인
        val deployRes = client.post("/api/v1/workflows/$wfId/deploy") {
            contentType(ContentType.Application.Json)
            setBody(Json.encodeToString(DeployWorkflowRequest(version = 1)))
        }
        assertEquals(HttpStatusCode.BadRequest, deployRes.status)
        val errorText = deployRes.bodyAsText()
        assertTrue(errorText.contains("WORKFLOW_CYCLE_DETECTED"))
    }

    @Test
    fun `테넌트 온보딩 및 마인크래프트 인스턴스 토큰 발급이 정상 완료되어야 한다`() = testApplication {
        application {
            module(database = database, jedis = jedis)
        }
        val client = createClient {}

        // 1. 온보딩 설정 (POST /api/v1/tenants/onboarding)
        val onboardRes = client.post("/api/v1/tenants/onboarding") {
            contentType(ContentType.Application.Json)
            setBody(Json.encodeToString(OnboardingRequest(
                tenantId = tenantId,
                name = "테스트 마인크래프트 커뮤니티",
                discordGuildId = "999888777666555444",
                authChannelId = "1234567890",
                policyMode = "ENFORCE"
            )))
        }
        assertEquals(HttpStatusCode.OK, onboardRes.status)

        // 2. 마인크래프트 인스턴스 토큰 발급 (POST /api/v1/instances/token)
        val tokenRes = client.post("/api/v1/instances/token") {
            contentType(ContentType.Application.Json)
            setBody(Json.encodeToString(IssueInstanceTokenRequest(
                tenantId = tenantId,
                instanceId = "inst_survival_01",
                name = "야생 1서버",
                instanceType = "BACKEND"
            )))
        }
        assertEquals(HttpStatusCode.Created, tokenRes.status)
        val tokenObj = Json.parseToJsonElement(tokenRes.bodyAsText()).jsonObject
        val token = tokenObj["token"]!!.jsonPrimitive.content
        assertNotNull(token)
        assertTrue(token.length >= 32)

        // 3. 테넌트 상세 조회 (GET /api/v1/tenants/{id})
        val detailRes = client.get("/api/v1/tenants/$tenantId")
        assertEquals(HttpStatusCode.OK, detailRes.status)
        val detailText = detailRes.bodyAsText()
        assertTrue(detailText.contains("야생 1서버"))
        assertTrue(detailText.contains("ENFORCE"))
    }

    @Test
    fun `순환 참조가 포함된 버전으로 롤백 시도시 400 및 WORKFLOW_CYCLE_DETECTED로 차단되어야 한다`() = testApplication {
        application {
            module(database = database, jedis = jedis)
        }
        val client = createClient {}

        val validDagJson = """
            {
              "version": 1,
              "trigger": { "id": "t1", "event_type": "MINECRAFT_LEVEL_UP", "next_node_ids": ["a1"] },
              "nodes": [
                {
                  "id": "a1",
                  "node_type": "DISCORD_SEND_MESSAGE",
                  "inputs": { "channel_id": "111", "message": "정상 v1" },
                  "next_node_ids": []
                }
              ]
            }
        """.trimIndent()

        // 1. 정상 v1 워크플로우 생성 및 배포
        val createRes = client.post("/api/v1/workflows") {
            contentType(ContentType.Application.Json)
            setBody(Json.encodeToString(CreateWorkflowRequest(
                tenantId = tenantId,
                name = "롤백 사이클 방어 테스트",
                definition = validDagJson
            )))
        }
        val wfId = Json.parseToJsonElement(createRes.bodyAsText()).jsonObject["workflowId"]!!.jsonPrimitive.content
        client.post("/api/v1/workflows/$wfId/deploy")

        // 2. v2로 사이클이 있는 정의 등록 (n1 <-> n2)
        val cycleDagJson = """
            {
              "version": 2,
              "trigger": { "id": "t1", "event_type": "MINECRAFT_LEVEL_UP", "next_node_ids": ["n1"] },
              "nodes": [
                { "id": "n1", "node_type": "DISCORD_SEND_MESSAGE", "next_node_ids": ["n2"] },
                { "id": "n2", "node_type": "DISCORD_SEND_MESSAGE", "next_node_ids": ["n1"] }
              ]
            }
        """.trimIndent()
        client.post("/api/v1/workflows/$wfId/versions") {
            contentType(ContentType.Application.Json)
            setBody(Json.encodeToString(CreateVersionRequest(definition = cycleDagJson)))
        }

        // 3. v2(사이클 포함)로 롤백 시도 시 400으로 차단되어야 함
        val rollbackRes = client.post("/api/v1/workflows/$wfId/rollback") {
            contentType(ContentType.Application.Json)
            setBody(Json.encodeToString(RollbackWorkflowRequest(targetVersion = 2)))
        }
        assertEquals(HttpStatusCode.BadRequest, rollbackRes.status)
        assertTrue(rollbackRes.bodyAsText().contains("WORKFLOW_CYCLE_DETECTED"))

        // 활성 버전은 여전히 v1이어야 함
        val detailRes = client.get("/api/v1/workflows/$wfId")
        val detailObj = Json.parseToJsonElement(detailRes.bodyAsText()).jsonObject
        assertEquals(1, detailObj["activeVersion"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun `깨진 JSON이나 빈 정의로 워크플로우 생성 시도 시 400 MALFORMED_JSON을 반환해야 한다`() = testApplication {
        application {
            module(database = database, jedis = jedis)
        }
        val client = createClient {}

        // 빈 정의 문자열
        val emptyRes = client.post("/api/v1/workflows") {
            contentType(ContentType.Application.Json)
            setBody(Json.encodeToString(CreateWorkflowRequest(
                tenantId = tenantId,
                name = "빈 워크플로우",
                definition = "   "
            )))
        }
        assertEquals(HttpStatusCode.BadRequest, emptyRes.status)
        assertTrue(emptyRes.bodyAsText().contains("MALFORMED_JSON"))

        // 잘못된 JSON 형식
        val malformedRes = client.post("/api/v1/workflows") {
            contentType(ContentType.Application.Json)
            setBody(Json.encodeToString(CreateWorkflowRequest(
                tenantId = tenantId,
                name = "깨진 JSON 워크플로우",
                definition = "{ this is not a valid json"
            )))
        }
        assertEquals(HttpStatusCode.BadRequest, malformedRes.status)
        assertTrue(malformedRes.bodyAsText().contains("MALFORMED_JSON"))
    }

    @Test
    fun `존재하지 않는 테넌트로 워크플로우 생성 시 404를 반환해야 한다`() = testApplication {
        application {
            module(database = database, jedis = jedis)
        }
        val client = createClient {}

        val res = client.post("/api/v1/workflows") {
            contentType(ContentType.Application.Json)
            setBody(Json.encodeToString(CreateWorkflowRequest(
                tenantId = "non_existent_tenant_999",
                name = "유령 테넌트 워크플로우",
                definition = "{}"
            )))
        }
        assertEquals(HttpStatusCode.NotFound, res.status)
        assertTrue(res.bodyAsText().contains("Tenant 'non_existent_tenant_999' not found"))
    }

    @Test
    fun `존재하지 않는 워크플로우 및 버전에 대한 조회, 배포, 롤백 시 404를 반환해야 한다`() = testApplication {
        application {
            module(database = database, jedis = jedis)
        }
        val client = createClient {}

        // 1. 존재하지 않는 워크플로우 ID 조회
        val notFoundGet = client.get("/api/v1/workflows/wf_invalid_id")
        assertEquals(HttpStatusCode.NotFound, notFoundGet.status)

        // 2. 존재하지 않는 워크플로우 ID 배포
        val notFoundDeploy = client.post("/api/v1/workflows/wf_invalid_id/deploy")
        assertEquals(HttpStatusCode.NotFound, notFoundDeploy.status)

        // 3. 정상 워크플로우 생성 후 존재하지 않는 버전 배포
        val validDagJson = """{"version":1,"trigger":{"id":"t1","event_type":"T"},"nodes":[]}"""
        val createRes = client.post("/api/v1/workflows") {
            contentType(ContentType.Application.Json)
            setBody(Json.encodeToString(CreateWorkflowRequest(
                tenantId = tenantId,
                name = "버전 범위 테스트",
                definition = validDagJson
            )))
        }
        val wfId = Json.parseToJsonElement(createRes.bodyAsText()).jsonObject["workflowId"]!!.jsonPrimitive.content

        val invalidVerDeploy = client.post("/api/v1/workflows/$wfId/deploy") {
            contentType(ContentType.Application.Json)
            setBody(Json.encodeToString(DeployWorkflowRequest(version = 999)))
        }
        assertEquals(HttpStatusCode.NotFound, invalidVerDeploy.status)

        // 4. 존재하지 않는 버전 롤백
        val invalidVerRollback = client.post("/api/v1/workflows/$wfId/rollback") {
            contentType(ContentType.Application.Json)
            setBody(Json.encodeToString(RollbackWorkflowRequest(targetVersion = 999)))
        }
        assertEquals(HttpStatusCode.NotFound, invalidVerRollback.status)
    }
}
