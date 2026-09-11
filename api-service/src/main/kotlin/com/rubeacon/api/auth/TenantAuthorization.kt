package com.rubeacon.api.auth

import com.rubeacon.api.db.Tenants
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.createRouteScopedPlugin
import io.ktor.server.application.install
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.RouteSelector
import io.ktor.server.routing.RouteSelectorEvaluation
import io.ktor.server.routing.RoutingResolveContext
import io.ktor.server.sessions.get
import io.ktor.server.sessions.sessions
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("TenantAuthorization")

/**
 * 개발/로컬 모드 및 슈퍼 어드민 환경 설정.
 */
object AuthConfig {
    val isDevMockAuth: Boolean
        get() = ((System.getenv("DEV_MOCK_AUTH")?.toBooleanStrictOrNull() == true) ||
                (System.getProperty("DEV_MOCK_AUTH")?.toBooleanStrictOrNull() == true)) &&
                (!System.getenv("ENVIRONMENT").equals("production", ignoreCase = true))

    val superAdminUserIds: Set<String>
        get() = (System.getenv("PLATFORM_SUPER_ADMIN_IDS") ?: System.getProperty("PLATFORM_SUPER_ADMIN_IDS") ?: "")
            .split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet()

    val platformAdminSecret: String
        get() = System.getenv("PLATFORM_ADMIN_SECRET") ?: System.getProperty("PLATFORM_ADMIN_SECRET") ?: "dev-secret-platform-key"
}

/**
 * 호출 컨텍스트에서 UserSession을 안전하게 획득함 (DEV_MOCK_AUTH 바이패스 지원).
 */
fun ApplicationCall.resolveUserSession(): UserSession? {
    val session = sessions.get<UserSession>()
    if (session != null) return session

    if (AuthConfig.isDevMockAuth) {
        val mockUserId = request.headers["X-Dev-Mock-User-Id"] ?: "dev_admin_01"
        val mockUsername = request.headers["X-Dev-Mock-Username"] ?: "DevAdmin"
        return UserSession(
            discordUserId = mockUserId,
            username = mockUsername
        )
    }

    return null
}

/**
 * 요청에서 tenantId를 추출함 (파라미터, 쿼리스트링, 헤더 순).
 */
fun ApplicationCall.extractTenantId(): String? {
    return parameters["tenantId"]
        ?: parameters["id"]
        ?: request.queryParameters["tenantId"]
        ?: request.headers["X-Tenant-Id"]
}

class TenantAuthConfiguration {
    var requireOwner: Boolean = false
    var oauthService: DiscordOAuthService? = null
}

/**
 * 멀티테넌트 RBAC 인가 플러그인.
 * 세션 유저가 대상 테넌트의 소유자(Owner) 또는 위임 관리자 역할(Admin Role)을 보유했는지 검증함.
 */
val TenantAuthPlugin = createRouteScopedPlugin(
    name = "TenantAuthPlugin",
    createConfiguration = ::TenantAuthConfiguration
) {
    val requireOwner = pluginConfig.requireOwner
    val oauthService = pluginConfig.oauthService

    onCall { call ->
        val session = call.resolveUserSession()
        if (session == null) {
            call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "인증 세션이 필요합니다."))
            return@onCall
        }

        val tenantId = call.extractTenantId()
        if (tenantId.isNullOrBlank()) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "tenantId가 지정되지 않았습니다."))
            return@onCall
        }

        // DEV_MOCK_AUTH 모드 바이패스 처리
        if (AuthConfig.isDevMockAuth) {
            val mockRole = call.request.headers["X-Dev-Mock-Role"] ?: "OWNER"
            if (requireOwner && mockRole != "OWNER") {
                call.respond(HttpStatusCode.Forbidden, mapOf("error" to "서버장(소유자) 권한이 필요합니다."))
                return@onCall
            }
            return@onCall
        }

        // 테넌트 정보 조회
        val tenantRow = transaction {
            Tenants.selectAll().where { Tenants.id eq tenantId }.singleOrNull()
        }
        if (tenantRow == null) {
            call.respond(HttpStatusCode.NotFound, mapOf("error" to "존재하지 않는 테넌트입니다."))
            return@onCall
        }

        val guildId = tenantRow[Tenants.discordGuildId]
        val adminRoleId = tenantRow[Tenants.adminRoleId]

        val cachedGuilds = oauthService?.getCachedUserGuilds(session.discordUserId) ?: emptyList()
        val guild = cachedGuilds.find { it.id == guildId }

        if (guild == null) {
            call.respond(HttpStatusCode.Forbidden, mapOf("error" to "해당 서버에 소속되어 있지 않습니다."))
            return@onCall
        }

        val isOwner = guild.isOwner
        val hasAdminRole = adminRoleId != null && guild.roles.contains(adminRoleId)

        if (requireOwner && !isOwner) {
            call.respond(HttpStatusCode.Forbidden, mapOf("error" to "해당 작업은 서버장(소유자) 권한이 필요합니다."))
            return@onCall
        }

        if (!isOwner && !hasAdminRole) {
            call.respond(HttpStatusCode.Forbidden, mapOf("error" to "해당 테넌트의 관리자 권한이 없습니다."))
            return@onCall
        }
    }
}

/**
 * 플랫폼 운영자 이중 검증 플러그인.
 * 1차: Discord User ID 화이트리스트 검증
 * 2차: X-Platform-Admin-Secret 헤더 비밀키 검증
 */
val PlatformSuperAdminPlugin = createRouteScopedPlugin(name = "PlatformSuperAdminPlugin") {
    onCall { call ->
        val session = call.resolveUserSession()
        if (session == null) {
            call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "인증 세션이 필요합니다."))
            return@onCall
        }

        // 1차 검증: 플랫폼 슈퍼 관리자 ID 화이트리스트 확인
        val isSuperAdminId = AuthConfig.superAdminUserIds.contains(session.discordUserId) ||
                (AuthConfig.isDevMockAuth && call.request.headers["X-Dev-Mock-Super-Admin"] == "true")

        if (!isSuperAdminId) {
            logger.warn("[Security] 비인가 플랫폼 운영자 접근 시도: userId={}", session.discordUserId)
            call.respond(HttpStatusCode.Forbidden, mapOf("error" to "플랫폼 운영자 권한이 없습니다."))
            return@onCall
        }

        // 2차 검증: 비밀키 헤더 검증
        val secretHeader = call.request.headers["X-Platform-Admin-Secret"]
        if (secretHeader.isNullOrBlank() || secretHeader != AuthConfig.platformAdminSecret) {
            logger.warn("[Security] 플랫폼 운영자 2차 시크릿 검증 실패: userId={}", session.discordUserId)
            call.respond(HttpStatusCode.Forbidden, mapOf("error" to "플랫폼 2차 인증 보안 키가 일치하지 않습니다."))
            return@onCall
        }
    }
}

/**
 * Route 확장 함수: 테넌트 RBAC 가드 적용.
 */
fun Route.withTenantAuth(
    oauthService: DiscordOAuthService,
    requireOwner: Boolean = false,
    build: Route.() -> Unit
): Route {
    val authenticatedRoute = createChild(object : RouteSelector() {
        override fun evaluate(context: RoutingResolveContext, segmentIndex: Int): RouteSelectorEvaluation =
            RouteSelectorEvaluation.Constant
    })
    authenticatedRoute.install(TenantAuthPlugin) {
        this.requireOwner = requireOwner
        this.oauthService = oauthService
    }
    authenticatedRoute.build()
    return authenticatedRoute
}

/**
 * Route 확장 함수: 플랫폼 운영자 이중 검증 가드 적용.
 */
fun Route.withPlatformSuperAdminAuth(build: Route.() -> Unit): Route {
    val authenticatedRoute = createChild(object : RouteSelector() {
        override fun evaluate(context: RoutingResolveContext, segmentIndex: Int): RouteSelectorEvaluation =
            RouteSelectorEvaluation.Constant
    })
    authenticatedRoute.install(PlatformSuperAdminPlugin)
    authenticatedRoute.build()
    return authenticatedRoute
}