package com.rubeacon.api.routes

import com.rubeacon.api.auth.AuthConfig
import com.rubeacon.api.auth.DevMockLoginRequest
import com.rubeacon.api.auth.DiscordOAuthService
import com.rubeacon.api.auth.TenantSummaryDto
import com.rubeacon.api.auth.UserProfileDto
import com.rubeacon.api.auth.UserSession
import com.rubeacon.api.auth.resolveUserSession
import com.rubeacon.api.db.Tenants
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondRedirect
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.sessions.clear
import io.ktor.server.sessions.sessions
import io.ktor.server.sessions.set
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory
import java.util.UUID

private val logger = LoggerFactory.getLogger("AuthRoute")

fun Route.authRoutes(oauthService: DiscordOAuthService) {
    route("/api/v1/auth") {
        // Discord OAuth2 로그인 URL 리다이렉트
        get("/discord/login") {
            val state = UUID.randomUUID().toString()
            val authUrl = oauthService.buildAuthorizationUrl(state)
            call.respondRedirect(authUrl)
        }

        // Discord OAuth2 콜백
        get("/discord/callback") {
            val code = call.request.queryParameters["code"]
            if (code.isNullOrBlank()) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "인증 코드가 누락되었습니다."))
                return@get
            }

            val token = oauthService.exchangeCodeForToken(code)
            if (token == null) {
                call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Discord 토큰 교환에 실패하였습니다."))
                return@get
            }

            val profile = oauthService.fetchUserProfile(token)
            if (profile == null) {
                call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Discord 프로필을 가져올 수 없습니다."))
                return@get
            }

            // 길드 캐시 예열
            oauthService.getCachedUserGuilds(profile.discordUserId, token)

            // 세션 쿠키 발급
            call.sessions.set(profile)
            logger.info("관리자 세션 발급 완료: userId={}, username={}", profile.discordUserId, profile.username)

            val returnUrl = call.request.queryParameters["state"] ?: "/dashboard"
            call.respond(HttpStatusCode.OK, mapOf(
                "status" to "SUCCESS",
                "userId" to profile.discordUserId,
                "username" to profile.username
            ))
        }

        // 로그아웃
        post("/logout") {
            call.sessions.clear<UserSession>()
            call.respond(HttpStatusCode.OK, mapOf("status" to "LOGGED_OUT"))
        }

        // 내 프로필 및 접근 가능한 테넌트 목록 조회
        get("/me") {
            val session = call.resolveUserSession()
            if (session == null) {
                call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "인증되지 않은 사용자입니다."))
                return@get
            }

            val isSuperAdmin = AuthConfig.superAdminUserIds.contains(session.discordUserId) ||
                    (AuthConfig.isDevMockAuth && call.request.headers["X-Dev-Mock-Super-Admin"] == "true")

            val cachedGuilds = oauthService.getCachedUserGuilds(session.discordUserId)

            val tenants = transaction {
                Tenants.selectAll().mapNotNull { row ->
                    val tenantId = row[Tenants.id]
                    val tenantName = row[Tenants.name]
                    val guildId = row[Tenants.discordGuildId]
                    val adminRoleId = row[Tenants.adminRoleId]

                    val guild = cachedGuilds.find { it.id == guildId }
                    val isOwner = guild?.isOwner == true
                    val hasAdminRole = adminRoleId != null && guild?.roles?.contains(adminRoleId) == true

                    if (isOwner || hasAdminRole || isSuperAdmin || AuthConfig.isDevMockAuth) {
                        TenantSummaryDto(
                            id = tenantId,
                            name = tenantName,
                            discordGuildId = guildId,
                            isOwner = isOwner,
                            hasAdminRole = hasAdminRole
                        )
                    } else {
                        null
                    }
                }
            }

            call.respond(HttpStatusCode.OK, UserProfileDto(
                discordUserId = session.discordUserId,
                username = session.username,
                avatar = session.avatar,
                isPlatformSuperAdmin = isSuperAdmin,
                tenants = tenants
            ))
        }

        // Redis 길드 캐시 강제 무효화 및 새로고침
        post("/refresh-guilds") {
            val session = call.resolveUserSession()
            if (session == null) {
                call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "인증이 필요합니다."))
                return@post
            }

            oauthService.invalidateGuildsCache(session.discordUserId)
            call.respond(HttpStatusCode.OK, mapOf("status" to "REFRESHED"))
        }

        // 로컬/개발 테스트용 목 세션 발급 가드 (DEV_MOCK_AUTH=true 전용)
        post("/dev-mock-login") {
            if (!AuthConfig.isDevMockAuth) {
                call.respond(HttpStatusCode.Forbidden, mapOf("error" to "DEV_MOCK_AUTH가 비활성화되어 있습니다."))
                return@post
            }

            val req = try {
                call.receive<DevMockLoginRequest>()
            } catch (_: Exception) {
                DevMockLoginRequest()
            }

            val mockSession = UserSession(
                discordUserId = req.discordUserId,
                username = req.username
            )
            call.sessions.set(mockSession)
            logger.info("[DevMock] 목 세션 발급 완료: userId={}", req.discordUserId)
            call.respond(HttpStatusCode.OK, mapOf(
                "status" to "MOCK_SESSION_ESTABLISHED",
                "userId" to req.discordUserId,
                "username" to req.username
            ))
        }
    }
}