package com.rubeacon.api.auth

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory
import redis.clients.jedis.JedisPooled
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration

/**
 * Discord OAuth2 연동 및 Redis 기반 길드/권한 캐싱 서비스.
 */
class DiscordOAuthService(
    private val jedis: JedisPooled,
    private val clientId: String = System.getenv("DISCORD_CLIENT_ID") ?: "",
    private val clientSecret: String = System.getenv("DISCORD_CLIENT_SECRET") ?: "",
    private val redirectUri: String = System.getenv("DISCORD_REDIRECT_URI") ?: "http://localhost:8080/api/v1/auth/discord/callback",
    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .build(),
    private val json: Json = Json { ignoreUnknownKeys = true }
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    companion object {
        private const val REDIS_CACHE_PREFIX = "rubeacon:discord:user:"
        private const val REDIS_CACHE_TTL_SECONDS = 900L // 15분
    }

    /**
     * Discord OAuth2 로그인 인가 URL 생성.
     */
    fun buildAuthorizationUrl(state: String): String {
        val encodedRedirect = URLEncoder.encode(redirectUri, StandardCharsets.UTF_8)
        val scope = URLEncoder.encode("identify guilds guilds.members.read", StandardCharsets.UTF_8)
        return "https://discord.com/api/oauth2/authorize?client_id=" + clientId +
                "&redirect_uri=" + encodedRedirect +
                "&response_type=code&scope=" + scope +
                "&state=" + state
    }

    /**
     * Discord 인가 코드를 Access Token으로 교환함.
     */
    fun exchangeCodeForToken(code: String): String? {
        val formBody = "client_id=" + clientId +
                "&client_secret=" + clientSecret +
                "&grant_type=authorization_code" +
                "&code=" + code +
                "&redirect_uri=" + URLEncoder.encode(redirectUri, StandardCharsets.UTF_8)

        val request = HttpRequest.newBuilder()
            .uri(URI.create("https://discord.com/api/oauth2/token"))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(formBody))
            .timeout(Duration.ofSeconds(10))
            .build()

        return try {
            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() in 200..299) {
                val element = json.parseToJsonElement(response.body()).jsonObject
                element["access_token"]?.jsonPrimitive?.content
            } else {
                logger.error("Discord 토큰 교환 실패: status={}, body={}", response.statusCode(), response.body())
                null
            }
        } catch (e: Exception) {
            logger.error("Discord 토큰 교환 네트워크 예외 발생", e)
            null
        }
    }

    /**
     * Discord 유저 프로필(@me) 조회.
     */
    fun fetchUserProfile(accessToken: String): UserSession? {
        val request = HttpRequest.newBuilder()
            .uri(URI.create("https://discord.com/api/users/@me"))
            .header("Authorization", "Bearer " + accessToken)
            .GET()
            .timeout(Duration.ofSeconds(5))
            .build()

        return try {
            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() in 200..299) {
                val obj = json.parseToJsonElement(response.body()).jsonObject
                val id = obj["id"]?.jsonPrimitive?.content ?: return null
                val username = obj["username"]?.jsonPrimitive?.content ?: "Unknown"
                val avatar = obj["avatar"]?.jsonPrimitive?.content
                UserSession(discordUserId = id, username = username, avatar = avatar)
            } else {
                null
            }
        } catch (e: Exception) {
            logger.error("Discord 프로필 조회 실패", e)
            null
        }
    }

    /**
     * 사용자의 길드 목록 및 역할 정보를 조회함 (Redis 캐싱 15분 적용).
     */
    fun getCachedUserGuilds(userId: String, accessToken: String? = null): List<DiscordGuildInfo> {
        val cacheKey = REDIS_CACHE_PREFIX + userId + ":guilds"
        try {
            val cachedJson = jedis.get(cacheKey)
            if (!cachedJson.isNullOrBlank()) {
                return json.decodeFromString(cachedJson)
            }
        } catch (e: Exception) {
            logger.warn("Redis 길드 캐시 조회 실패, API 직접 조회 시도: {}", e.message)
        }

        if (accessToken.isNullOrBlank()) {
            return emptyList()
        }

        val guilds = fetchGuildsFromDiscord(accessToken)
        if (guilds.isNotEmpty()) {
            try {
                jedis.setex(cacheKey, REDIS_CACHE_TTL_SECONDS, json.encodeToString(guilds))
            } catch (e: Exception) {
                logger.warn("Redis 길드 캐시 적재 실패: {}", e.message)
            }
        }
        return guilds
    }

    /**
     * Redis 길드 캐시 강제 무효화.
     */
    fun invalidateGuildsCache(userId: String) {
        val cacheKey = REDIS_CACHE_PREFIX + userId + ":guilds"
        try {
            jedis.del(cacheKey)
            logger.info("Redis 길드 캐시 무효화 완료: userId={}", userId)
        } catch (e: Exception) {
            logger.warn("Redis 길드 캐시 삭제 실패: {}", e.message)
        }
    }

    /**
     * Discord API로부터 사용자가 소속된 길드 목록 조회.
     */
    private fun fetchGuildsFromDiscord(accessToken: String): List<DiscordGuildInfo> {
        val request = HttpRequest.newBuilder()
            .uri(URI.create("https://discord.com/api/users/@me/guilds"))
            .header("Authorization", "Bearer " + accessToken)
            .GET()
            .timeout(Duration.ofSeconds(8))
            .build()

        return try {
            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() in 200..299) {
                val array = json.parseToJsonElement(response.body()).jsonArray
                array.map { element ->
                    val obj = element.jsonObject
                    val id = obj["id"]?.jsonPrimitive?.content ?: ""
                    val name = obj["name"]?.jsonPrimitive?.content ?: ""
                    val isOwner = obj["owner"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false
                    val perms = obj["permissions"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L
                    DiscordGuildInfo(
                        id = id,
                        name = name,
                        isOwner = isOwner,
                        permissions = perms,
                        roles = emptyList()
                    )
                }
            } else {
                logger.error("Discord 길드 목록 조회 실패: status={}, body={}", response.statusCode(), response.body())
                emptyList()
            }
        } catch (e: Exception) {
            logger.error("Discord 길드 목록 요청 예외 발생", e)
            emptyList()
        }
    }

    /**
     * 특정 길드에서 사용자가 보유한 역할(Role IDs) 조회.
     */
    fun fetchMemberRolesForGuild(guildId: String, accessToken: String): List<String> {
        val request = HttpRequest.newBuilder()
            .uri(URI.create("https://discord.com/api/users/@me/guilds/" + guildId + "/member"))
            .header("Authorization", "Bearer " + accessToken)
            .GET()
            .timeout(Duration.ofSeconds(5))
            .build()

        return try {
            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() in 200..299) {
                val obj = json.parseToJsonElement(response.body()).jsonObject
                val rolesArray = obj["roles"]?.jsonArray ?: return emptyList()
                rolesArray.map { it.jsonPrimitive.content }
            } else {
                emptyList()
            }
        } catch (e: Exception) {
            logger.warn("Discord 길드 멤버 역할 조회 실패: guildId={}, error={}", guildId, e.message)
            emptyList()
        }
    }
}
