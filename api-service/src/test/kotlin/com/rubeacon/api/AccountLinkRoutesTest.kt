package com.rubeacon.api

import com.rubeacon.api.db.AccountLinks
import com.rubeacon.api.db.MinecraftInstances
import com.rubeacon.api.db.MinecraftNetworks
import com.rubeacon.api.db.Tenants
import com.rubeacon.api.db.Workflows
import com.rubeacon.api.routes.ApiResponse
import com.rubeacon.api.routes.LinkRequestDto
import com.rubeacon.api.routes.VerifyRequestDto
import com.rubeacon.common.serialization.RuBeaconJson
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import org.jetbrains.exposed.sql.deleteAll
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AccountLinkRoutesTest : BaseIntegrationTest() {

    @BeforeEach
    fun clearData() {
        transaction(database) {
            AccountLinks.deleteAll()
            MinecraftInstances.deleteAll()
            MinecraftNetworks.deleteAll()
            Workflows.deleteAll()
            Tenants.deleteAll()
        }
    }

    @Test
    fun `REST API를 통한 연동 요청 및 코드 인증이 성공해야 한다`() = testApplication {
        val tenantId = "tenant_rest_01"
        transaction(database) {
            Tenants.insert {
                it[id] = tenantId
                it[name] = "REST 테스트 테넌트"
                it[discordGuildId] = "555666777888999000"
                it[maxAccountLinksPerUser] = 2
            }
        }

        application {
            module(database = database, jedis = jedis)
        }

        val client = createClient {}

        val playerUuid = UUID.randomUUID().toString()

        // 1. POST /api/v1/accounts/link/request
        val reqBody = LinkRequestDto(
            tenantId = tenantId,
            discordUserId = "discord_12345",
            minecraftUuid = playerUuid,
            minecraftUsername = "Alex"
        )
        val reqResponse = client.post("/api/v1/accounts/link/request") {
            contentType(ContentType.Application.Json)
            setBody(RuBeaconJson.default.encodeToString(reqBody))
        }
        assertEquals(HttpStatusCode.OK, reqResponse.status)
        val linkRes = RuBeaconJson.default.decodeFromString<ApiResponse>(reqResponse.bodyAsText())
        assertTrue(linkRes.success)
        val code = linkRes.code
        assertNotNull(code)
        assertEquals(6, code.length)

        // 2. POST /api/v1/accounts/link/verify
        val verifyBody = VerifyRequestDto(
            tenantId = tenantId,
            minecraftUuid = playerUuid,
            code = code
        )
        val verifyResponse = client.post("/api/v1/accounts/link/verify") {
            contentType(ContentType.Application.Json)
            setBody(RuBeaconJson.default.encodeToString(verifyBody))
        }
        assertEquals(HttpStatusCode.OK, verifyResponse.status)
        val verifyRes = RuBeaconJson.default.decodeFromString<ApiResponse>(verifyResponse.bodyAsText())
        assertTrue(verifyRes.success)
    }

    @Test
    fun `이미 연동된 마인크래프트 계정을 타인이 요청할 경우 Conflict(409)를 반환해야 한다`() = testApplication {
        val tenantId = "tenant_rest_hijack"
        transaction(database) {
            Tenants.insert {
                it[id] = tenantId
                it[name] = "REST 탈취 방어 테넌트"
                it[discordGuildId] = "666777888999000111"
            }
        }

        application {
            module(database = database, jedis = jedis)
        }

        val client = createClient {}
        val playerUuid = UUID.randomUUID().toString()

        // 1. 유저 1 연동 및 인증 완료
        val req1 = LinkRequestDto(
            tenantId = tenantId,
            discordUserId = "discord_legit",
            minecraftUuid = playerUuid,
            minecraftUsername = "Steve"
        )
        val res1 = client.post("/api/v1/accounts/link/request") {
            contentType(ContentType.Application.Json)
            setBody(RuBeaconJson.default.encodeToString(req1))
        }
        val linkRes1 = RuBeaconJson.default.decodeFromString<ApiResponse>(res1.bodyAsText())
        client.post("/api/v1/accounts/link/verify") {
            contentType(ContentType.Application.Json)
            setBody(RuBeaconJson.default.encodeToString(VerifyRequestDto(tenantId, playerUuid, linkRes1.code!!)))
        }

        // 2. 유저 2가 동일 playerUuid로 연동 요청 시 409 Conflict 발생 확인
        val req2 = LinkRequestDto(
            tenantId = tenantId,
            discordUserId = "discord_attacker",
            minecraftUuid = playerUuid,
            minecraftUsername = "Steve"
        )
        val res2 = client.post("/api/v1/accounts/link/request") {
            contentType(ContentType.Application.Json)
            setBody(RuBeaconJson.default.encodeToString(req2))
        }
        assertEquals(HttpStatusCode.Conflict, res2.status)
        val linkRes2 = RuBeaconJson.default.decodeFromString<ApiResponse>(res2.bodyAsText())
        kotlin.test.assertFalse(linkRes2.success)
    }
}
