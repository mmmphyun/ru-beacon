package com.rubeacon.worker

import com.github.benmanes.caffeine.cache.Caffeine
import com.rubeacon.worker.db.WorkflowVersions
import com.rubeacon.worker.engine.WorkflowDefinition
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory
import java.time.Duration
import java.util.Optional

/**
 * Caffeine 기반 워커 로컬 캐시 (결함 3 해결: N+1 역직렬화 병목 해소).
 * 활성 워크플로우 정의를 인메모리에 캐싱하여 Redis Streams 소비 시 DB 쿼리 및 JSON 역직렬화 부하를 최소화함.
 */
class CachedWorkflowLookup(
    private val database: Database,
    private val json: Json = Json { ignoreUnknownKeys = true },
    maxSize: Long = 10_000,
    expireDuration: Duration = Duration.ofMinutes(5),
    private val onCacheMiss: (() -> Unit)? = null
) {
    private val log = LoggerFactory.getLogger(CachedWorkflowLookup::class.java)

    // Optional을 값으로 사용하여 워크플로우 부재(null) 상태도 캐싱 (Cache Penetration 방지)
    private val cache = Caffeine.newBuilder()
        .maximumSize(maxSize)
        .expireAfterWrite(expireDuration)
        .build<String, Optional<WorkflowDefinition>>()

    /**
     * 테넌트 ID와 이벤트 타입으로 활성 워크플로우 정의를 조회 (캐시 히트 시 즉시 반환).
     */
    operator fun invoke(eventType: String, tenantId: String): WorkflowDefinition? {
        val key = "$tenantId:$eventType"
        val cached = cache.get(key) {
            onCacheMiss?.invoke()
            val found = transaction(database) {
                WorkflowVersions.selectAll().where {
                    (WorkflowVersions.tenantId eq tenantId) and (WorkflowVersions.status eq "ACTIVE")
                }.mapNotNull { row ->
                    val defJson = row[WorkflowVersions.definition]
                    try {
                        val def = json.decodeFromString<WorkflowDefinition>(defJson)
                        if (def.trigger.eventType == eventType) def else null
                    } catch (e: Exception) {
                        log.error("워크플로우 역직렬화 실패 (tenant: {}, event: {}): {}", tenantId, eventType, e.message)
                        null
                    }
                }.firstOrNull()
            }
            Optional.ofNullable(found)
        }
        return cached?.orElse(null)
    }

    /**
     * 워크플로우 변경 시 특정 키의 캐시 무효화.
     */
    fun invalidate(tenantId: String, eventType: String) {
        cache.invalidate("$tenantId:$eventType")
    }

    /**
     * 전체 캐시 무효화.
     */
    fun invalidateAll() {
        cache.invalidateAll()
    }

    /**
     * 현재 캐시된 항목 수 반환.
     */
    fun estimatedSize(): Long = cache.estimatedSize()
}
