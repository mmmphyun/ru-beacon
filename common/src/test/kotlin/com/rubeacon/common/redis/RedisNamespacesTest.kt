package com.rubeacon.common.redis

import kotlin.test.Test
import kotlin.test.assertEquals

class RedisNamespacesTest {

    @Test
    fun `instanceHeartbeatKey는 presence prefix를 가진 표준 키를 반환해야 한다`() {
        val instanceId = "inst_test_01"
        val expected = "presence:instance:inst_test_01"

        assertEquals(expected, RedisNamespaces.instanceHeartbeatKey(instanceId))
    }

    @Test
    fun `HEARTBEAT_TTL_SECONDS는 60초여야 한다`() {
        assertEquals(60L, RedisNamespaces.HEARTBEAT_TTL_SECONDS)
    }
}
