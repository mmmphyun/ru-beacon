package com.rubeacon.bot

import com.rubeacon.common.event.EventEnvelope
import com.rubeacon.common.redis.RedisNamespaces
import com.rubeacon.common.serialization.RuBeaconJson
import kotlinx.serialization.encodeToString
import org.slf4j.LoggerFactory
import redis.clients.jedis.JedisPooled
import redis.clients.jedis.params.XAddParams

/**
 * 정규화된 Discord 이벤트를 Redis Streams(stream:events:{tenant_id})로 발행하는 컴포넌트.
 * FinOps OOM 방어를 위해 MAXLEN ~ 10000 근사 트리밍을 상시 적용함.
 */
class DiscordEventPublisher(
    private val jedis: JedisPooled?
) {
    private val log = LoggerFactory.getLogger(DiscordEventPublisher::class.java)

    /**
     * EventEnvelope를 대상 테넌트의 이벤트 스트림에 발행한다.
     *
     * @param envelope 표준 이벤트 봉투
     * @return Redis Stream Entry ID (발행 실패 시 또는 jedis 부재 시 null)
     */
    fun publish(envelope: EventEnvelope): String? {
        if (jedis == null) {
            log.warn("Jedis 인스턴스가 주입되지 않아 이벤트 발행을 건너뜁니다. (eventId={})", envelope.eventId)
            return null
        }

        return try {
            val streamKey = RedisNamespaces.eventsStream(envelope.tenantId)
            val jsonString = RuBeaconJson.default.encodeToString(envelope)
            val fields = mapOf(
                "payload" to jsonString,
                "event_id" to envelope.eventId,
                "event_type" to envelope.eventType,
                "occurred_at" to envelope.occurredAt
            )

            val entryId = jedis.xadd(
                streamKey,
                XAddParams.xAddParams().maxLen(RedisNamespaces.STREAM_MAX_LEN).approximateTrimming(),
                fields
            )
            log.info("이벤트 발행 성공: stream={}, eventId={}, entryId={}", streamKey, envelope.eventId, entryId)
            entryId.toString()
        } catch (e: Exception) {
            log.error("이벤트 Redis Streams 발행 실패 (eventId={}): {}", envelope.eventId, e.message, e)
            null
        }
    }
}
