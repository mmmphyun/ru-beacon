package com.rubeacon.api.redis

import com.rubeacon.common.redis.RedisNamespaces
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import redis.clients.jedis.JedisPooled
import redis.clients.jedis.params.XAddParams

/**
 * 수신된 비즈니스 이벤트를 테넌트 전용 Redis Streams로 발행하는 프로듀서.
 * docs/TRANSPORT_PROTOCOL_SPEC.md §2.3 메모리 보호 정책(MAXLEN ~ 10000)을 강제함.
 */
class RedisEventPublisher(private val jedis: JedisPooled) {

    /**
     * 테넌트 스트림(`stream:events:{tenant_id}`)에 직렬화된 이벤트를 발행함.
     *
     * @param tenantId 이벤트 소유 테넌트 ID
     * @param payloadJson EventEnvelope JSON 문자열
     * @return Redis Stream Entry ID (예: "1710000000000-0")
     */
    suspend fun publishEvent(tenantId: String, payloadJson: String): String = withContext(Dispatchers.IO) {
        val streamKey = RedisNamespaces.eventsStream(tenantId)
        val params = XAddParams.xAddParams()
            .maxLen(RedisNamespaces.STREAM_MAX_LEN)
            .approximateTrimming()

        val fields = mapOf("payload" to payloadJson)
        val entryId = jedis.xadd(streamKey, params, fields)
        entryId.toString()
    }
}
