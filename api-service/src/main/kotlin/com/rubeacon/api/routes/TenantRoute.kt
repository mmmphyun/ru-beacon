package com.rubeacon.api.routes

import com.rubeacon.api.auth.AuthConfig
import com.rubeacon.api.auth.resolveUserSession
import com.rubeacon.api.auth.withTenantAuth
import com.rubeacon.api.db.Admin2faPolicies
import com.rubeacon.api.db.MinecraftInstances
import com.rubeacon.api.db.MinecraftNetworks
import com.rubeacon.api.db.Tenants
import com.rubeacon.api.service.InstanceAuthService
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.insertIgnore
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.jetbrains.exposed.sql.upsert
import org.slf4j.LoggerFactory
import java.time.OffsetDateTime
import java.util.UUID

@Serializable
data class OnboardingRequest(
    val tenantId: String,
    val name: String,
    val discordGuildId: String,
    val adminRoleId: String? = null,
    val authChannelId: String? = null,
    val policyMode: String = "MONITOR"
)

@Serializable
data class IssueInstanceTokenRequest(
    val tenantId: String,
    val instanceId: String,
    val name: String,
    val networkId: String? = null,
    val instanceType: String = "BACKEND"
)

@Serializable
data class InstanceResponseDto(
    val id: String,
    val name: String,
    val instanceType: String,
    val status: String,
    val lastHeartbeatAt: String?
)

@Serializable
data class TenantDetailDto(
    val id: String,
    val name: String,
    val discordGuildId: String,
    val adminRoleId: String?,
    val policyMode: String,
    val authChannelId: String?,
    val instances: List<InstanceResponseDto>
)

private val logger = LoggerFactory.getLogger("TenantRoute")

fun Route.tenantRoutes(authService: InstanceAuthService, oauthService: com.rubeacon.api.auth.DiscordOAuthService) {
    route("/api/v1/tenants") {
        // 온보딩 (테넌트 초기 생성 및 2FA 정책, 기본 네트워크 설정)
        post("/onboarding") {
            val req = call.receive<OnboardingRequest>()
            val now = OffsetDateTime.now()

            val session = call.resolveUserSession()
            if (session == null && !AuthConfig.isDevMockAuth) {
                call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "온보딩을 진행하려면 먼저 로그인이 필요합니다."))
                return@post
            }

            if (!AuthConfig.isDevMockAuth && session != null) {
                val cachedGuilds = oauthService.getCachedUserGuilds(session.discordUserId)
                val guild = cachedGuilds.find { it.id == req.discordGuildId }
                if (guild == null || !guild.isOwner) {
                    call.respond(HttpStatusCode.Forbidden, mapOf("error" to "Discord 서버의 소유자(Owner)만 온보딩을 진행할 수 있습니다."))
                    return@post
                }
            }

            transaction {
                val exists = Tenants.selectAll().where { Tenants.id eq req.tenantId }.count() > 0
                if (!exists) {
                    Tenants.insert {
                        it[id] = req.tenantId
                        it[name] = req.name
                        it[discordGuildId] = req.discordGuildId
                        it[adminRoleId] = req.adminRoleId
                        it[createdAt] = now
                        it[updatedAt] = now
                    }
                } else {
                    Tenants.update({ Tenants.id eq req.tenantId }) {
                        it[name] = req.name
                        it[discordGuildId] = req.discordGuildId
                        it[adminRoleId] = req.adminRoleId
                        it[updatedAt] = now
                    }
                }

                // 기본 마인크래프트 네트워크 생성
                val netId = "net_" + req.tenantId
                val netExists = MinecraftNetworks.selectAll().where { MinecraftNetworks.id eq netId }.count() > 0
                if (!netExists) {
                    MinecraftNetworks.insert {
                        it[id] = netId
                        it[tenantId] = req.tenantId
                        it[name] = "${req.name} Default Network"
                        it[createdAt] = now
                        it[updatedAt] = now
                    }
                }

                // 2FA 정책 설정
                val policyExists = Admin2faPolicies.selectAll().where { Admin2faPolicies.tenantId eq req.tenantId }.count() > 0
                if (!policyExists) {
                    Admin2faPolicies.insert {
                        it[tenantId] = req.tenantId
                        it[policyMode] = req.policyMode
                        it[authChannelId] = req.authChannelId
                        it[timeoutSeconds] = 60
                        it[updatedAt] = now
                    }
                } else {
                    Admin2faPolicies.update({ Admin2faPolicies.tenantId eq req.tenantId }) {
                        it[policyMode] = req.policyMode
                        it[authChannelId] = req.authChannelId
                        it[updatedAt] = now
                    }
                }
            }

            logger.info("[Tenant] Configured onboarding for tenantId={}, guildId={}", req.tenantId, req.discordGuildId)
            call.respond(HttpStatusCode.OK, mapOf(
                "tenantId" to req.tenantId,
                "status" to "CONFIGURED"
            ))
        }

        // 테넌트 상세 조회
        withTenantAuth(oauthService, requireOwner = false) {
            get("/{id}") {
                val tenantId = call.parameters["id"] ?: return@get call.respond(HttpStatusCode.BadRequest)

                val detail = transaction {
                    val t = Tenants.selectAll().where { Tenants.id eq tenantId }.singleOrNull()
                        ?: return@transaction null

                    val policy = Admin2faPolicies.selectAll().where { Admin2faPolicies.tenantId eq tenantId }.singleOrNull()
                    val instances = MinecraftInstances.selectAll().where { MinecraftInstances.tenantId eq tenantId }
                        .map {
                            InstanceResponseDto(
                                id = it[MinecraftInstances.id],
                                name = it[MinecraftInstances.name],
                                instanceType = it[MinecraftInstances.instanceType],
                                status = it[MinecraftInstances.status],
                                lastHeartbeatAt = it[MinecraftInstances.lastHeartbeatAt]?.toString()
                            )
                        }

                    TenantDetailDto(
                        id = t[Tenants.id],
                        name = t[Tenants.name],
                        discordGuildId = t[Tenants.discordGuildId],
                        adminRoleId = t[Tenants.adminRoleId],
                        policyMode = policy?.get(Admin2faPolicies.policyMode) ?: "MONITOR",
                        authChannelId = policy?.get(Admin2faPolicies.authChannelId),
                        instances = instances
                    )
                }

                if (detail == null) {
                    call.respond(HttpStatusCode.NotFound, mapOf("error" to "Tenant not found"))
                } else {
                    call.respond(HttpStatusCode.OK, detail)
                }
            }
        }
    }

    // 마인크래프트 인스턴스 토큰 발급/등록 (서버장 전용)
    route("/api/v1/instances") {
        post("/token") {
            val req = call.receive<IssueInstanceTokenRequest>()

            if (!AuthConfig.isDevMockAuth) {
                val session = call.resolveUserSession()
                if (session == null) {
                    call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "인증 세션이 필요합니다."))
                    return@post
                }
                val tenantRow = transaction { Tenants.selectAll().where { Tenants.id eq req.tenantId }.singleOrNull() }
                if (tenantRow == null) {
                    call.respond(HttpStatusCode.NotFound, mapOf("error" to "존재하지 않는 테넌트입니다."))
                    return@post
                }
                val guildId = tenantRow[Tenants.discordGuildId]
                val cachedGuilds = oauthService.getCachedUserGuilds(session.discordUserId)
                val guild = cachedGuilds.find { it.id == guildId }
                if (guild == null || !guild.isOwner) {
                    call.respond(HttpStatusCode.Forbidden, mapOf("error" to "인스턴스 토큰 발급은 서버장(소유자) 권한이 필요합니다."))
                    return@post
                }
            }

            val plainToken = UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", "")
            val tokenHash = authService.hashToken(plainToken)
            val networkId = req.networkId ?: ("net_" + req.tenantId)
            val now = OffsetDateTime.now()

            transaction {
                val exists = MinecraftInstances.selectAll().where { MinecraftInstances.id eq req.instanceId }.count() > 0
                if (exists) {
                    MinecraftInstances.update({ MinecraftInstances.id eq req.instanceId }) {
                        it[tenantId] = req.tenantId
                        it[this.networkId] = networkId
                        it[name] = req.name
                        it[instanceType] = req.instanceType
                        it[this.tokenHash] = tokenHash
                        it[status] = "OFFLINE"
                        it[updatedAt] = now
                    }
                } else {
                    MinecraftInstances.insert {
                        it[id] = req.instanceId
                        it[tenantId] = req.tenantId
                        it[this.networkId] = networkId
                        it[name] = req.name
                        it[instanceType] = req.instanceType
                        it[this.tokenHash] = tokenHash
                        it[status] = "OFFLINE"
                        it[createdAt] = now
                        it[updatedAt] = now
                    }
                }
            }

            logger.info("[Tenant] Issued instance token for tenantId={}, instanceId={}", req.tenantId, req.instanceId)
            call.respond(HttpStatusCode.Created, mapOf(
                "instanceId" to req.instanceId,
                "tenantId" to req.tenantId,
                "token" to plainToken,
                "status" to "OFFLINE"
            ))
        }
    }
}
