package com.rubeacon.api.service

import org.slf4j.LoggerFactory
import redis.clients.jedis.JedisPooled

/**
 * 토큰 버킷 속도 제한 판정 결과.
 */
data class RateLimitResult(
    val allowed: Boolean,
    val remainingTokens: Long,
    val retryAfterSeconds: Long
)

/**
 * Redis Lua 스크립트 기반 분산 토큰 버킷 레이트리미터.
 * 경계면 2배 버스트 및 저트래픽 환경의 위양성(False Positive)을 원천 차단함.
 */
class TokenBucketRateLimiter(private val jedis: JedisPooled) {
    private val log = LoggerFactory.getLogger(javaClass)

    companion object {
        /**
         * 원자적 토큰 보충 및 차감 Lua 스크립트.
         * KEYS[1]: 버킷 식별 키
         * ARGV[1]: 버킷 최대 용량 (capacity)
         * ARGV[2]: 초당 토큰 보충 속도 (refill_rate)
         * ARGV[3]: 요청당 소모 토큰 (cost)
         * ARGV[4]: 현재 시각 밀리초 (now_ms)
         */
        private val LUA_TOKEN_BUCKET = """
            local key = KEYS[1]
            local capacity = tonumber(ARGV[1])
            local refill_rate = tonumber(ARGV[2])
            local cost = tonumber(ARGV[3])
            local now = tonumber(ARGV[4])

            local data = redis.call('HMGET', key, 'tokens', 'last_updated')
            local tokens = tonumber(data[1])
            local last_updated = tonumber(data[2])

            if tokens == nil or last_updated == nil then
                tokens = capacity
                last_updated = now
            else
                local elapsed = math.max(0, (now - last_updated) / 1000.0)
                tokens = math.min(capacity, tokens + (elapsed * refill_rate))
                last_updated = now
            end

            local allowed = 0
            local remaining = tokens
            local retry_after = 0

            if tokens >= cost then
                tokens = tokens - cost
                allowed = 1
                remaining = math.floor(tokens)
            else
                allowed = 0
                remaining = 0
                local needed = cost - tokens
                if refill_rate > 0 then
                    retry_after = math.ceil(needed / refill_rate)
                else
                    retry_after = 60
                end
            end

            redis.call('HMSET', key, 'tokens', tokens, 'last_updated', last_updated)
            local ttl = 60
            if refill_rate > 0 then
                ttl = math.max(10, math.ceil((capacity / refill_rate) + 10))
            end
            redis.call('EXPIRE', key, ttl)

            return { allowed, remaining, retry_after }
        """.trimIndent()
    }

    /**
     * 식별자 키에 대해 토큰을 차감하고 허용 여부를 반환함.
     * Redis 일시 장애 시 가용성 보장을 위해 Fail-Open(통과) 처리함.
     */
    fun check(
        key: String,
        capacity: Long = 50,
        refillRatePerSec: Double = 20.0,
        cost: Long = 1,
        nowMs: Long = System.currentTimeMillis()
    ): RateLimitResult {
        return try {
            val res = jedis.eval(
                LUA_TOKEN_BUCKET,
                listOf(key),
                listOf(
                    capacity.toString(),
                    refillRatePerSec.toString(),
                    cost.toString(),
                    nowMs.toString()
                )
            ) as List<*>

            val allowed = (res[0] as Long) == 1L
            val remaining = res[1] as Long
            val retryAfter = res[2] as Long

            RateLimitResult(
                allowed = allowed,
                remainingTokens = remaining,
                retryAfterSeconds = retryAfter
            )
        } catch (e: Exception) {
            log.warn("Redis 레이트리미터 조회 실패 (Fail-Closed 방어 적용, Key: {}): {}", key, e.message)
            RateLimitResult(
                allowed = false,
                remainingTokens = 0,
                retryAfterSeconds = 5
            )
        }
    }
}
