package com.rubeacon.worker

import com.rubeacon.common.event.EventEntity
import com.rubeacon.common.event.EventEnvelope
import com.rubeacon.worker.db.AttendanceQuotas
import com.rubeacon.worker.db.RewardReservations
import com.rubeacon.worker.engine.AttendanceReservationExecutor
import com.rubeacon.worker.engine.NodeResult
import com.rubeacon.worker.engine.WorkflowContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.insertIgnore
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * 2-Tier 분산 동시성 제어 (Redis Admission Control + PostgreSQL 영속화 & 보상 트랜잭션) 통합 테스트.
 */
class AttendanceConcurrencyIntegrationTest : BaseWorkerIntegrationTest() {

    private val tenantId = "tenant_concurrency_test"
    private val rewardDate = LocalDate.now()

    @BeforeEach
    fun setUp() {
        cleanupData()
        jedis.flushAll()
        ensureTenant(tenantId)
    }

    private fun createTestContext(playerUuid: UUID, correlationId: String): WorkflowContext {
        val event = EventEnvelope(
            eventId = "evt_${UUID.randomUUID().toString().take(12)}",
            eventType = "minecraft.player.attendance",
            source = "minecraft",
            sourceInstanceId = "survival-01",
            tenantId = tenantId,
            occurredAt = "2026-09-10T12:00:00Z",
            receivedAt = "2026-09-10T12:00:01Z",
            correlationId = correlationId,
            idempotencyKey = "idemp_${UUID.randomUUID().toString().take(12)}",
            actor = EventEntity("minecraft_player", playerUuid.toString())
        )
        return WorkflowContext(
            tenantId = tenantId,
            correlationId = correlationId,
            initialEvent = event,
            variables = mapOf("Minecraft_UUID" to playerUuid.toString())
        )
    }

    @Test
    fun `동시성 경합 검증 - N개 정원 동시 요청 시 정확히 N개만 1차 통과 및 DB 저장 성공`() = runBlocking {
        val executor = AttendanceReservationExecutor(jedis)
        val totalLimit = 5
        val concurrentRequests = 30

        val successCount = AtomicInteger(0)
        val quotaExceededCount = AtomicInteger(0)

        val deferreds = (1..concurrentRequests).map {
            async(Dispatchers.IO) {
                val playerUuid = UUID.randomUUID()
                val context = createTestContext(playerUuid, "corr_race_${UUID.randomUUID().toString().take(8)}")
                val inputs = mapOf(
                    "action" to JsonPrimitive("RESERVE"),
                    "total_limit" to JsonPrimitive(totalLimit.toString())
                )

                val result = executor.execute(context, inputs)
                if (result is NodeResult.Success) {
                    successCount.incrementAndGet()
                } else if (result is NodeResult.Failure && result.errorCode == "QUOTA_EXCEEDED") {
                    quotaExceededCount.incrementAndGet()
                }
                Unit
            }
        }

        deferreds.awaitAll()

        // 1. 정확히 N개만 성공
        assertEquals(totalLimit, successCount.get(), "성공 건수는 정원 N과 정확히 일치해야 함")
        assertEquals(concurrentRequests - totalLimit, quotaExceededCount.get(), "초과 건수는 QUOTA_EXCEEDED여야 함")

        // 2. DB 영속화 검증
        transaction(database) {
            val dbReservations = RewardReservations.selectAll().where {
                (RewardReservations.tenantId eq tenantId) and (RewardReservations.rewardDate eq rewardDate)
            }.count()
            assertEquals(totalLimit.toLong(), dbReservations, "DB에 최종 저장된 예약 수는 정확히 N이어야 함")
        }

        // 3. Redis 상태 검증
        val redisCount = jedis.get("quota:$tenantId:$rewardDate:count")?.toInt() ?: 0
        val redisPlayersSize = jedis.scard("quota:$tenantId:$rewardDate:players")
        assertEquals(totalLimit, redisCount, "Redis 선점 카운트는 정확히 N이어야 함")
        assertEquals(totalLimit.toLong(), redisPlayersSize, "Redis 플레이어 집합 크기는 정확히 N이어야 함")
    }

    @Test
    fun `Fast-Fail 검증 - (N+1)번째 초과 요청 및 중복 요청은 Redis 레벨에서 즉시 차단되어 DB 쿼리가 발생하지 않음`() = runBlocking {
        val executor = AttendanceReservationExecutor(jedis)
        val totalLimit = 2

        val player1 = UUID.randomUUID()
        val player2 = UUID.randomUUID()
        val player3 = UUID.randomUUID()

        val inputs = mapOf(
            "action" to JsonPrimitive("RESERVE"),
            "total_limit" to JsonPrimitive(totalLimit.toString())
        )

        val res1 = executor.execute(createTestContext(player1, "corr_p1"), inputs)
        val res2 = executor.execute(createTestContext(player2, "corr_p2"), inputs)
        assertTrue(res1 is NodeResult.Success)
        assertTrue(res2 is NodeResult.Success)

        // DB에 2건 저장 확인
        var initialDbCount = 0L
        transaction(database) {
            initialDbCount = RewardReservations.selectAll().count()
        }
        assertEquals(2L, initialDbCount)

        // N+1 번째 초과 요청 -> Redis 레벨 Fast-Fail (QUOTA_EXCEEDED)
        val res3 = executor.execute(createTestContext(player3, "corr_p3"), inputs)
        assertTrue(res3 is NodeResult.Failure)
        assertEquals("QUOTA_EXCEEDED", res3.errorCode)

        // 중복 요청 -> Redis 레벨 Fast-Fail (ALREADY_RESERVED)
        val resDup = executor.execute(createTestContext(player1, "corr_p1_dup"), inputs)
        assertTrue(resDup is NodeResult.Failure)
        assertEquals("ALREADY_RESERVED", resDup.errorCode)

        // DB 레코드 수가 전혀 증가하지 않음 검증 (DB 호출 횟수 0회 원칙)
        transaction(database) {
            val currentDbCount = RewardReservations.selectAll().count()
            assertEquals(initialDbCount, currentDbCount, "초과 및 중복 요청 시 DB 레코드가 변동 없어야 함")
        }
    }

    @Test
    fun `중복 차단 검증 - 동일 플레이어가 당일 재요청 시 1차 관문에서 즉시 차단됨`() = runBlocking {
        val executor = AttendanceReservationExecutor(jedis)
        val playerUuid = UUID.randomUUID()

        val context = createTestContext(playerUuid, "corr_dup_test")
        val inputs = mapOf(
            "action" to JsonPrimitive("RESERVE"),
            "total_limit" to JsonPrimitive("10")
        )

        // 1차 성공
        val firstResult = executor.execute(context, inputs)
        assertTrue(firstResult is NodeResult.Success)

        // 2차 중복 시도 -> 차단
        val secondResult = executor.execute(context, inputs)
        assertTrue(secondResult is NodeResult.Failure)
        assertEquals("ALREADY_RESERVED", secondResult.errorCode)

        // Redis & DB 정합성: 1건만 유지
        val redisCount = jedis.get("quota:$tenantId:$rewardDate:count")?.toInt() ?: 0
        assertEquals(1, redisCount)

        transaction(database) {
            val count = RewardReservations.selectAll().where {
                RewardReservations.playerUuid eq playerUuid
            }.count()
            assertEquals(1L, count)
        }
    }

    @Test
    fun `보상 트랜잭션 검증 - DB 저장 실패 시 Redis 선점 카운트가 정상 원복됨`() = runBlocking {
        val executor = AttendanceReservationExecutor(jedis)
        val playerUuid = UUID.randomUUID()

        val inputs = mapOf(
            "action" to JsonPrimitive("RESERVE"),
            "total_limit" to JsonPrimitive("10")
        )

        // 1. 정상 1차 예약 -> DB 및 Redis에 정상 저장
        val firstRes = executor.execute(createTestContext(playerUuid, "corr_pre_existing"), inputs)
        assertTrue(firstRes is NodeResult.Success)

        // 2. Redis 상태만 강제로 초기화 (DB에는 데이터가 남아있으나 Redis에는 없는 상태 시뮬레이션)
        jedis.flushAll()

        // 3. 동일 플레이어가 다시 예약 시도:
        //    - Redis 1차 관문 통과 (Redis 키가 없으므로 카운트 증가 및 Set 추가)
        //    - PostgreSQL 2차 관문에서 uq_reward_reservation_daily 유니크 제약조건 위반으로 Exception 발생
        //    - catch 블록에서 Redis 보상 롤백 수행
        assertFailsWith<Exception> {
            executor.execute(createTestContext(playerUuid, "corr_compensation_test"), inputs)
        }

        // Redis 보상 롤백 확인: 카운터 및 Set에서 완전히 원복되어 카운트는 0이어야 함
        val countKey = "quota:$tenantId:$rewardDate:count"
        val playersKey = "quota:$tenantId:$rewardDate:players"
        val countVal = jedis.get(countKey)?.toInt() ?: 0
        val isMember = jedis.sismember(playersKey, playerUuid.toString())

        assertEquals(0, countVal, "DB 실패 시 Redis 카운트는 0으로 롤백되어야 함")
        assertEquals(false, isMember, "DB 실패 시 Redis 플레이어 Set에서 제거되어야 함")
    }

    @Test
    fun `Fallback 검증 - jedis가 null인 상태에서도 기존 DB 조건부 UPDATE로 안전하게 동작`() = runBlocking {
        val executor = AttendanceReservationExecutor(jedis = null)
        val totalLimit = 2

        val p1 = UUID.randomUUID()
        val p2 = UUID.randomUUID()
        val p3 = UUID.randomUUID()

        val inputs = mapOf(
            "action" to JsonPrimitive("RESERVE"),
            "total_limit" to JsonPrimitive(totalLimit.toString())
        )

        val r1 = executor.execute(createTestContext(p1, "corr_fb_1"), inputs)
        val r2 = executor.execute(createTestContext(p2, "corr_fb_2"), inputs)
        val r3 = executor.execute(createTestContext(p3, "corr_fb_3"), inputs)
        val rDup = executor.execute(createTestContext(p1, "corr_fb_dup"), inputs)

        assertTrue(r1 is NodeResult.Success)
        assertTrue(r2 is NodeResult.Success)
        assertTrue(r3 is NodeResult.Failure)
        assertEquals("QUOTA_EXCEEDED", r3.errorCode)
        assertTrue(rDup is NodeResult.Failure)
        assertEquals("ALREADY_RESERVED", rDup.errorCode)

        transaction(database) {
            val totalSaved = RewardReservations.selectAll().count()
            assertEquals(2L, totalSaved, "DB에 정상적으로 2명만 예약되어야 함")
        }
    }
}
