package com.rubeacon.api.db

import org.jetbrains.exposed.sql.ReferenceOption
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestampWithTimeZone
import org.jetbrains.exposed.sql.json.jsonb
import java.time.OffsetDateTime

/**
 * 테넌트(커뮤니티/고객사) 기본 정보 테이블.
 */
object Tenants : Table("tenants") {
    val id = varchar("id", 64)
    val name = varchar("name", 100)
    val discordGuildId = varchar("discord_guild_id", 32).uniqueIndex()
    val timezone = varchar("timezone", 50).default("Asia/Seoul")
    val maxAccountLinksPerUser = integer("max_account_links_per_user").default(2)
    val adminRoleId = varchar("admin_role_id", 32).nullable()
    val createdAt = timestampWithTimeZone("created_at").clientDefault { OffsetDateTime.now() }
    val updatedAt = timestampWithTimeZone("updated_at").clientDefault { OffsetDateTime.now() }

    override val primaryKey = PrimaryKey(id)
}

/**
 * 테넌트 소속 마인크래프트 서버 네트워크(Bungee/Velocity 또는 단일 서버군) 테이블.
 */
object MinecraftNetworks : Table("minecraft_networks") {
    val id = varchar("id", 64)
    val tenantId = reference("tenant_id", Tenants.id, onDelete = ReferenceOption.CASCADE)
    val name = varchar("name", 100)
    val createdAt = timestampWithTimeZone("created_at").clientDefault { OffsetDateTime.now() }
    val updatedAt = timestampWithTimeZone("updated_at").clientDefault { OffsetDateTime.now() }

    override val primaryKey = PrimaryKey(id)
}

/**
 * 마인크래프트 물리/가상 서버 인스턴스 정보 테이블.
 * WebSocket 연결 인증 토큰 해시 및 실시간 상태를 보관함.
 */
object MinecraftInstances : Table("minecraft_instances") {
    val id = varchar("id", 64)
    val networkId = reference("network_id", MinecraftNetworks.id, onDelete = ReferenceOption.CASCADE)
    val tenantId = reference("tenant_id", Tenants.id, onDelete = ReferenceOption.CASCADE)
    val instanceType = varchar("instance_type", 20)
    val name = varchar("name", 100)
    val tokenHash = varchar("token_hash", 128)
    val status = varchar("status", 20).default("OFFLINE")
    val lastHeartbeatAt = timestampWithTimeZone("last_heartbeat_at").nullable()
    val createdAt = timestampWithTimeZone("created_at").clientDefault { OffsetDateTime.now() }
    val updatedAt = timestampWithTimeZone("updated_at").clientDefault { OffsetDateTime.now() }

    override val primaryKey = PrimaryKey(id)
}

/**
 * Discord 계정과 마인크래프트 플레이어 UUID 간의 연동 및 2FA 인증 상태 테이블.
 */
object AccountLinks : Table("account_links") {
    val id = varchar("id", 64)
    val tenantId = reference("tenant_id", Tenants.id, onDelete = ReferenceOption.CASCADE)
    val discordUserId = varchar("discord_user_id", 32)
    val minecraftUuid = uuid("minecraft_uuid")
    val minecraftUsername = varchar("minecraft_username", 32)
    val status = varchar("status", 20) // 'PENDING', 'ACTIVE'
    val verificationCode = varchar("verification_code", 16).nullable()
    val codeExpiresAt = timestampWithTimeZone("code_expires_at").nullable()
    val failedAttempts = integer("failed_attempts").default(0)
    val verifiedAt = timestampWithTimeZone("verified_at").nullable()
    val createdAt = timestampWithTimeZone("created_at").clientDefault { OffsetDateTime.now() }
    val updatedAt = timestampWithTimeZone("updated_at").clientDefault { OffsetDateTime.now() }

    override val primaryKey = PrimaryKey(id)
}

/**
 * 자동화 워크플로우 메타데이터 테이블.
 */
object Workflows : Table("workflows") {
    val id = varchar("id", 64)
    val tenantId = reference("tenant_id", Tenants.id, onDelete = ReferenceOption.CASCADE)
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
    val workflowId = reference("workflow_id", Workflows.id, onDelete = ReferenceOption.CASCADE)
    val tenantId = reference("tenant_id", Tenants.id, onDelete = ReferenceOption.CASCADE)
    val version = integer("version")
    val status = varchar("status", 20).default("DRAFT") // 'DRAFT', 'TESTING', 'ACTIVE', 'INACTIVE', 'ARCHIVED'
    val definition = jsonb<String>("definition", { it }, { it })
    val publishedAt = timestampWithTimeZone("published_at").nullable()
    val createdAt = timestampWithTimeZone("created_at").clientDefault { OffsetDateTime.now() }

    override val primaryKey = PrimaryKey(id)
}

/**
 * 테넌트별 관리자 2FA 정책 테이블.
 */
object Admin2faPolicies : Table("admin_2fa_policies") {
    val tenantId = reference("tenant_id", Tenants.id, onDelete = ReferenceOption.CASCADE)
    val policyMode = varchar("policy_mode", 20).default("MONITOR") // 'DISABLED', 'MONITOR', 'ENFORCE'
    val authChannelId = varchar("auth_channel_id", 32).nullable()
    val timeoutSeconds = integer("timeout_seconds").default(60)
    val updatedAt = timestampWithTimeZone("updated_at").clientDefault { OffsetDateTime.now() }

    override val primaryKey = PrimaryKey(tenantId)
}

/**
 * 테넌트 비즈니스 및 관리자 보안 감사 로그 테이블.
 */
object AuditLogs : Table("audit_logs") {
    val id = varchar("id", 64)
    val tenantId = reference("tenant_id", Tenants.id, onDelete = ReferenceOption.CASCADE)
    val correlationId = varchar("correlation_id", 64)
    val actorType = varchar("actor_type", 32)
    val actorId = varchar("actor_id", 64)
    val action = varchar("action", 64)
    val targetType = varchar("target_type", 32).nullable()
    val targetId = varchar("target_id", 64).nullable()
    val status = varchar("status", 20)
    val details = jsonb<String>("details", { it }, { it })
    val ipAddress = registerColumn<String>("ip_address", object : org.jetbrains.exposed.sql.ColumnType<String>() {
        override fun sqlType(): String = "INET"
        override fun setParameter(stmt: org.jetbrains.exposed.sql.statements.api.PreparedStatementApi, index: Int, value: Any?) {
            if (value == null) {
                stmt.setNull(index, this)
            } else {
                val obj = org.postgresql.util.PGobject().apply {
                    type = "inet"
                    this.value = value.toString()
                }
                stmt[index] = obj
            }
        }
        override fun valueFromDB(value: Any): String = value.toString()
    }).nullable()
    val createdAt = timestampWithTimeZone("created_at").clientDefault { OffsetDateTime.now() }

    override val primaryKey = PrimaryKey(id)
}

