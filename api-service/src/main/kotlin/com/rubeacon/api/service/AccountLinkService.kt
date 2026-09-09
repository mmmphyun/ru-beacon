package com.rubeacon.api.service

import com.rubeacon.api.db.AccountLinks
import com.rubeacon.api.db.Tenants
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.jetbrains.exposed.sql.upsert
import java.security.SecureRandom
import java.time.OffsetDateTime
import java.util.UUID

/**
 * 계정 연동 요청 및 인증 결과 DTO.
 */
sealed interface LinkResult {
    data class Success(val linkId: String, val code: String, val expiresAt: OffsetDateTime) : LinkResult
    data class LimitExceeded(val maxAllowed: Int) : LinkResult
    data class AlreadyLinked(val existingDiscordUserId: String) : LinkResult
}

sealed interface VerifyResult {
    data object Success : VerifyResult
    data object Expired : VerifyResult
    data object InvalidCode : VerifyResult
    data object NotFound : VerifyResult
    data object TooManyAttempts : VerifyResult
}

/**
 * Discord 계정과 Minecraft UUID 간의 1회용 6자리 인증코드 발급 및 만료 검증 서비스.
 */
class AccountLinkService {
    private val random = SecureRandom()

    /**
     * 6자리 대문자/숫자 조합의 일회성 인증 코드를 생성함.
     */
    private fun generateCode(): String {
        val chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        return (1..6).map { chars[random.nextInt(chars.length)] }.joinToString("")
    }

    /**
     * 신규 연동 요청을 등록하고 5분 만료 일회성 코드를 발급함.
     * 테넌트의 최대 연동 허용 수를 초과하면 LimitExceeded 반환.
     */
    fun requestLink(
        tenantId: String,
        discordUserId: String,
        minecraftUuid: UUID,
        minecraftUsername: String
    ): LinkResult = transaction {
        val tenant = Tenants
            .selectAll()
            .where { Tenants.id eq tenantId }
            .singleOrNull() ?: throw IllegalArgumentException("존재하지 않는 테넌트입니다: $tenantId")

        val maxAllowed = tenant[Tenants.maxAccountLinksPerUser]

        // 해당 디스코드 유저의 활성 연동 수 확인 (자기 자신 제외)
        val currentActiveCount = AccountLinks
            .selectAll()
            .where {
                (AccountLinks.tenantId eq tenantId) and
                        (AccountLinks.discordUserId eq discordUserId) and
                        (AccountLinks.status eq "ACTIVE") and
                        (AccountLinks.minecraftUuid neq minecraftUuid)
            }
            .count()

        if (currentActiveCount >= maxAllowed) {
            return@transaction LinkResult.LimitExceeded(maxAllowed)
        }

        val code = generateCode()
        val now = OffsetDateTime.now()
        val expiresAt = now.plusMinutes(5)
        val linkId = "link_${UUID.randomUUID().toString().replace("-", "").take(16)}"

        // 기존에 동일 tenant_id + minecraft_uuid 쌍이 있으면 갱신, 없으면 삽입
        val existing = AccountLinks
            .selectAll()
            .where { (AccountLinks.tenantId eq tenantId) and (AccountLinks.minecraftUuid eq minecraftUuid) }
            .singleOrNull()

        if (existing != null) {
            // 이미 다른 디스코드 유저에게 ACTIVE 상태로 연동된 마인크래프트 계정 가로채기(Hijacking) 차단
            val currentStatus = existing[AccountLinks.status]
            val linkedDiscordUser = existing[AccountLinks.discordUserId]
            if (currentStatus == "ACTIVE" && linkedDiscordUser != discordUserId) {
                return@transaction LinkResult.AlreadyLinked(linkedDiscordUser)
            }

            val id = existing[AccountLinks.id]
            AccountLinks.update({ AccountLinks.id eq id }) {
                it[AccountLinks.discordUserId] = discordUserId
                it[AccountLinks.minecraftUsername] = minecraftUsername
                it[AccountLinks.status] = "PENDING"
                it[AccountLinks.verificationCode] = code
                it[AccountLinks.codeExpiresAt] = expiresAt
                it[AccountLinks.failedAttempts] = 0
                it[AccountLinks.updatedAt] = now
            }
            LinkResult.Success(id, code, expiresAt)
        } else {
            AccountLinks.insert {
                it[AccountLinks.id] = linkId
                it[AccountLinks.tenantId] = tenantId
                it[AccountLinks.discordUserId] = discordUserId
                it[AccountLinks.minecraftUuid] = minecraftUuid
                it[AccountLinks.minecraftUsername] = minecraftUsername
                it[AccountLinks.status] = "PENDING"
                it[AccountLinks.verificationCode] = code
                it[AccountLinks.codeExpiresAt] = expiresAt
                it[AccountLinks.failedAttempts] = 0
                it[AccountLinks.createdAt] = now
                it[AccountLinks.updatedAt] = now
            }
            LinkResult.Success(linkId, code, expiresAt)
        }
    }

    /**
     * 마인크래프트 인게임에서 입력한 인증 코드를 검증하고 ACTIVE 상태로 전환함.
     */
    fun verifyCode(tenantId: String, minecraftUuid: UUID, code: String): VerifyResult = transaction {
        val link = AccountLinks
            .selectAll()
            .where {
                (AccountLinks.tenantId eq tenantId) and
                        (AccountLinks.minecraftUuid eq minecraftUuid) and
                        (AccountLinks.status eq "PENDING")
            }
            .singleOrNull() ?: return@transaction VerifyResult.NotFound

        val linkId = link[AccountLinks.id]
        val failedAttempts = link[AccountLinks.failedAttempts]
        val expectedCode = link[AccountLinks.verificationCode]
        val expiresAt = link[AccountLinks.codeExpiresAt]
        val now = OffsetDateTime.now()

        // 5회 이상 실패 시 잠금
        if (failedAttempts >= 5) {
            return@transaction VerifyResult.TooManyAttempts
        }

        // 만료 체크
        if (expiresAt == null || now.isAfter(expiresAt)) {
            return@transaction VerifyResult.Expired
        }

        // 코드 불일치 체크
        if (expectedCode == null || !expectedCode.equals(code.trim(), ignoreCase = true)) {
            val newAttempts = failedAttempts + 1
            AccountLinks.update({ AccountLinks.id eq linkId }) {
                it[AccountLinks.failedAttempts] = newAttempts
                if (newAttempts >= 5) {
                    // 무차별 대입 공격 차단: 5회 실패 시 일회성 코드 즉시 파기
                    it[AccountLinks.verificationCode] = null
                    it[AccountLinks.codeExpiresAt] = null
                }
                it[AccountLinks.updatedAt] = now
            }
            return@transaction if (newAttempts >= 5) VerifyResult.TooManyAttempts else VerifyResult.InvalidCode
        }

        // 인증 성공: ACTIVE 전환 및 1회용 코드 파기
        AccountLinks.update({ AccountLinks.id eq linkId }) {
            it[AccountLinks.status] = "ACTIVE"
            it[AccountLinks.verificationCode] = null
            it[AccountLinks.codeExpiresAt] = null
            it[AccountLinks.verifiedAt] = now
            it[AccountLinks.updatedAt] = now
        }

        VerifyResult.Success
    }
}
