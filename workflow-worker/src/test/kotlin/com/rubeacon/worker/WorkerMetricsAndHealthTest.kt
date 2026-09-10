package com.rubeacon.worker

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import io.micrometer.core.instrument.binder.jvm.JvmMemoryMetrics
import io.micrometer.prometheus.PrometheusConfig
import io.micrometer.prometheus.PrometheusMeterRegistry
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WorkerMetricsAndHealthTest {

    @Test
    fun `worker healthz 엔드포인트는 200 OK를 반환해야 한다`() = testApplication {
        val meterRegistry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
        application {
            workerMetricsModule(meterRegistry)
        }

        val response = client.get("/healthz")
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("OK", response.bodyAsText())
    }

    @Test
    fun `worker metrics 엔드포인트는 Prometheus 포맷 메트릭을 정상 노출해야 한다`() = testApplication {
        val meterRegistry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT).apply {
            JvmMemoryMetrics().bindTo(this)
        }
        application {
            workerMetricsModule(meterRegistry)
        }

        val response = client.get("/metrics")
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("jvm_memory_used_bytes") || body.contains("jvm_memory"))
    }
}
