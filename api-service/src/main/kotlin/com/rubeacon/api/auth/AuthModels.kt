package com.rubeacon.api.auth

import kotlinx.serialization.Serializable

/**
 * 브라우저 HttpOnly 쿠키에 보관되는 관리자 세션 정보.
 */
@Serializable
data class UserSession(
    val discordUserId: String,
    val username: String,
    val avatar: String? = null,
    val createdAt: Long = System.currentTimeMillis()
)

/**
 * Discord API 및 Redis 캐시용 길드 메타데이터.
 */
@Serializable
data class DiscordGuildInfo(
    val id: String,
    val name: String,
    val isOwner: Boolean,
    val permissions: Long = 0L,
    val roles: List<String> = emptyList()
)

/**
 * 개발/로컬 테스트용 즉시 세션 발급 요청 DTO.
 */
@Serializable
data class DevMockLoginRequest(
    val discordUserId: String = "dev_admin_01",
    val username: String = "DevAdmin",
    val isOwner: Boolean = true,
    val roles: List<String> = emptyList()
)

/**
 * GET /api/v1/auth/me 응답용 DTO.
 */
@Serializable
data class TenantSummaryDto(
    val id: String,
    val name: String,
    val discordGuildId: String,
    val isOwner: Boolean,
    val hasAdminRole: Boolean
)

@Serializable
data class UserProfileDto(
    val discordUserId: String,
    val username: String,
    val avatar: String?,
    val isPlatformSuperAdmin: Boolean,
    val tenants: List<TenantSummaryDto>
)