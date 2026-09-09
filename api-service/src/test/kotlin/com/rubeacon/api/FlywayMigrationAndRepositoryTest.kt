package com.rubeacon.api

import com.rubeacon.api.db.AccountLinks
import com.rubeacon.api.db.MinecraftInstances
import com.rubeacon.api.db.MinecraftNetworks
import com.rubeacon.api.db.Tenants
import com.rubeacon.api.db.Workflows
import com.rubeacon.api.service.AccountLinkService
import com.rubeacon.api.service.InstanceAuthService
import com.rubeacon.api.service.LinkResult
import com.rubeacon.api.service.VerifyResult
import org.jetbrains.exposed.sql.deleteAll
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class FlywayMigrationAndRepositoryTest : BaseIntegrationTest() {

    private val authService = InstanceAuthService()
    private val linkService = AccountLinkService()

    @BeforeEach
    fun clearTables() {
        transaction(database) {
            AccountLinks.deleteAll()
            MinecraftInstances.deleteAll()
            MinecraftNetworks.deleteAll()
            Workflows.deleteAll()
            Tenants.deleteAll()
        }
    }

    @Test
    fun `Flyway 마이그레이션이 실행되어 테넌트 및 인스턴스가 정상 저장되어야 한다`() {
        val tenantId = "tenant_test_01"
        val networkId = "net_test_01"
        val instanceId = "inst_test_01"
        val rawToken = "super-secret-token"
        val tokenHash = authService.hashToken(rawToken)

        transaction(database) {
            Tenants.insert {
                it[id] = tenantId
                it[name] = "테스트 커뮤니티"
                it[discordGuildId] = "123456789012345678"
                it[timezone] = "Asia/Seoul"
                it[maxAccountLinksPerUser] = 2
            }

            MinecraftNetworks.insert {
                it[id] = networkId
                it[this.tenantId] = tenantId
                it[name] = "메인 로비 네트워크"
            }

            MinecraftInstances.insert {
                it[id] = instanceId
                it[this.networkId] = networkId
                it[this.tenantId] = tenantId
                it[instanceType] = "BACKEND"
                it[name] = "서바이벌 1서버"
                it[this.tokenHash] = tokenHash
                it[status] = "OFFLINE"
            }
        }

        // 인증 검증
        assertTrue(authService.authenticate(tenantId, instanceId, rawToken))
        assertFalse(authService.authenticate(tenantId, instanceId, "wrong-token"))
        assertFalse(authService.authenticate("other_tenant", instanceId, rawToken))

        // 상태 갱신 검증
        authService.updateStatus(instanceId, "ONLINE", updateHeartbeat = true)
        transaction(database) {
            val updated = MinecraftInstances.selectAll().where { MinecraftInstances.id eq instanceId }.single()
            assertEquals("ONLINE", updated[MinecraftInstances.status])
            assertNotNull(updated[MinecraftInstances.lastHeartbeatAt])
        }
    }

    @Test
    fun `계정 연동 요청 시 5분 만료 코드가 생성되고 정상 인증 시 ACTIVE로 전이되어야 한다`() {
        val tenantId = "tenant_test_02"
        transaction(database) {
            Tenants.insert {
                it[id] = tenantId
                it[name] = "테넌트 2"
                it[discordGuildId] = "987654321098765432"
                it[maxAccountLinksPerUser] = 2
            }
        }

        val discordUserId = "discord_user_100"
        val playerUuid = UUID.randomUUID()
        val username = "Steve"

        // 1. 연동 코드 발급
        val reqResult = linkService.requestLink(tenantId, discordUserId, playerUuid, username)
        assertTrue(reqResult is LinkResult.Success)
        val code = reqResult.code
        assertEquals(6, code.length)
        assertTrue(reqResult.expiresAt.isAfter(OffsetDateTime.now().plusMinutes(4)))

        // 2. 오답 입력 시 InvalidCode
        val invalidResult = linkService.verifyCode(tenantId, playerUuid, "WRONG1")
        assertEquals(VerifyResult.InvalidCode, invalidResult)

        // 3. 정답 입력 시 Success 및 ACTIVE 전이
        val verifySuccess = linkService.verifyCode(tenantId, playerUuid, code)
        assertEquals(VerifyResult.Success, verifySuccess)

        transaction(database) {
            val link = AccountLinks.selectAll().where { AccountLinks.minecraftUuid eq playerUuid }.single()
            assertEquals("ACTIVE", link[AccountLinks.status])
            assertNotNull(link[AccountLinks.verifiedAt])
        }

        // 4. 최대 계정 연동 수 초과 제한 검증
        val secondPlayer = UUID.randomUUID()
        val thirdPlayer = UUID.randomUUID()

        val secondReq = linkService.requestLink(tenantId, discordUserId, secondPlayer, "Alex")
        assertTrue(secondReq is LinkResult.Success)
        linkService.verifyCode(tenantId, secondPlayer, secondReq.code)

        // 세 번째 연동 시도는 LimitExceeded 발생해야 함
        val thirdReq = linkService.requestLink(tenantId, discordUserId, thirdPlayer, "Zombie")
        assertTrue(thirdReq is LinkResult.LimitExceeded)
        assertEquals(2, thirdReq.maxAllowed)
    }

    @Test
    fun `연동 코드 검증 5회 연속 실패 시 코드가 즉시 파기되고 TooManyAttempts로 잠겨야 한다`() {
        val tenantId = "tenant_test_lockout"
        transaction(database) {
            Tenants.insert {
                it[id] = tenantId
                it[name] = "보안 테넌트"
                it[discordGuildId] = "777888999111222333"
            }
        }

        val playerUuid = UUID.randomUUID()
        val reqResult = linkService.requestLink(tenantId, "discord_attacker", playerUuid, "Attacker")
        assertTrue(reqResult is LinkResult.Success)
        val validCode = reqResult.code

        // 4회 실패
        repeat(4) {
            val result = linkService.verifyCode(tenantId, playerUuid, "WRONG$it")
            assertEquals(VerifyResult.InvalidCode, result)
        }

        // 5회째 실패 -> TooManyAttempts 반환
        val fifthResult = linkService.verifyCode(tenantId, playerUuid, "WRONG5")
        assertEquals(VerifyResult.TooManyAttempts, fifthResult)

        // 6회째 이후는 올바른 코드를 넣어도 이미 파기되어 TooManyAttempts
        val subsequentResult = linkService.verifyCode(tenantId, playerUuid, validCode)
        assertEquals(VerifyResult.TooManyAttempts, subsequentResult)

        // DB에 verification_code가 null로 파기되었는지 확인
        transaction(database) {
            val link = AccountLinks.selectAll().where { AccountLinks.minecraftUuid eq playerUuid }.single()
            kotlin.test.assertNull(link[AccountLinks.verificationCode])
            assertEquals(5, link[AccountLinks.failedAttempts])
        }
    }

    @Test
    fun `이미 다른 디스코드 유저에게 ACTIVE로 연동된 계정에 대해 타인이 requestLink 시도시 AlreadyLinked로 차단되어야 한다`() {
        val tenantId = "tenant_test_hijack"
        transaction(database) {
            Tenants.insert {
                it[id] = tenantId
                it[name] = "보안 테넌트 2"
                it[discordGuildId] = "888999000111222333"
            }
        }

        val playerUuid = UUID.randomUUID()
        val originalOwner = "discord_legit_user"
        val attacker = "discord_attacker_user"

        // 1. 정상 유저가 연동 및 완료
        val req1 = linkService.requestLink(tenantId, originalOwner, playerUuid, "LegitPlayer")
        assertTrue(req1 is LinkResult.Success)
        val verifyRes = linkService.verifyCode(tenantId, playerUuid, req1.code)
        assertEquals(VerifyResult.Success, verifyRes)

        // 2. 공격자가 동일 UUID에 대해 requestLink 시도 시 AlreadyLinked 반환 확인
        val hijackAttempt = linkService.requestLink(tenantId, attacker, playerUuid, "LegitPlayer")
        assertTrue(hijackAttempt is LinkResult.AlreadyLinked)
        assertEquals(originalOwner, hijackAttempt.existingDiscordUserId)

        // 3. 기존 소유자의 ACTIVE 상태가 유지되는지 확인
        transaction(database) {
            val link = AccountLinks.selectAll().where { AccountLinks.minecraftUuid eq playerUuid }.single()
            assertEquals("ACTIVE", link[AccountLinks.status])
            assertEquals(originalOwner, link[AccountLinks.discordUserId])
        }
    }
}
