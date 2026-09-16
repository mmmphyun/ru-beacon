import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Trend } from 'k6/metrics';

// Custom Metrics for Cloud SRE Observability
const fastFailCount = new Counter('admission_fast_fail_total');
const committedCount = new Counter('admission_committed_total');
const fastFailDuration = new Trend('fast_fail_duration_ms', true);
const committedDuration = new Trend('committed_duration_ms', true);
const serverProcessingDuration = new Trend('server_processing_time_ms', true); // TTFB (Waiting time)

const IS_CLOUD = (__ENV.NETWORK_ENV || 'local').toLowerCase() === 'cloud';
const BASE_URL = __ENV.API_BASE_URL || 'http://localhost:8080';
const TENANT_ID = __ENV.TENANT_ID || (`tenant_sre_${Date.now()}`);

export const options = {
  insecureSkipTLSVerify: __ENV.INSECURE_SKIP_TLS === 'true',
  scenarios: {
    concurrency_spike: {
      executor: 'ramping-arrival-rate',
      startRate: 50,
      timeUnit: '1s',
      preAllocatedVUs: 200,
      maxVUs: 1500,
      stages: [
        { duration: '10s', target: 200 },   // Warm-up to 200 RPS
        { duration: '30s', target: 1000 },  // Spike to 1,000 RPS concurrent burst
        { duration: '15s', target: 1000 },  // Sustained load at 1,000 RPS
        { duration: '10s', target: 0 },     // Cooldown
      ],
    },
  },
  thresholds: IS_CLOUD ? {
    // Cloud WAN thresholds (Physical RTT + TLS handshake overhead accounted)
    http_req_duration: ['p(95)<250', 'p(99)<400'],
    server_processing_time_ms: ['p(95)<15'], // Pure server admission computation TTFB
    fast_fail_duration_ms: ['p(95)<150'],
    checks: ['rate>0.95'],
  } : {
    // Local Loopback thresholds (sub-millisecond RTT)
    http_req_duration: ['p(95)<50', 'p(99)<100'],
    server_processing_time_ms: ['p(95)<5'],
    fast_fail_duration_ms: ['p(95)<15'],
    checks: ['rate>0.95'],
  },
};

export default function () {
  // Generate pseudo-random player UUID to simulate 10,000 distinct concurrent players
  const randomPlayerId = Math.floor(Math.random() * 10000);
  const playerUuid = `00000000-0000-0000-0000-${String(randomPlayerId).padStart(12, '0')}`;

  const payload = JSON.stringify({
    event_id: `evt_${Date.now()}_${Math.random().toString(36).substring(7)}`,
    event_type: 'minecraft.player.interact',
    source: 'MINECRAFT_BACKEND',
    tenant_id: TENANT_ID,
    minecraft_network_id: 'net_sre_test',
    timestamp: new Date().toISOString(),
    correlation_id: `trace-${Date.now()}-${randomPlayerId}`,
    idempotency_key: `idem-${playerUuid}-${new Date().toISOString().slice(0, 10)}`,
    version: 1,
    payload: {
      action: 'ATTENDANCE_REWARD',
      player_uuid: playerUuid,
      player_name: `Player_${randomPlayerId}`,
      total_limit: 10
    }
  });

  const params = {
    headers: {
      'Content-Type': 'application/json',
      'X-Tenant-Id': TENANT_ID,
    },
    timeout: '3s',
  };

  const startTime = Date.now();
  const res = http.post(`${BASE_URL}/api/v1/events/simulate`, payload, params);
  const duration = Date.now() - startTime;
  const ttfb = res.timings.waiting;

  serverProcessingDuration.add(ttfb);

  if (res.status === 200 || res.status === 202) {
    committedCount.add(1);
    committedDuration.add(duration);
    check(res, {
      'status is 200/202 committed': (r) => r.status === 200 || r.status === 202,
    });
  } else if (res.status === 429 || res.status === 409) {
    fastFailCount.add(1);
    fastFailDuration.add(duration);
    check(res, {
      'status is 429 fast-failed': (r) => r.status === 429 || r.status === 409,
      'fast-fail server computation under 15ms': (r) => ttfb < 15,
    });
  } else {
    check(res, {
      'unexpected status': (r) => false,
    });
  }

  sleep(0.01);
}
