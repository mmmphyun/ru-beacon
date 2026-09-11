package com.rubeacon.api.routes

import com.rubeacon.api.auth.AuthConfig
import com.rubeacon.api.auth.DiscordOAuthService
import com.rubeacon.api.auth.resolveUserSession
import com.rubeacon.api.db.AuditLogs
import com.rubeacon.api.db.Tenants
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.andWhere
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException

@Serializable
data class AuditLogDto(
    val id: String,
    val tenantId: String,
    val correlationId: String,
    val actorType: String,
    val actorId: String,
    val action: String,
    val targetType: String?,
    val targetId: String?,
    val status: String,
    val details: String,
    val ipAddress: String?,
    val createdAt: String
)

@Serializable
data class AuditLogPageResponse(
    val items: List<AuditLogDto>,
    val nextCursor: String?
)

/**
 * 개인정보보호법 및 ISMS-P 기준 준수를 위한 IP 마스킹 유틸.
 * 끝자리 옥텟을 비식별화함.
 */
fun maskIpAddress(ip: String?): String? {
    if (ip.isNullOrBlank()) return null
    val clean = ip.trim().removePrefix("/").split(":")[0] // 포트 또는 CIDR 프리픽스 제거
    return if (clean.contains(".")) {
        val parts = clean.split(".")
        if (parts.size == 4) {
            "${parts[0]}.${parts[1]}.***.***"
        } else {
            "***.***.***.***"
        }
    } else if (clean.contains(":")) {
        val parts = clean.split(":")
        if (parts.size >= 2) {
            "${parts[0]}:${parts[1]}:****:****"
        } else {
            "****:****:****"
        }
    } else {
        "***.***.***.***"
    }
}

private val logger = LoggerFactory.getLogger("AuditLogRoute")

fun Route.auditLogRoutes(oauthService: DiscordOAuthService) {
    route("/api/v1/audit-logs") {
        get {
            val session = call.resolveUserSession()
            if (session == null) {
                call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "인증 세션이 필요합니다."))
                return@get
            }

            val tenantId = call.request.queryParameters["tenantId"]
            val limitParam = call.request.queryParameters["limit"]?.toIntOrNull() ?: 50
            val limit = limitParam.coerceIn(1, 100)
            val cursor = call.request.queryParameters["cursor"]

            // 1. 권한 인가 분기
            if (tenantId.isNullOrBlank()) {
                // 테넌트 미지정 시: 플랫폼 운영자 전수 조회 (이중 검증 필수)
                val isSuperAdmin = AuthConfig.superAdminUserIds.contains(session.discordUserId) ||
                        (AuthConfig.isDevMockAuth && call.request.headers["X-Dev-Mock-Super-Admin"] == "true")
                val secretHeader = call.request.headers["X-Platform-Admin-Secret"]

                if (!isSuperAdmin || secretHeader != AuthConfig.platformAdminSecret) {
                    call.respond(HttpStatusCode.Forbidden, mapOf("error" to "전체 감사 로그 전수 조회는 플랫폼 운영자 이중 검증 통과가 필수입니다."))
                    return@get
                }
            } else {
                // 특정 테넌트 조회 시: 해당 테넌트 관리자(OWNER/ADMIN)인지 검증
                if (!AuthConfig.isDevMockAuth) {
                    val tenantRow = transaction {
                        Tenants.selectAll().where { Tenants.id eq tenantId }.singleOrNull()
                    }
                    if (tenantRow == null) {
                        call.respond(HttpStatusCode.NotFound, mapOf("error" to "존재하지 않는 테넌트입니다."))
                        return@get
                    }

                    val guildId = tenantRow[Tenants.discordGuildId]
                    val adminRoleId = tenantRow[Tenants.adminRoleId]
                    val cachedGuilds = oauthService.getCachedUserGuilds(session.discordUserId)
                    val guild = cachedGuilds.find { it.id == guildId }

                    val isOwner = guild?.isOwner == true
                    val hasAdminRole = adminRoleId != null && guild?.roles?.contains(adminRoleId) == true

                    if (!isOwner && !hasAdminRole) {
                        call.respond(HttpStatusCode.Forbidden, mapOf("error" to "해당 테넌트의 감사 로그를 열람할 권한이 없습니다."))
                        return@get
                    }
                }
            }

            // 2. 커서 기반 페이징 쿼리 (idx_audit_logs_tenant_created 활용)
            val cursorTime = if (!cursor.isNullOrBlank()) {
                try {
                    OffsetDateTime.parse(cursor)
                } catch (_: DateTimeParseException) {
                    null
                }
            } else null

            val query = transaction {
                var q = AuditLogs.selectAll()
                if (!tenantId.isNullOrBlank()) {
                    q = q.where { AuditLogs.tenantId eq tenantId }
                }
                if (cursorTime != null) {
                    q = q.andWhere { AuditLogs.createdAt less cursorTime }
                }
                q.orderBy(AuditLogs.createdAt, SortOrder.DESC)
                    .limit(limit + 1)
                    .toList()
            }

            val hasMore = query.size > limit
            val itemsToReturn = if (hasMore) query.take(limit) else query
            val nextCursor = if (hasMore) itemsToReturn.lastOrNull()?.get(AuditLogs.createdAt)?.toString() else null

            val dtoList = itemsToReturn.map { row ->
                AuditLogDto(
                    id = row[AuditLogs.id],
                    tenantId = row[AuditLogs.tenantId],
                    correlationId = row[AuditLogs.correlationId],
                    actorType = row[AuditLogs.actorType],
                    actorId = row[AuditLogs.actorId],
                    action = row[AuditLogs.action],
                    targetType = row[AuditLogs.targetType],
                    targetId = row[AuditLogs.targetId],
                    status = row[AuditLogs.status],
                    details = row[AuditLogs.details],
                    ipAddress = maskIpAddress(row[AuditLogs.ipAddress]),
                    createdAt = row[AuditLogs.createdAt].toString()
                )
            }

            call.respond(HttpStatusCode.OK, AuditLogPageResponse(
                items = dtoList,
                nextCursor = nextCursor
            ))
        }
    }
}