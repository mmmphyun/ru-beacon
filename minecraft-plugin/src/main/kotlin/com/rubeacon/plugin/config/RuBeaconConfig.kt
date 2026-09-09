package com.rubeacon.plugin.config

/**
 * Ru-Beacon 플러그인의 중앙 WSS 서버 통신 및 인증 설정.
 *
 * docs/TRANSPORT_PROTOCOL_SPEC.md §1.1 규격을 준수함.
 *
 * @property serverUrl 중앙 API Service의 WSS 엔드포인트 URL
 * @property tenantId 소속 테넌트 식별자
 * @property networkId 마인크래프트 네트워크 식별자
 * @property instanceId 해당 마인크래프트 서버 인스턴스 고유 식별자
 * @property instanceToken 인스턴스 전용 사전 발급 보안 인증 토큰
 * @property pingIntervalMs WebSocket 하트비트 PING 주기 (기본 30초)
 * @property reconnectInitialDelayMs 지수 백오프 최초 재연결 지연 시간 (기본 1초)
 * @property reconnectMaxDelayMs 지수 백오프 최대 지연 한계 시간 (기본 60초)
 * @property enabled 플러그인 WSS 활성화 여부
 */
data class RuBeaconConfig(
    val serverUrl: String = "ws://localhost:8080/ws/minecraft/v1",
    val tenantId: String = "tenant_default",
    val networkId: String = "net_default",
    val instanceId: String = "inst_default",
    val instanceToken: String = "token_default",
    val pingIntervalMs: Long = 30_000L,
    val reconnectInitialDelayMs: Long = 1_000L,
    val reconnectMaxDelayMs: Long = 60_000L,
    val enabled: Boolean = true
)
