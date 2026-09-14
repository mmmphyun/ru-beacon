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

    /**
     * 마인크래프트 인스턴스에서 회신된 명령어 실행 결과를 결과 스트림(`stream:commands:result`)에 발행함.
     *
     * @param requestId 실행 요청 ID
     * @param success 명령어 실행 성공 여부
     * @param output 실행 콘솔 출력 또는 오류 메시지
     * @param traceId 추적 ID
     * @param instanceId 응답을 보낸 마인크래프트 인스턴스 ID
     * @return Redis Stream Entry ID
     */
    suspend fun publishCommandResult(
        requestId: String,
        success: Boolean,
        output: String,
        traceId: String,
        instanceId: String
    ): String = withContext(Dispatchers.IO) {
        val params = XAddParams.xAddParams()
            .maxLen(RedisNamespaces.STREAM_MAX_LEN)
            .approximateTrimming()

        val fields = mapOf(
            "request_id" to requestId,
            "success" to success.toString(),
            "output" to output,
            "trace_id" to traceId,
            "instance_id" to instanceId,
            "timestamp" to System.currentTimeMillis().toString()
        )
        val entryId = jedis.xadd(RedisNamespaces.STREAM_COMMANDS_RESULT, params, fields)
        entryId.toString()
    }
}

