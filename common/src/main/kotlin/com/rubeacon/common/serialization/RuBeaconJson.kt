package com.rubeacon.common.serialization

import kotlinx.serialization.json.Json

/**
 * Ru-Beacon 전 모듈 표준 JSON 직렬화기 설정.
 *
 * 마이크로서비스 간 통신 시 신규 필드 추가에 따른 하위 호환성 유지를 위해
 * ignoreUnknownKeys를 활성화하고, 기본값 누락 방지를 위해 encodeDefaults를 적용함.
 */
object RuBeaconJson {
    val default: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        isLenient = true
    }
}
