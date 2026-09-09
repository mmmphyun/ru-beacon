package com.rubeacon.plugin.listener

import com.rubeacon.common.event.EventEntity
import com.rubeacon.common.event.EventEnvelope
import com.rubeacon.common.serialization.RuBeaconJson
import com.rubeacon.common.transport.WebSocketFrame
import com.rubeacon.plugin.config.RuBeaconConfig
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerAdvancementDoneEvent
import org.bukkit.event.player.PlayerLevelChangeEvent
import java.time.Instant
import java.util.UUID

/**
 * 플레이어 레벨 변경 이벤트 페이로드 DTO.
 */
@Serializable
data class PlayerLevelChangePayload(
    val playerUuid: String,
    val playerName: String,
    val oldLevel: Int,
    val newLevel: Int
)

/**
 * 플레이어 발전 과제 완료 이벤트 페이로드 DTO.
 */
@Serializable
data class PlayerAdvancementPayload(
    val playerUuid: String,
    val playerName: String,
    val advancementId: String
)

/**
 * Bukkit 게임 이벤트를 가로채 Ru-Beacon 공통 이벤트 봉투(EventEnvelope)로 정규화하는 리스너.
 *
 * docs/EVENT_CONTRACTS.md §1 및 DEC-071 규격을 준수함.
 *
 * @param config 테넌트 및 인스턴스 설정
 * @param onEventGenerated 생성된 WebSocketFrame을 아웃바운드 큐로 전달하는 콜백
 */
class MinecraftEventListener(
    private val config: RuBeaconConfig,
    private val onEventGenerated: (WebSocketFrame) -> Unit
) : Listener {

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onPlayerLevelChange(event: PlayerLevelChangeEvent) {
        val player = event.player
        val payloadDto = PlayerLevelChangePayload(
            playerUuid = player.uniqueId.toString(),
            playerName = player.name,
            oldLevel = event.oldLevel,
            newLevel = event.newLevel
        )
        val frame = buildEventFrame(
            eventType = "minecraft.player.level_up",
            player = player,
            idempotencyKey = "mc:lvl:${player.uniqueId}:${event.newLevel}",
            payloadDto = payloadDto
        )
        onEventGenerated(frame)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onPlayerAdvancementDone(event: PlayerAdvancementDoneEvent) {
        // 내부 레시피 언락 등 디스플레이가 없는 백그라운드 advancement는 이벤트 발행 대상에서 제외
        if (event.advancement.display == null) return
        val player = event.player
        val payloadDto = PlayerAdvancementPayload(
            playerUuid = player.uniqueId.toString(),
            playerName = player.name,
            advancementId = event.advancement.key.toString()
        )
        val frame = buildEventFrame(
            eventType = "minecraft.player.advancement_done",
            player = player,
            idempotencyKey = "mc:adv:${player.uniqueId}:${event.advancement.key}",
            payloadDto = payloadDto
        )
        onEventGenerated(frame)
    }

    private inline fun <reified T> buildEventFrame(
        eventType: String,
        player: org.bukkit.entity.Player,
        idempotencyKey: String,
        payloadDto: T
    ): WebSocketFrame {
        val now = Instant.now().toString()
        val eventId = "evt_${UUID.randomUUID()}"
        val traceId = "trc_${UUID.randomUUID()}"
        val envelope = EventEnvelope(
            eventId = eventId,
            eventType = eventType,
            source = "minecraft",
            sourceInstanceId = config.instanceId,
            tenantId = config.tenantId,
            minecraftNetworkId = config.networkId,
            occurredAt = now,
            receivedAt = now,
            correlationId = traceId,
            causationId = null,
            idempotencyKey = idempotencyKey,
            actor = EventEntity(type = "minecraft_player", id = player.uniqueId.toString()),
            subject = EventEntity(type = "minecraft_player", id = player.uniqueId.toString()),
            payload = RuBeaconJson.default.encodeToJsonElement(payloadDto).jsonObject
        )
        return WebSocketFrame.event(envelope)
    }
}
