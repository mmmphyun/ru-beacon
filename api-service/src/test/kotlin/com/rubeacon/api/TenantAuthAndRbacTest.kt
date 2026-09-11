package com.rubeacon.api

import com.rubeacon.api.auth.DevMockLoginRequest
import com.rubeacon.api.auth.DiscordGuildInfo
import com.rubeacon.api.auth.DiscordOAuthService
import com.rubeacon.api.db.Admin2faPolicies
import com.rubeacon.api.db.MinecraftInstances
import com.rubeacon.api.db.MinecraftNetworks
import com.rubeacon.api.db.Tenants
import com.rubeacon.api.routes.IssueInstanceTokenRequest
import io.ktor.client.plugins.cookies.HttpCookies
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
import kotlin.test.assertTrue

class TenantAuthAndRbacTest : BaseIntegrationTest() {

    private val tenantA = "tenant_alpha"
    private val tenantB = "tenant_beta"
    private val guildA = "guild_111"
    private val guildB = "guild_222"
    private val adminRoleA = "role_admin_alpha"

    @BeforeEach
    fun setup() {
        System.setProperty("DEV_MOCK_AUTH", "false")
        transaction(database) {
            MinecraftInstances.deleteAll()
            Admin2faPolicies.deleteAll()
            MinecraftNetworks.deleteAll()
            Tenants.deleteAll()

            Tenants.insert {
                it[id] = tenantA
                it[name] = "알파 서버"
                it[discordGuildId] = guildA
                it[adminRoleId] = adminRoleA
            }

            Tenants.insert {
                it[id] = tenantB
                it[name] = "베타 서버"
                it[discordGuildId] = guildB
                it[adminRoleId] = null
            }

            MinecraftNetworks.insert {
                it[id] = "net_" + tenantA
                it[tenantId] = tenantA
                it[name] = "알파 기본 네트워크"
            }
        }
    }

    @Test
    fun `미인증 상태에서 테넌트 상세 조회 시 401 Unauthorized 반환되어야 한다`() = testApplication {
        application {
            module(database = database, jedis = jedis)
        }
        val client = createClient {}

        val res = client.get("/api/v1/tenants/" + tenantA)
        assertEquals(HttpStatusCode.Unauthorized, res.status)
    }

    @Test
    fun `일반 관리자는 상세 조회는 가능하나 인스턴스 토큰 발급 시 403 Forbidden 차단되어야 한다`() = testApplication {
        val oauthService = DiscordOAuthService(jedis)
        val testUserId = "user_admin_01"
        jedis.setex("rubeacon:discord:user:" + testUserId + ":guilds", 300, Json.encodeToString(listOf(
            DiscordGuildInfo(id = guildA, name = "알파 서버", isOwner = false, roles = listOf(adminRoleA))
        )))

        application {
            module(database = database, jedis = jedis, oauthService = oauthService)
        }

        val client = createClient {
            install(HttpCookies)
        }

        // 1. 개발 목 로그인으로 세션 획득
        System.setProperty("DEV_MOCK_AUTH", "true")
        val loginRes = client.post("/api/v1/auth/dev-mock-login") {
            contentType(ContentType.Application.Json)
            setBody(Json.encodeToString(DevMockLoginRequest(discordUserId = testUserId, username = "AdminUser")))
        }
        assertEquals(HttpStatusCode.OK, loginRes.status)
        System.setProperty("DEV_MOCK_AUTH", "false")

        // 2. 테넌트 A 조회 (관리자 역할 있으므로 성공)
        val getRes = client.get("/api/v1/tenants/" + tenantA)
        assertEquals(HttpStatusCode.OK, getRes.status)

        // 3. 인스턴스 토큰 발급 시도 (소유자가 아니므로 403 Forbidden)
        val tokenRes = client.post("/api/v1/instances/token") {
            contentType(ContentType.Application.Json)
            setBody(Json.encodeToString(IssueInstanceTokenRequest(
                tenantId = tenantA,
                instanceId = "inst_backend_01",
                name = "생존 서버 1"
            )))
        }
        assertEquals(HttpStatusCode.Forbidden, tokenRes.status)
    }

    @Test
    fun `서버장은 인스턴스 토큰을 정상 발급받을 수 있어야 한다`() = testApplication {
        val oauthService = DiscordOAuthService(jedis)
        val ownerUserId = "user_owner_01"
        jedis.setex("rubeacon:discord:user:" + ownerUserId + ":guilds", 300, Json.encodeToString(listOf(
            DiscordGuildInfo(id = guildA, name = "알파 서버", isOwner = true, roles = emptyList())
        )))

        application {
            module(database = database, jedis = jedis, oauthService = oauthService)
        }

        val client = createClient {
            install(HttpCookies)
        }

        System.setProperty("DEV_MOCK_AUTH", "true")
        val loginRes = client.post("/api/v1/auth/dev-mock-login") {
            contentType(ContentType.Application.Json)
            setBody(Json.encodeToString(DevMockLoginRequest(discordUserId = ownerUserId, username = "OwnerUser")))
        }
        assertEquals(HttpStatusCode.OK, loginRes.status)
        System.setProperty("DEV_MOCK_AUTH", "false")

        val tokenRes = client.post("/api/v1/instances/token") {
            contentType(ContentType.Application.Json)
            setBody(Json.encodeToString(IssueInstanceTokenRequest(
                tenantId = tenantA,
                instanceId = "inst_backend_01",
                name = "생존 서버 1"
            )))
        }
        assertEquals(HttpStatusCode.Created, tokenRes.status)
        val resJson = Json.parseToJsonElement(tokenRes.bodyAsText()).jsonObject
        assertTrue(resJson["token"]!!.jsonPrimitive.content.isNotBlank())
    }

    @Test
    fun `타 테넌트 접근 시 403 Forbidden 차단되어야 한다`() = testApplication {
        val oauthService = DiscordOAuthService(jedis)
        val userA = "user_only_alpha"
        jedis.setex("rubeacon:discord:user:" + userA + ":guilds", 300, Json.encodeToString(listOf(
            DiscordGuildInfo(id = guildA, name = "알파 서버", isOwner = true)
        )))

        application {
            module(database = database, jedis = jedis, oauthService = oauthService)
        }

        val client = createClient {
            install(HttpCookies)
        }

        System.setProperty("DEV_MOCK_AUTH", "true")
        client.post("/api/v1/auth/dev-mock-login") {
            contentType(ContentType.Application.Json)
            setBody(Json.encodeToString(DevMockLoginRequest(discordUserId = userA, username = "UserA")))
        }
        System.setProperty("DEV_MOCK_AUTH", "false")

        val res = client.get("/api/v1/tenants/" + tenantB)
        assertEquals(HttpStatusCode.Forbidden, res.status)
    }
}
