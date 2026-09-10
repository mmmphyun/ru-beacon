package com.rubeacon.api.routes

import com.rubeacon.api.redis.RedisEventPublisher
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory
import redis.clients.jedis.JedisPooled
import java.time.LocalDate

private val log = LoggerFactory.getLogger("com.rubeacon.api.routes.EventSimulationRoute")

// KEYS[1]: quota:{tenantId}:{date}:players
// ARGV[1]: totalLimit
// ARGV[2]: playerUuid
// ARGV[3]: ttlSeconds
private val ADMISSION_LUA = """
    if redis.call('SISMEMBER', KEYS[1], ARGV[2]) == 1 then
        return 'ALREADY_RESERVED'
    end
    local current = redis.call('SCARD', KEYS[1])
    local limit = tonumber(ARGV[1])
    if current >= limit then
        return 'QUOTA_EXCEEDED'
    end
    redis.call('SADD', KEYS[1], ARGV[2])
    if redis.call('TTL', KEYS[1]) < 0 then
        redis.call('EXPIRE', KEYS[1], ARGV[3])
    end
    return 'OK'
""".trimIndent()

/**
 * k6 부하 테스트 및 비동기 이벤트 스트림 시뮬레이션 엔드포인트.
 * 2-Tier Admission Control의 초고속 Fast-Fail(<5ms) 및 Redis Streams 인그레스를 검증함.
 */
fun Route.eventSimulationRoutes(
    jedis: JedisPooled,
    redisPublisher: RedisEventPublisher,
    json: Json = Json { ignoreUnknownKeys = true }
) {
    route("/api/v1/events") {
        post("/simulate") {
            val rawBody = try {
                call.receiveText()
            } catch (e: Exception) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "MALFORMED_REQUEST_BODY"))
                return@post
            }

            if (rawBody.isBlank()) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "EMPTY_BODY"))
                return@post
            }

            val jsonElement = try {
                json.parseToJsonElement(rawBody).jsonObject
            } catch (e: Exception) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "INVALID_JSON"))
                return@post
            }

            val tenantId = call.request.headers["X-Tenant-Id"]
                ?: jsonElement["tenant_id"]?.jsonPrimitive?.contentOrNull
                ?: run {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "MISSING_TENANT_ID"))
                    return@post
                }

            val payload = jsonElement["payload"]?.jsonObject
            val action = payload?.get("action")?.jsonPrimitive?.contentOrNull ?: "GENERAL_EVENT"

            if (action == "ATTENDANCE_REWARD") {
                val playerUuid = payload?.get("player_uuid")?.jsonPrimitive?.contentOrNull
                    ?: java.util.UUID.randomUUID().toString()
                val totalLimit = payload?.get("total_limit")?.jsonPrimitive?.intOrNull ?: 10
                val rewardDate = LocalDate.now().toString()
                val quotaKey = "quota:$tenantId:$rewardDate:players"

                val result = try {
                    jedis.eval(ADMISSION_LUA, listOf(quotaKey), listOf(totalLimit.toString(), playerUuid, "172800")) as String
                } catch (e: Exception) {
                    log.error("Redis 2-Tier Admission Control 처리 실패: {}", e.message)
                    // Redis 장애 시 서비스 지속성을 위해 무중단 통과
                    "OK"
                }

                when (result) {
                    "ALREADY_RESERVED" -> {
                        call.respond(HttpStatusCode.Conflict, mapOf("status" to "ALREADY_RESERVED", "player_uuid" to playerUuid))
                        return@post
                    }
                    "QUOTA_EXCEEDED" -> {
                        call.respond(HttpStatusCode.TooManyRequests, mapOf("status" to "QUOTA_EXCEEDED", "limit" to totalLimit.toString()))
                        return@post
                    }
                    else -> {
                        try {
                            redisPublisher.publishEvent(tenantId, rawBody)
                        } catch (e: Exception) {
                            log.error("Redis Streams 발행 실패: {}", e.message)
                        }
                        call.respond(HttpStatusCode.OK, mapOf("status" to "COMMITTED", "player_uuid" to playerUuid))
                        return@post
                    }
                }
            }

            try {
                redisPublisher.publishEvent(tenantId, rawBody)
            } catch (e: Exception) {
                log.error("Redis Streams 발행 실패: {}", e.message)
            }
            call.respond(HttpStatusCode.Accepted, mapOf("status" to "ACCEPTED"))
        }
    }
}
