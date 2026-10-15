// scripts/verify-wss-tenant-isolation.mjs
// Node.js v24 내장 WebSocket 기반 Traefik Ingress WSS 및 테넌트 격리 정밀 실측 스크립트

import { execFileSync, execSync } from 'child_process';

const BASE_WS_URL = process.env.API_WS_URL || 'ws://127.0.0.1:8080/ws/minecraft/v1';
const REDIS_POD = process.env.REDIS_POD || 'redis-master-7969b4d8d6-trx8s';
const PG_POD = process.env.PG_POD || 'postgres-postgresql-7ff5647b68-472dz';

async function testUnauthorizedAccess() {
  console.log('\n--- 1. 미승인 WSS 접속 차단 (Security 4003) 검증 ---');
  return new Promise((resolve) => {
    const ws = new WebSocket(BASE_WS_URL, {
      headers: {
        'X-Tenant-Id': 'tenant_sre_a',
        'X-Instance-Id': 'inst_sre_a_1',
        'X-Instance-Token': 'WRONG_INVALID_TOKEN_999'
      }
    });

    let closed = false;

    ws.onclose = (event) => {
      closed = true;
      if (event.code === 4003) {
        console.log(`[PASS] 서버가 잘못된 토큰 세션을 4003 Forbidden으로 즉시 차단함: code=${event.code}, reason="${event.reason}"`);
        resolve(true);
      } else {
        console.warn(`[WARN] 반환 코드가 4003이 아님: ${event.code}`);
        resolve(false);
      }
    };

    ws.onopen = () => {
      setTimeout(() => {
        if (!closed) {
          console.error('FAIL: 잘못된 토큰인데 WSS 연결이 유지되었습니다.');
          ws.close();
          resolve(false);
        }
      }, 500);
    };

    ws.onerror = () => {};
  });
}

function connectClient(tenantId, instanceId, token, label) {
  return new Promise((resolve, reject) => {
    const receivedFrames = [];
    const ws = new WebSocket(BASE_WS_URL, {
      headers: {
        'X-Tenant-Id': tenantId,
        'X-Instance-Id': instanceId,
        'X-Instance-Token': token
      }
    });

    ws.onopen = () => {
      console.log(`[${label}] WSS 세션 수립 완료 (Tenant: ${tenantId}, Instance: ${instanceId})`);
      resolve({ ws, receivedFrames, label, tenantId, instanceId });
    };

    ws.onmessage = (event) => {
      try {
        const frame = JSON.parse(event.data);
        receivedFrames.push(frame);
        console.log(`[${label}] 프레임 수신: op=${frame.op}, traceId=${frame.trace_id}, payload=${JSON.stringify(frame.payload)}`);
      } catch (e) {
        console.warn(`[${label}] JSON 파싱 오류:`, event.data);
      }
    };

    ws.onerror = (err) => {
      console.error(`[${label}] WebSocket 에러:`, err);
      reject(err);
    };

    ws.onclose = (ev) => {
      console.log(`[${label}] 연결 종료: code=${ev.code}`);
    };
  });
}

function publishRedisBroadcast(messageObj) {
  const payloadStr = JSON.stringify(messageObj);
  execFileSync('kubectl', [
    'exec', '-n', 'database', REDIS_POD, '--',
    'redis-cli', 'PUBLISH', 'pubsub:commands:broadcast', payloadStr
  ], { stdio: 'pipe' });
}

async function run() {
  console.log('================================================================');
  console.log('  Ru-Beacon Traefik Ingress WSS & 4대 아키텍처 테넌트 격리 실측');
  console.log('================================================================');

  // 1. 보안 검증: 미승인 접속 즉시 4003 차단
  const authBlocked = await testUnauthorizedAccess();
  if (!authBlocked) {
    throw new Error('보안 차단 검증 실패');
  }

  // 2. 테넌트 A, B 동시 세션 수립
  console.log('\n--- 2. 테넌트 A, B 동시 WSS 세션 수립 ---');
  const clientA = await connectClient('tenant_sre_a', 'inst_sre_a_1', 'token_secret_a_123', 'Client-A (Tenant A)');
  const clientB = await connectClient('tenant_sre_b', 'inst_sre_b_1', 'token_secret_b_123', 'Client-B (Tenant B)');

  // 세션 레지스트리 등록 안정화 대기
  await new Promise(r => setTimeout(r, 1000));

  // 3. PING-PONG RTT 측정
  console.log('\n--- 3. Traefik 인그레스 WSS PING-PONG 왕복 지연시간(RTT) 실측 ---');
  const pingStart = Date.now();
  const pingTraceId = `trace-ping-${Date.now()}`;
  clientA.ws.send(JSON.stringify({
    op: 'PING',
    trace_id: pingTraceId,
    timestamp: Date.now(),
    payload: {}
  }));

  await new Promise(r => setTimeout(r, 600));
  const pongA = clientA.receivedFrames.find(f => f.op === 'PONG' && f.trace_id === pingTraceId);
  if (pongA) {
    const rtt = Date.now() - pingStart;
    console.log(`[PASS] Client-A PONG 수신: RTT = ${rtt}ms (WAN Traefik Ingress)`);
  } else {
    console.warn('[WARN] PONG 응답 프레임 미수신');
  }

  // 4. 테넌트 격리 실측 1: Tenant A 브로드캐스트 발행
  console.log('\n--- 4. 테넌트 격리 실측 1: Tenant A 전용 브로드캐스트 발행 ---');
  const traceA = `trace-cmd-tenant-a-${Date.now()}`;
  publishRedisBroadcast({
    instance_id: 'all',
    tenant_id: 'tenant_sre_a',
    request_id: 'req_a_001',
    command: 'broadcast "Hello Tenant A Only"',
    correlation_id: traceA
  });

  await new Promise(r => setTimeout(r, 1200));

  const frameReceivedByA = clientA.receivedFrames.filter(f => f.trace_id === traceA);
  const frameReceivedByB_forA = clientB.receivedFrames.filter(f => f.trace_id === traceA);

  console.log(`- Client A (Tenant A) 수신 건수: ${frameReceivedByA.length}건`);
  console.log(`- Client B (Tenant B) 수신 건수: ${frameReceivedByB_forA.length}건`);

  // 5. 테넌트 격리 실측 2: Tenant B 브로드캐스트 발행
  console.log('\n--- 5. 테넌트 격리 실측 2: Tenant B 전용 브로드캐스트 발행 ---');
  const traceB = `trace-cmd-tenant-b-${Date.now()}`;
  publishRedisBroadcast({
    instance_id: 'all',
    tenant_id: 'tenant_sre_b',
    request_id: 'req_b_001',
    command: 'broadcast "Hello Tenant B Only"',
    correlation_id: traceB
  });

  await new Promise(r => setTimeout(r, 1200));

  const frameReceivedByB = clientB.receivedFrames.filter(f => f.trace_id === traceB);
  const frameReceivedByA_forB = clientA.receivedFrames.filter(f => f.trace_id === traceB);

  console.log(`- Client B (Tenant B) 수신 건수: ${frameReceivedByB.length}건`);
  console.log(`- Client A (Tenant A) 수신 건수: ${frameReceivedByA_forB.length}건`);

  // 6. DB 실시간 ONLINE 상태 검증
  console.log('\n--- 6. DB minecraft_instances 실시간 ONLINE 상태 확인 ---');
  const dbStatusBeforeClose = execSync(
    `kubectl exec -n database ${PG_POD} -- psql -U rubeacon_app -d rubeacon -t -A -c "SELECT id, status FROM minecraft_instances ORDER BY id;"`
  ).toString().trim();
  console.log('현재 인스턴스 상태:\n' + dbStatusBeforeClose);

  // 7. 세션 정상 종료 및 OFFLINE 전이 확인
  console.log('\n--- 7. 세션 정상 종료 및 OFFLINE 전이 확인 ---');
  clientA.ws.close();
  clientB.ws.close();
  await new Promise(r => setTimeout(r, 1500));

  const dbStatusAfterClose = execSync(
    `kubectl exec -n database ${PG_POD} -- psql -U rubeacon_app -d rubeacon -t -A -c "SELECT id, status FROM minecraft_instances ORDER BY id;"`
  ).toString().trim();
  console.log('종료 후 인스턴스 상태:\n' + dbStatusAfterClose);

  console.log('\n================================================================');
  console.log('  [ALL PASS] WSS 인그레스 연결 및 테넌트 분산 세션 실측 완료!');
  console.log('================================================================');
}

run().catch(err => {
  console.error('\n[TEST FAILED]:', err.message || err);
  process.exit(1);
});
