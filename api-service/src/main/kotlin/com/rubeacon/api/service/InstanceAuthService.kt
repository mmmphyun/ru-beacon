package com.rubeacon.api.service

import com.rubeacon.api.db.MinecraftInstances
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.security.MessageDigest
import java.time.OffsetDateTime

/**
 * 마인크래프트 인스턴스의 SHA-256 토큰 인증 및 실시간 상태(ONLINE/OFFLINE/STALE) 라이프사이클 관리 서비스.
 */
class InstanceAuthService {

    /**
     * 원문 토큰을 SHA-256 16진수 소문자 문자열로 해싱함.
     * DB 유출 시에도 원문 토큰이 노출되지 않도록 단방향 암호화하여 저장/비교함.
     */
    fun hashToken(token: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest(token.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    /**
     * WSS 핸드셰이크 시 인스턴스 인증을 수행함.
     *
     * @param tenantId 요청 헤더의 X-Tenant-Id
     * @param instanceId 요청 헤더의 X-Instance-Id
     * @param token 요청 헤더의 X-Instance-Token (원문)
     * @return DB에 등록된 token_hash와 일치하고 테넌트가 부합하면 true
     */
    fun authenticate(tenantId: String, instanceId: String, token: String): Boolean = transaction {
        val expectedHash = hashToken(token)
        val instance = MinecraftInstances
            .selectAll()
            .where { (MinecraftInstances.id eq instanceId) and (MinecraftInstances.tenantId eq tenantId) }
            .singleOrNull() ?: return@transaction false

        instance[MinecraftInstances.tokenHash] == expectedHash
    }

    /**
     * 인스턴스의 온라인 상태 및 하트비트 시각을 갱신함.
     */
    fun updateStatus(instanceId: String, status: String, updateHeartbeat: Boolean = false): Unit = transaction {
        val now = OffsetDateTime.now()
        MinecraftInstances.update({ MinecraftInstances.id eq instanceId }) {
            it[MinecraftInstances.status] = status
            it[MinecraftInstances.updatedAt] = now
            if (updateHeartbeat) {
                it[MinecraftInstances.lastHeartbeatAt] = now
            }
        }
    }
}
