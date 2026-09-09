package com.rubeacon.common.transport

/**
 * WebSocket 통신 계층에서 사용하는 공통 프로토콜 상수.
 * docs/TRANSPORT_PROTOCOL_SPEC.md §1 참조.
 */
object TransportConstants {
    const val HEADER_TENANT_ID = "X-Tenant-Id"
    const val HEADER_NETWORK_ID = "X-Network-Id"
    const val HEADER_INSTANCE_ID = "X-Instance-Id"
    const val HEADER_INSTANCE_TOKEN = "X-Instance-Token"

    const val WS_PATH_MINECRAFT_V1 = "/ws/minecraft/v1"

    const val PING_INTERVAL_SECONDS = 30L
    const val SESSION_TIMEOUT_SECONDS = 90L
    const val COMMAND_TIMEOUT_SECONDS = 10L

    const val CLOSE_CODE_FORBIDDEN = 4003
}
