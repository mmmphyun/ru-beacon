package com.rubeacon.bot

/**
 * Discord 봇 및 Redis 연결 설정.
 *
 * @property token Discord Bot Token (환경변수 DISCORD_BOT_TOKEN 우선)
 * @property redisHost Redis 서버 호스트 (환경변수 REDIS_HOST 우선, 기본값 localhost)
 * @property redisPort Redis 서버 포트 (환경변수 REDIS_PORT 우선, 기본값 6379)
 * @property defaultTenantId 길드가 없는 DM 등의 환경에서 사용할 폴백 테넌트 ID
 */
data class DiscordBotConfig(
    val token: String = System.getenv("DISCORD_BOT_TOKEN") ?: "",
    val redisHost: String = System.getenv("REDIS_HOST") ?: "localhost",
    val redisPort: Int = System.getenv("REDIS_PORT")?.toIntOrNull() ?: 6379,
    val defaultTenantId: String = System.getenv("DEFAULT_TENANT_ID") ?: "default-tenant"
)
