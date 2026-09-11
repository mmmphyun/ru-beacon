package com.rubeacon.api

import com.rubeacon.api.auth.DevMockLoginRequest
import com.rubeacon.api.auth.DiscordGuildInfo
import com.rubeacon.api.auth.DiscordOAuthService
import com.rubeacon.api.db.AuditLogs
import com.rubeacon.api.db.Tenants
import com.rubeacon.api.routes.AuditLogPageResponse
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.deleteAll
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.OffsetDateTime
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class AuditLogAndSuperAdminTest : BaseIntegrationTest() {

    private val tenantA = "tenant_audit_a"
    private val tenantB = "tenant_audit_b"
    private val superAdminId = "super_admin_999"
    private val adminSecret = "test-ultra-secret-key-123"

    @BeforeEach
    fun setup() {
        System.setProperty("DEV_MOCK_AUTH", "false")
        System.setProperty("PLATFORM_SUPER_ADMIN_IDS", superAdminId)
        System.setProperty("PLATFORM_ADMIN_SECRET", adminSecret)

        transaction(database) {
            AuditLogs.deleteAll()
            Tenants.deleteAll()

            Tenants.insert {
                it[id] = tenantA
                it[name] = "테넌트 A"
                it[discordGuildId] = "guild_audit_a"
            }

            Tenants.insert {
                it[id] = tenantB
                it[name] = "테넌트 B"
                it[discordGuildId] = "guild_audit_b"
            }

            // 테넌트 A 로그 3건 삽입
            for (i in 1..3) {
                AuditLogs.insert {
                    it[id] = "log_a_" + i
                    it[tenantId] = this@AuditLogAndSuperAdminTest.tenantA
                    it[correlationId] = "corr_a_" + i
                    it[actorType] = "DISCORD_USER"
                    it[actorId] = "user_01"
                    it[action] = "WORKFLOW_DEPLOY"
                    it[status] = "SUCCESS"
                    it[details] = "{}"
                    it[ipAddress] = "192.168.1." + i
                    it[createdAt] = OffsetDateTime.now().minusMinutes((10 - i).toLong())
                }
            }

            // 테넌트 B 로그 1건 삽입
            AuditLogs.insert {
                it[id] = "log_b_1"
                it[tenantId] = this@AuditLogAndSuperAdminTest.tenantB
                it[correlationId] = "corr_b_1"
                it[actorType] = "SYSTEM"
                it[actorId] = "system"
                it[action] = "NODE_EXECUTE"
                it[status] = "SUCCESS"
                it[details] = "{}"
                it[ipAddress] = "10.0.0.1"
                it[createdAt] = OffsetDateTime.now()
            }
        }
    }

    @Test
    fun `테넌트 A 관리자는 본인 테넌트의 로그만 조회하고 IP는 마스킹되어야 한다`() = testApplication {
        val oauthService = DiscordOAuthService(jedis)
        val userA = "user_a_owner"
        jedis.setex("rubeacon:discord:user:" + userA + ":guilds", 300, Json.encodeToString(listOf(
            DiscordGuildInfo(id = "guild_audit_a", name = "테넌트 A", isOwner = true)
        )))

        application {
            module(database = database, jedis = jedis, oauthService = oauthService)
        }

        val client = createClient { install(HttpCookies) }

        System.setProperty("DEV_MOCK_AUTH", "true")
        client.post("/api/v1/auth/dev-mock-login") {
            contentType(ContentType.Application.Json)
            setBody(Json.encodeToString(DevMockLoginRequest(discordUserId = userA, username = "UserA")))
        }
        System.setProperty("DEV_MOCK_AUTH", "false")

        val res = client.get("/api/v1/audit-logs?tenantId=" + tenantA + "&limit=2")
        assertEquals(HttpStatusCode.OK, res.status)

        val page = Json.decodeFromString<AuditLogPageResponse>(res.bodyAsText())
        assertEquals(2, page.items.size)
        assertNotNull(page.nextCursor)
        // IP 마스킹 검증: 192.168.1.x -> 192.168.***.***
        assertEquals("192.168.***.***", page.items[0].ipAddress)

        // 커서로 다음 페이지 조회
        val res2 = client.get("/api/v1/audit-logs?tenantId=" + tenantA + "&limit=2&cursor=" + page.nextCursor)
        assertEquals(HttpStatusCode.OK, res2.status)
        val page2 = Json.decodeFromString<AuditLogPageResponse>(res2.bodyAsText())
        assertEquals(1, page2.items.size)
    }

    @Test
    fun `일반 사용자가 전체 감사 로그(tenantId 미지정) 조회 시 이중 검증 실패로 403 Forbidden이어야 한다`() = testApplication {
        val oauthService = DiscordOAuthService(jedis)
        val normalUser = "user_normal_01"
        jedis.setex("rubeacon:discord:user:" + normalUser + ":guilds", 300, Json.encodeToString(listOf(
            DiscordGuildInfo(id = "guild_audit_a", name = "테넌트 A", isOwner = true)
        )))

        application {
            module(database = database, jedis = jedis, oauthService = oauthService)
        }

        val client = createClient { install(HttpCookies) }

        System.setProperty("DEV_MOCK_AUTH", "true")
        client.post("/api/v1/auth/dev-mock-login") {
            contentType(ContentType.Application.Json)
            setBody(Json.encodeToString(DevMockLoginRequest(discordUserId = normalUser, username = "NormalUser")))
        }
        System.setProperty("DEV_MOCK_AUTH", "false")

        val res = client.get("/api/v1/audit-logs")
        assertEquals(HttpStatusCode.Forbidden, res.status)
    }

    @Test
    fun `슈퍼 어드민 ID라도 시크릿 헤더가 없으면 403 차단되고, 올바른 시크릿 제공 시 전체 테넌트 로그가 전수 조회되어야 한다`() = testApplication {
        val oauthService = DiscordOAuthService(jedis)

        application {
            module(database = database, jedis = jedis, oauthService = oauthService)
        }

        val client = createClient { install(HttpCookies) }

        System.setProperty("DEV_MOCK_AUTH", "true")
        client.post("/api/v1/auth/dev-mock-login") {
            contentType(ContentType.Application.Json)
            setBody(Json.encodeToString(DevMockLoginRequest(discordUserId = superAdminId, username = "SuperAdmin")))
        }
        System.setProperty("DEV_MOCK_AUTH", "false")

        // 1. 시크릿 헤더 없이 요청 -> 403 Forbidden
        val failRes = client.get("/api/v1/audit-logs")
        assertEquals(HttpStatusCode.Forbidden, failRes.status)

        // 2. 잘못된 시크릿 헤더로 요청 -> 403 Forbidden
        val wrongSecretRes = client.get("/api/v1/audit-logs") {
            header("X-Platform-Admin-Secret", "wrong-secret")
        }
        assertEquals(HttpStatusCode.Forbidden, wrongSecretRes.status)

        // 3. 올바른 2차 시크릿 헤더와 함께 요청 -> 200 OK 및 전체 로그(테넌트 A + B 총 4건) 전수 조회
        val successRes = client.get("/api/v1/audit-logs?limit=10") {
            header("X-Platform-Admin-Secret", adminSecret)
        }
        assertEquals(HttpStatusCode.OK, successRes.status)
        val page = Json.decodeFromString<AuditLogPageResponse>(successRes.bodyAsText())
        assertEquals(4, page.items.size)
    }
}
