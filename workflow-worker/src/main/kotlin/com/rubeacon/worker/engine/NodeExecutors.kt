package com.rubeacon.worker.engine

import com.rubeacon.common.redis.RedisNamespaces
import com.rubeacon.worker.db.AttendanceQuotas
import com.rubeacon.worker.db.RewardReservations
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.minus
import org.jetbrains.exposed.sql.SqlExpressionBuilder.plus
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.insertIgnore
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.sql.update
import redis.clients.jedis.JedisPooled
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
            redis.clients.jedis.params.XAddParams.xAddParams(),
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
            redis.clients.jedis.params.XAddParams.xAddParams(),
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
class AttendanceReservationExecutor : NodeExecutor {
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
    ): NodeResult = newSuspendedTransaction(Dispatchers.IO) {
        val quotaId = "quota_${tenantId}_$date"

        // 쿼터 기본 레코드 선행 생성 (최초 1회 보장)
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

        // 원자적 조건부 예약 증가 (Race condition 원천 차단)
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

        // 예약 레코드 삽입
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
                (AttendanceQuotas.tenantId eq tenantId) and (AttendanceQuotas.rewardDate eq date)
            }) {
                with(org.jetbrains.exposed.sql.SqlExpressionBuilder) {
                    it.update(AttendanceQuotas.reservedCount, AttendanceQuotas.reservedCount - 1)
                }
                it[updatedAt] = OffsetDateTime.now()
            }
        }

        NodeResult.Success(outputVariables = mapOf("reservation_status" to "RELEASED"))
    }
}
