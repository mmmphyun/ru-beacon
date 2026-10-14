package com.rubeacon.api

import com.rubeacon.api.service.TokenBucketRateLimiter
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import redis.clients.jedis.JedisPooled
import java.util.UUID

class TokenBucketRateLimiterTest : BaseIntegrationTest() {

    private lateinit var rateLimiter: TokenBucketRateLimiter

    @BeforeEach
    fun setUp() {
        rateLimiter = TokenBucketRateLimiter(jedis)
    }

    @Test
    fun `초기 용량 이내 요청은 허용되고 한도 초과 시 429 차단된다`() {
        val key = "ratelimit:test:${UUID.randomUUID()}"
        val capacity = 3L
        val refillRate = 1.0 // 초당 1개 충전
        val baseTime = 1000000L

        // 1~3번째 요청: 모두 허용
        val r1 = rateLimiter.check(key, capacity, refillRate, cost = 1, nowMs = baseTime)
        assertTrue(r1.allowed)
        assertEquals(2L, r1.remainingTokens)

        val r2 = rateLimiter.check(key, capacity, refillRate, cost = 1, nowMs = baseTime)
        assertTrue(r2.allowed)
        assertEquals(1L, r2.remainingTokens)

        val r3 = rateLimiter.check(key, capacity, refillRate, cost = 1, nowMs = baseTime)
        assertTrue(r3.allowed)
        assertEquals(0L, r3.remainingTokens)

        // 4번째 요청: 한도 초과 차단
        val r4 = rateLimiter.check(key, capacity, refillRate, cost = 1, nowMs = baseTime)
        assertFalse(r4.allowed)
        assertEquals(0L, r4.remainingTokens)
        assertEquals(1L, r4.retryAfterSeconds)
    }

    @Test
    fun `시간 경과 시 토큰이 점진적으로 보충된다`() {
        val key = "ratelimit:test:${UUID.randomUUID()}"
        val capacity = 5L
        val refillRate = 2.0 // 초당 2개 충전
        var currentTime = 2000000L

        // 5개 토큰 전량 소진
        repeat(5) {
            val res = rateLimiter.check(key, capacity, refillRate, cost = 1, nowMs = currentTime)
            assertTrue(res.allowed)
        }

        // 즉시 요청 시 차단
        val blocked = rateLimiter.check(key, capacity, refillRate, cost = 1, nowMs = currentTime)
        assertFalse(blocked.allowed)

        // 1초 경과 (2개 충전됨)
        currentTime += 1000L

        val refilled1 = rateLimiter.check(key, capacity, refillRate, cost = 1, nowMs = currentTime)
        assertTrue(refilled1.allowed)
        assertEquals(1L, refilled1.remainingTokens)

        val refilled2 = rateLimiter.check(key, capacity, refillRate, cost = 1, nowMs = currentTime)
        assertTrue(refilled2.allowed)
        assertEquals(0L, refilled2.remainingTokens)

        // 추가 소진 시 차단
        val blockedAgain = rateLimiter.check(key, capacity, refillRate, cost = 1, nowMs = currentTime)
        assertFalse(blockedAgain.allowed)
    }

    @Test
    fun `Redis 장애 시 Fail-Open으로 안전하게 통과한다`() {
        val deadJedis = JedisPooled("127.0.0.1", 59999)
        val fallbackLimiter = TokenBucketRateLimiter(deadJedis)

        val res = fallbackLimiter.check("ratelimit:failopen:test", capacity = 10, refillRatePerSec = 5.0)
        assertTrue(res.allowed)
        assertEquals(10L, res.remainingTokens)
        assertEquals(0L, res.retryAfterSeconds)
    }
}
