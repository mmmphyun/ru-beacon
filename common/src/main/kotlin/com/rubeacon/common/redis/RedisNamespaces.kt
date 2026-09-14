package com.rubeacon.common.redis

/**
 * Ru-Beacon 백엔드 내부 서비스 간 통신용 Redis Streams 및 Pub/Sub 네임스페이스 정의.
 * docs/TRANSPORT_PROTOCOL_SPEC.md §2 참조.
 */
object RedisNamespaces {
    const val STREAM_COMMANDS_REQUEST = "stream:commands:request"
    const val STREAM_COMMANDS_RESULT = "stream:commands:result"
    const val STREAM_DISCORD_ACTIONS = "stream:discord:actions"

    const val PUBSUB_HEARTBEAT = "pubsub:heartbeat"
    const val PUBSUB_COMMANDS_BROADCAST = "pubsub:commands:broadcast"

    const val GROUP_WORKER = "worker-group"
    const val GROUP_API_INGRESS = "api-ingress-group"
    const val GROUP_BOT = "bot-group"

    const val STREAM_MAX_LEN = 10_000L

    /**
     * 특정 테넌트 전용 비즈니스 이벤트 스트림 키 반환.
     * 격리 수준을 테넌트 단위로 분리하여 다중 테넌트 간 이벤트 오염 및 경합을 방지함.
     */
    fun eventsStream(tenantId: String): String = "stream:events:$tenantId"

    /**
     * 특정 테넌트 전용 인게임-디스코드 양방향 채팅 Pub/Sub 채널 키 반환.
     */
    fun chatChannel(tenantId: String): String = "pubsub:chat:$tenantId"
}
