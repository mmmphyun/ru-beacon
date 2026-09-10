package com.rubeacon.api

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MetricsAndHealthRoutesTest : BaseIntegrationTest() {

    @Test
    fun `healthz 엔드포인트는 200 OK를 반환해야 한다`() = testApplication {
        application {
            module(database, jedis)
        }

        val response = client.get("/healthz")
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("OK", response.bodyAsText())
    }

    @Test
    fun `metrics 엔드포인트는 Prometheus 포맷 메트릭을 반환해야 한다`() = testApplication {
        application {
            module(database, jedis)
        }

        val response = client.get("/metrics")
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("jvm_memory_used_bytes") || body.contains("jvm_threads_live_threads") || body.contains("process_cpu_usage"))
    }
}
