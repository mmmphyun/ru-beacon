package com.rubeacon.worker.db

import kotlinx.coroutines.Dispatchers
import org.jetbrains.exposed.sql.json.jsonb
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.javatime.date
import org.jetbrains.exposed.sql.javatime.timestampWithTimeZone
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import java.time.OffsetDateTime
import java.util.UUID

/**
 * 테넌트 기본 정보 테이블 매핑 (외래 키 무결성 보장용).
 */
object Tenants : Table("tenants") {
    val id = varchar("id", 64)
    val name = varchar("name", 100)
    val discordGuildId = varchar("discord_guild_id", 32).uniqueIndex()
    val timezone = varchar("timezone", 50).default("Asia/Seoul")
    val maxAccountLinksPerUser = integer("max_account_links_per_user").default(2)
    val createdAt = timestampWithTimeZone("created_at").clientDefault { OffsetDateTime.now() }
    val updatedAt = timestampWithTimeZone("updated_at").clientDefault { OffsetDateTime.now() }

    override val primaryKey = PrimaryKey(id)
}

/**
 * 일일 출석 한정 보상 쿼터 테이블 매핑.
 */
object AttendanceQuotas : Table("attendance_quotas") {
    val id = varchar("id", 64)
    val tenantId = varchar("tenant_id", 64)
    val rewardDate = date("reward_date")
    val totalLimit = integer("total_limit")
    val reservedCount = integer("reserved_count")
    val committedCount = integer("committed_count")
    val createdAt = timestampWithTimeZone("created_at").clientDefault { OffsetDateTime.now() }
    val updatedAt = timestampWithTimeZone("updated_at").clientDefault { OffsetDateTime.now() }

    override val primaryKey = PrimaryKey(id)
}

/**
 * 일일 출석 선착순 보상 원자적 예약 상태 테이블 매핑.
 */
object RewardReservations : Table("reward_reservations") {
    val id = varchar("id", 64)
    val tenantId = varchar("tenant_id", 64)
    val quotaId = varchar("quota_id", 64)
    val rewardDate = date("reward_date")
    val playerUuid = uuid("player_uuid")
    val status = varchar("status", 20) // 'RESERVED', 'COMMITTED', 'RELEASED'
    val idempotencyKey = varchar("idempotency_key", 128)
    val expiresAt = timestampWithTimeZone("expires_at")
    val createdAt = timestampWithTimeZone("created_at").clientDefault { OffsetDateTime.now() }
    val updatedAt = timestampWithTimeZone("updated_at").clientDefault { OffsetDateTime.now() }

    override val primaryKey = PrimaryKey(id)
}

/**
 * 실행 결과 및 보안 감사 로그 테이블 매핑.
 */
object AuditLogs : Table("audit_logs") {
    val id = varchar("id", 64)
    val tenantId = varchar("tenant_id", 64)
    val correlationId = varchar("correlation_id", 64)
    val actorType = varchar("actor_type", 32)
    val actorId = varchar("actor_id", 64)
    val action = varchar("action", 64)
    val targetType = varchar("target_type", 32).nullable()
    val targetId = varchar("target_id", 64).nullable()
    val status = varchar("status", 20) // 'SUCCESS', 'FAILURE', 'PARTIAL_FAILURE'
    val details = jsonb<String>("details", { it }, { it })
    val createdAt = timestampWithTimeZone("created_at").clientDefault { OffsetDateTime.now() }

    override val primaryKey = PrimaryKey(id)
}

/**
 * 워크플로우 실행 종료 시 단 1회 비동기로 감사 로그를 기록하는 영속성 계층.
 */
class AuditLogger {
    suspend fun record(
        tenantId: String,
        correlationId: String,
        actorType: String,
        actorId: String,
        action: String,
        status: String,
        detailsJson: String,
        targetType: String? = null,
        targetId: String? = null
    ) {
        newSuspendedTransaction(Dispatchers.IO) {
            AuditLogs.insert {
                it[id] = "audit_${UUID.randomUUID().toString().replace("-", "").take(24)}"
                it[AuditLogs.tenantId] = tenantId
                it[AuditLogs.correlationId] = correlationId
                it[AuditLogs.actorType] = actorType
                it[AuditLogs.actorId] = actorId
                it[AuditLogs.action] = action
                it[AuditLogs.status] = status
                it[details] = detailsJson
                it[AuditLogs.targetType] = targetType
                it[AuditLogs.targetId] = targetId
                it[createdAt] = OffsetDateTime.now()
            }
        }
    }
}

/**
 * 자동화 워크플로우 메타데이터 테이블.
 */
object Workflows : Table("workflows") {
    val id = varchar("id", 64)
    val tenantId = reference("tenant_id", Tenants.id, onDelete = org.jetbrains.exposed.sql.ReferenceOption.CASCADE)
    val name = varchar("name", 100)
    val description = text("description").nullable()
    val activeVersion = integer("active_version").nullable()
    val createdAt = timestampWithTimeZone("created_at").clientDefault { OffsetDateTime.now() }
    val updatedAt = timestampWithTimeZone("updated_at").clientDefault { OffsetDateTime.now() }

    override val primaryKey = PrimaryKey(id)
}

/**
 * 워크플로우 버전별 DAG 정의(JSONB) 테이블.
 */
object WorkflowVersions : Table("workflow_versions") {
    val id = varchar("id", 64)
    val workflowId = reference("workflow_id", Workflows.id, onDelete = org.jetbrains.exposed.sql.ReferenceOption.CASCADE)
    val tenantId = reference("tenant_id", Tenants.id, onDelete = org.jetbrains.exposed.sql.ReferenceOption.CASCADE)
    val version = integer("version")
    val status = varchar("status", 20).default("DRAFT") // 'DRAFT', 'TESTING', 'ACTIVE', 'INACTIVE', 'ARCHIVED'
    val definition = jsonb<String>("definition", { it }, { it })
    val publishedAt = timestampWithTimeZone("published_at").nullable()
    val createdAt = timestampWithTimeZone("created_at").clientDefault { OffsetDateTime.now() }

    override val primaryKey = PrimaryKey(id)
}

