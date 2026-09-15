package com.rubeacon.api.service

import com.rubeacon.api.db.MinecraftInstances
import com.rubeacon.common.redis.RedisNamespaces
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import redis.clients.jedis.JedisPooled
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
        MinecraftInstances
            .selectAll()
            .where {
                (MinecraftInstances.id eq instanceId) and
                        (MinecraftInstances.tenantId eq tenantId) and
                        (MinecraftInstances.tokenHash eq expectedHash)
            }
            .count() > 0
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

    /**
     * Redis Presence 키가 만료되었으나 RDB에 여전히 ONLINE으로 남아있는 고아 세션을 STALE로 일괄 정리함.
     * 주기적(60초) 백그라운드 리퍼 루프에서 호출되어 목록 조회 및 인덱스 정합성을 보장함.
     *
     * @return STALE로 전이된 고아 인스턴스 수
     */
    fun reapStaleInstances(jedis: JedisPooled): Int = transaction {
        val onlineIds = MinecraftInstances.select(MinecraftInstances.id)
            .where { MinecraftInstances.status eq "ONLINE" }
            .map { it[MinecraftInstances.id] }

        var reaped = 0
        for (id in onlineIds) {
            val key = RedisNamespaces.instanceHeartbeatKey(id)
            if (!jedis.exists(key)) {
                MinecraftInstances.update({ MinecraftInstances.id eq id }) {
                    it[status] = "STALE"
                    it[updatedAt] = OffsetDateTime.now()
                }
                reaped++
            }
        }
        reaped
    }
}
