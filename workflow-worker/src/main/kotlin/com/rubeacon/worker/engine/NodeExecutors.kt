package com.rubeacon.worker.engine

import com.rubeacon.common.redis.RedisNamespaces
import com.rubeacon.worker.db.AttendanceQuotas
import com.rubeacon.worker.db.RewardReservations
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greater
import org.jetbrains.exposed.sql.SqlExpressionBuilder.minus
import org.jetbrains.exposed.sql.SqlExpressionBuilder.plus
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.insertIgnore
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.sql.update
import org.slf4j.LoggerFactory
import redis.clients.jedis.JedisPooled
import redis.clients.jedis.params.XAddParams
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID

interface NodeExecutor {
    val supportedNodeType: String

    suspend fun execute(
        context: WorkflowContext,
        inputs: Map<String, JsonElement>
    ): NodeResult
}

/**
 * 조건식 평가 후 on_true / on_false 분기를 반환하는 조건 분기 실행기.
 */
class ConditionBranchExecutor : NodeExecutor {
    override val supportedNodeType: String = "CONDITION_BRANCH"

    override suspend fun execute(
        context: WorkflowContext,
        inputs: Map<String, JsonElement>
    ): NodeResult {
        val leftRaw = inputs["left"]?.jsonPrimitive?.contentOrNull ?: ""
        val operator = inputs["operator"]?.jsonPrimitive?.contentOrNull ?: "EQUALS"
        val rightRaw = inputs["right"]?.jsonPrimitive?.contentOrNull ?: ""

        val leftResolved = VariableResolver.resolve(leftRaw, context)
        val rightResolved = VariableResolver.resolve(rightRaw, context)

        val conditionMet = when (operator.uppercase()) {
            "EQUALS" -> leftResolved.equals(rightResolved, ignoreCase = true)
            "NOT_EQUALS" -> !leftResolved.equals(rightResolved, ignoreCase = true)
            "GREATER_THAN_OR_EQUAL", "GTE" -> {
                val l = leftResolved.toDoubleOrNull()
                val r = rightResolved.toDoubleOrNull()
                if (l != null && r != null) l >= r else leftResolved >= rightResolved
            }
            "GREATER_THAN", "GT" -> {
                val l = leftResolved.toDoubleOrNull()
                val r = rightResolved.toDoubleOrNull()
                if (l != null && r != null) l > r else leftResolved > rightResolved
            }
            "LESS_THAN_OR_EQUAL", "LTE" -> {
                val l = leftResolved.toDoubleOrNull()
                val r = rightResolved.toDoubleOrNull()
                if (l != null && r != null) l <= r else leftResolved <= rightResolved
            }
            "LESS_THAN", "LT" -> {
                val l = leftResolved.toDoubleOrNull()
                val r = rightResolved.toDoubleOrNull()
                if (l != null && r != null) l < r else leftResolved < rightResolved
            }
            "CONTAINS" -> leftResolved.contains(rightResolved, ignoreCase = true)
            else -> false
        }

        val branch = if (conditionMet) "on_true" else "on_false"
        return NodeResult.Success(
            outputVariables = mapOf("condition_result" to conditionMet),
            branch = branch
        )
    }
}

/**
 * 마인크래프트 서버로 디스패치할 명령어를 Redis Streams에 발행하는 실행기.
 */
class MinecraftDispatchCommandExecutor(
    private val jedis: JedisPooled? = null
) : NodeExecutor {
    override val supportedNodeType: String = "MINECRAFT_DISPATCH_COMMAND"

    val dispatchedCommands = mutableListOf<Map<String, String>>()

    override suspend fun execute(
        context: WorkflowContext,
        inputs: Map<String, JsonElement>
    ): NodeResult {
        val commandTemplate = inputs["command"]?.jsonPrimitive?.contentOrNull
            ?: return NodeResult.Failure("Missing required input 'command'", "INVALID_INPUT")
        val instanceId = inputs["instance_id"]?.jsonPrimitive?.contentOrNull ?: "all"

        val resolvedCommand = VariableResolver.resolve(commandTemplate, context)
        val message = mapOf(
            "request_id" to "cmd_${UUID.randomUUID().toString().take(12)}",
            "tenant_id" to context.tenantId,
            "instance_id" to instanceId,
            "command" to resolvedCommand,
            "correlation_id" to context.correlationId
        )

        dispatchedCommands.add(message)
        jedis?.xadd(
            RedisNamespaces.STREAM_COMMANDS_REQUEST,
            XAddParams.xAddParams().maxLen(10_000L).approximateTrimming(),
            message
        )

        return NodeResult.Success(
            outputVariables = mapOf("dispatched_command" to resolvedCommand)
        )
    }
}

/**
 * 디스코드 메시지 전송 요청을 Redis Streams에 발행하는 실행기.
 */
class DiscordSendMessageExecutor(
    private val jedis: JedisPooled? = null
) : NodeExecutor {
    override val supportedNodeType: String = "DISCORD_SEND_MESSAGE"

    val sentMessages = mutableListOf<Map<String, String>>()

    override suspend fun execute(
        context: WorkflowContext,
        inputs: Map<String, JsonElement>
    ): NodeResult {
        val channelId = inputs["channel_id"]?.jsonPrimitive?.contentOrNull
            ?: return NodeResult.Failure("Missing 'channel_id'", "INVALID_INPUT")
        val messageTemplate = inputs["message"]?.jsonPrimitive?.contentOrNull
            ?: return NodeResult.Failure("Missing 'message'", "INVALID_INPUT")

        val resolvedMessage = VariableResolver.resolve(messageTemplate, context)
        val payload = mapOf(
            "action" to "SEND_MESSAGE",
            "tenant_id" to context.tenantId,
            "channel_id" to channelId,
            "content" to resolvedMessage,
            "correlation_id" to context.correlationId
        )

        sentMessages.add(payload)
        jedis?.xadd(
            RedisNamespaces.STREAM_DISCORD_ACTIONS,
            XAddParams.xAddParams().maxLen(10_000L).approximateTrimming(),
            payload
        )

        return NodeResult.Success(
            outputVariables = mapOf("sent_discord_message" to resolvedMessage)
        )
    }
}

/**
 * 일일 출석 선착순 보상 원자적 예약/확정/복구 노드 실행기.
 * docs/WORKFLOW_ENGINE_SPEC.md 및 MILESTONES.md 템플릿 B 규격을 준수함.
 */
class AttendanceReservationExecutor(
    private val jedis: JedisPooled? = null
) : NodeExecutor {
    private val log = LoggerFactory.getLogger(AttendanceReservationExecutor::class.java)

    companion object {
        private const val TTL_SECONDS = 48 * 3600L // 48시간 만료

        // KEYS[1]: quota:{tenantId}:{date}:count
        // KEYS[2]: quota:{tenantId}:{date}:players
        // ARGV[1]: totalLimit
        // ARGV[2]: playerUuid
        // ARGV[3]: ttlSeconds
        private val ADMISSION_LUA = """
            if redis.call('SISMEMBER', KEYS[2], ARGV[2]) == 1 then
                return 'ALREADY_RESERVED'
            end
            local current = tonumber(redis.call('GET', KEYS[1]) or '0')
            local limit = tonumber(ARGV[1])
            if current >= limit then
                return 'QUOTA_EXCEEDED'
            end
            redis.call('INCR', KEYS[1])
            redis.call('SADD', KEYS[2], ARGV[2])
            if redis.call('TTL', KEYS[1]) < 0 then
                redis.call('EXPIRE', KEYS[1], ARGV[3])
            end
            if redis.call('TTL', KEYS[2]) < 0 then
                redis.call('EXPIRE', KEYS[2], ARGV[3])
            end
            return 'OK'
        """.trimIndent()

        // KEYS[1]: quota:{tenantId}:{date}:count
        // KEYS[2]: quota:{tenantId}:{date}:players
        // ARGV[1]: playerUuid
        private val ROLLBACK_LUA = """
            if redis.call('SREM', KEYS[2], ARGV[1]) == 1 then
                local current = tonumber(redis.call('GET', KEYS[1]) or '0')
                if current > 0 then
                    redis.call('DECR', KEYS[1])
                end
            end
            return 'OK'
        """.trimIndent()
    }

    override val supportedNodeType: String = "ATTENDANCE_RESERVATION"

    override suspend fun execute(
        context: WorkflowContext,
        inputs: Map<String, JsonElement>
    ): NodeResult {
        val action = inputs["action"]?.jsonPrimitive?.contentOrNull ?: "RESERVE"
        val totalLimit = inputs["total_limit"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 100
        val rewardDate = LocalDate.now()

        val actor = context.initialEvent.actor
        val rawPlayerUuid = context.variables["Minecraft_UUID"]?.toString()
            ?: (if (actor?.type == "minecraft_player") actor.id else null)
            ?: UUID.randomUUID().toString()
        val playerUuid = runCatching { UUID.fromString(rawPlayerUuid) }.getOrElse { UUID.randomUUID() }

        return when (action.uppercase()) {
            "RESERVE" -> reserve(context.tenantId, rewardDate, totalLimit, playerUuid, context.correlationId)
            "COMMIT" -> commit(context.tenantId, rewardDate, playerUuid)
            "RELEASE" -> release(context.tenantId, rewardDate, playerUuid)
            else -> NodeResult.Failure("Unsupported action: $action", "INVALID_ACTION")
        }
    }

    private suspend fun reserve(
        tenantId: String,
        date: LocalDate,
        limit: Int,
        playerUuid: UUID,
        correlationId: String
    ): NodeResult {
        // 1. Redis 기반 1차 Admission Control (Fast-Fail)
        if (jedis != null) {
            val countKey = "quota:$tenantId:$date:count"
            val playersKey = "quota:$tenantId:$date:players"

            val admissionResult = try {
                jedis.eval(
                    ADMISSION_LUA,
                    listOf(countKey, playersKey),
                    listOf(limit.toString(), playerUuid.toString(), TTL_SECONDS.toString())
                )?.toString()
            } catch (e: Exception) {
                log.warn("[{}] Redis admission error, falling back to DB: {}", correlationId, e.message)
                null
            }

            if (admissionResult != null) {
                when (admissionResult) {
                    "ALREADY_RESERVED" -> {
                        log.warn("[{}] Fast-Fail (ALREADY_RESERVED): player={} date={}", correlationId, playerUuid, date)
                        return NodeResult.Failure(
                            reason = "Player already reserved attendance reward for $date",
                            errorCode = "ALREADY_RESERVED"
                        )
                    }
                    "QUOTA_EXCEEDED" -> {
                        log.warn("[{}] Fast-Fail (QUOTA_EXCEEDED): limit={} tenant={}", correlationId, limit, tenantId)
                        return NodeResult.Failure(
                            reason = "Quota limit reached ($limit max)",
                            errorCode = "QUOTA_EXCEEDED"
                        )
                    }
                    "OK" -> {
                        log.info("[{}] Redis admission granted: player={} tenant={}", correlationId, playerUuid, tenantId)
                        // 2차 관문: PostgreSQL 영속화 및 실패 시 보상 트랜잭션
                        return try {
                            persistReservation(tenantId, date, limit, playerUuid, correlationId)
                        } catch (e: Exception) {
                            log.error("[{}] DB persistence failed, triggering Redis rollback: {}", correlationId, e.message)
                            rollbackRedis(countKey, playersKey, playerUuid)
                            throw e
                        }
                    }
                }
            }
        }

        // Graceful Fallback: jedis가 없거나 Redis 장애 시 기존 DB 원자적 UPDATE로 안전 동작
        log.info("[{}] Fallback to DB conditional update: tenant={}, player={}", correlationId, tenantId, playerUuid)
        return reserveFallback(tenantId, date, limit, playerUuid, correlationId)
    }

    private suspend fun persistReservation(
        tenantId: String,
        date: LocalDate,
        limit: Int,
        playerUuid: UUID,
        correlationId: String
    ): NodeResult = newSuspendedTransaction(Dispatchers.IO) {
        val quotaId = "quota_${tenantId}_$date"

        AttendanceQuotas.insertIgnore {
            it[id] = quotaId
            it[AttendanceQuotas.tenantId] = tenantId
            it[rewardDate] = date
            it[totalLimit] = limit
            it[reservedCount] = 0
            it[committedCount] = 0
            it[createdAt] = OffsetDateTime.now()
            it[updatedAt] = OffsetDateTime.now()
        }

        val updatedRows = AttendanceQuotas.update({
            (AttendanceQuotas.tenantId eq tenantId) and
            (AttendanceQuotas.rewardDate eq date) and
            (AttendanceQuotas.reservedCount less limit)
        }) {
            it.update(reservedCount, reservedCount + 1)
            it[updatedAt] = OffsetDateTime.now()
        }

        if (updatedRows == 0) {
            val countKey = "quota:$tenantId:$date:count"
            val playersKey = "quota:$tenantId:$date:players"
            rollbackRedis(countKey, playersKey, playerUuid)
            return@newSuspendedTransaction NodeResult.Failure(
                reason = "Quota limit reached ($limit max)",
                errorCode = "QUOTA_EXCEEDED"
            )
        }

        val reservationId = "res_${UUID.randomUUID().toString().take(16)}"
        RewardReservations.insert {
            it[id] = reservationId
            it[RewardReservations.tenantId] = tenantId
            it[RewardReservations.quotaId] = quotaId
            it[rewardDate] = date
            it[RewardReservations.playerUuid] = playerUuid
            it[status] = "RESERVED"
            it[idempotencyKey] = correlationId
            it[expiresAt] = OffsetDateTime.now().plusMinutes(5)
            it[createdAt] = OffsetDateTime.now()
            it[updatedAt] = OffsetDateTime.now()
        }

        NodeResult.Success(
            outputVariables = mapOf(
                "reservation_id" to reservationId,
                "reservation_status" to "RESERVED"
            )
        )
    }

    private suspend fun reserveFallback(
        tenantId: String,
        date: LocalDate,
        limit: Int,
        playerUuid: UUID,
        correlationId: String
    ): NodeResult = newSuspendedTransaction(Dispatchers.IO) {
        val quotaId = "quota_${tenantId}_$date"

        val alreadyReserved = RewardReservations.selectAll().where {
            (RewardReservations.tenantId eq tenantId) and
            (RewardReservations.rewardDate eq date) and
            (RewardReservations.playerUuid eq playerUuid)
        }.count() > 0

        if (alreadyReserved) {
            return@newSuspendedTransaction NodeResult.Failure(
                reason = "Player already reserved attendance reward for $date",
                errorCode = "ALREADY_RESERVED"
            )
        }

        AttendanceQuotas.insertIgnore {
            it[id] = quotaId
            it[AttendanceQuotas.tenantId] = tenantId
            it[rewardDate] = date
            it[totalLimit] = limit
            it[reservedCount] = 0
            it[committedCount] = 0
            it[createdAt] = OffsetDateTime.now()
            it[updatedAt] = OffsetDateTime.now()
        }

        val updatedRows = AttendanceQuotas.update({
            (AttendanceQuotas.tenantId eq tenantId) and
            (AttendanceQuotas.rewardDate eq date) and
            (AttendanceQuotas.reservedCount less limit)
        }) {
            it.update(reservedCount, reservedCount + 1)
            it[updatedAt] = OffsetDateTime.now()
        }

        if (updatedRows == 0) {
            return@newSuspendedTransaction NodeResult.Failure(
                reason = "Quota limit reached ($limit max)",
                errorCode = "QUOTA_EXCEEDED"
            )
        }

        val reservationId = "res_${UUID.randomUUID().toString().take(16)}"
        RewardReservations.insert {
            it[id] = reservationId
            it[RewardReservations.tenantId] = tenantId
            it[RewardReservations.quotaId] = quotaId
            it[rewardDate] = date
            it[RewardReservations.playerUuid] = playerUuid
            it[status] = "RESERVED"
            it[idempotencyKey] = correlationId
            it[expiresAt] = OffsetDateTime.now().plusMinutes(5)
            it[createdAt] = OffsetDateTime.now()
            it[updatedAt] = OffsetDateTime.now()
        }

        NodeResult.Success(
            outputVariables = mapOf(
                "reservation_id" to reservationId,
                "reservation_status" to "RESERVED"
            )
        )
    }

    private fun rollbackRedis(countKey: String, playersKey: String, playerUuid: UUID) {
        runCatching {
            jedis?.eval(ROLLBACK_LUA, listOf(countKey, playersKey), listOf(playerUuid.toString()))
        }
    }

    private suspend fun commit(
        tenantId: String,
        date: LocalDate,
        playerUuid: UUID
    ): NodeResult = newSuspendedTransaction(Dispatchers.IO) {
        RewardReservations.update({
            (RewardReservations.tenantId eq tenantId) and
            (RewardReservations.rewardDate eq date) and
            (RewardReservations.playerUuid eq playerUuid) and
            (RewardReservations.status eq "RESERVED")
        }) {
            it[status] = "COMMITTED"
            it[updatedAt] = OffsetDateTime.now()
        }

        AttendanceQuotas.update({
            (AttendanceQuotas.tenantId eq tenantId) and (AttendanceQuotas.rewardDate eq date)
        }) {
            it.update(committedCount, committedCount + 1)
            it[updatedAt] = OffsetDateTime.now()
        }

        NodeResult.Success(outputVariables = mapOf("reservation_status" to "COMMITTED"))
    }

    private suspend fun release(
        tenantId: String,
        date: LocalDate,
        playerUuid: UUID
    ): NodeResult = newSuspendedTransaction(Dispatchers.IO) {
        val updated = RewardReservations.update({
            (RewardReservations.tenantId eq tenantId) and
            (RewardReservations.rewardDate eq date) and
            (RewardReservations.playerUuid eq playerUuid) and
            (RewardReservations.status eq "RESERVED")
        }) {
            it[status] = "RELEASED"
            it[updatedAt] = OffsetDateTime.now()
        }

        if (updated > 0) {
            AttendanceQuotas.update({
                (AttendanceQuotas.tenantId eq tenantId) and
                (AttendanceQuotas.rewardDate eq date) and
                (AttendanceQuotas.reservedCount greater 0)
            }) {
                with(org.jetbrains.exposed.sql.SqlExpressionBuilder) {
                    it.update(AttendanceQuotas.reservedCount, AttendanceQuotas.reservedCount - 1)
                }
                it[updatedAt] = OffsetDateTime.now()
            }
            jedis?.let {
                val countKey = "quota:$tenantId:$date:count"
                val playersKey = "quota:$tenantId:$date:players"
                rollbackRedis(countKey, playersKey, playerUuid)
            }
        }

        NodeResult.Success(outputVariables = mapOf("reservation_status" to "RELEASED"))
    }
}
