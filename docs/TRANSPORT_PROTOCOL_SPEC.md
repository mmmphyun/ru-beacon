# Ru-Beacon 전송 계층 프로토콜 명세 (Transport Protocol Spec)

## 문서 역할
외부 Minecraft 플러그인과 중앙 Ru-Beacon API Service 간의 **WebSocket(WSS) 통신 규격** 및 VPC 내부 서비스 간의 **Redis Streams / Pub/Sub 메시징 규격**을 상세 정의한다.

---

## 1. 외부 통신: WebSocket (WSS) 프로토콜

외부 서버에 설치된 Ru-Beacon Minecraft Plugin(Paper / Velocity)은 중앙 API Service의 `/ws/minecraft/v1` 엔드포인트에 아웃바운드 WSS 클라이언트로 접속한다.

### 1.1 연결 핸드셰이크 및 인증
- **URL**: `wss://<ru-beacon-api-host>/ws/minecraft/v1`
- **인증 헤더 (또는 초기 쿼리 파라미터)**:
  - `X-Tenant-Id`: 테넌트 식별자 (예: `tenant_01J...`)
  - `X-Network-Id`: 마인크래프트 네트워크 식별자 (예: `net_01J...`)
  - `X-Instance-Id`: 인스턴스 식별자 (예: `inst_backend_01`)
  - `X-Instance-Token`: 인스턴스별 일회성 발급 비밀 토큰
- **검증 절차**:
  1. API Service는 `minecraft_instances` 테이블에서 `id` 및 `token_hash`(SHA-256)를 검증.
  2. 불일치 또는 비활성 테넌트인 경우 WebSocket Close Code `4003 (Forbidden)` 반환 후 즉시 세션 종료.
  3. 인증 성공 시 인스턴스 상태를 `ONLINE`으로 업데이트하고 세션을 메모리 레지스트리에 등록.

### 1.2 프레임 포맷 (JSON Frame)
모든 WSS 메시지는 UTF-8 텍스트 JSON 프레임을 사용한다.

```json
{
  "op": "EVENT | COMMAND_REQ | COMMAND_RES | PING | PONG",
  "trace_id": "trc_01J...",
  "timestamp": 1757318838000,
  "payload": {}
}
```

#### 프레임 Opcode 카탈로그
| Opcode | 방향 | 설명 | Payload |
|---|---|---|---|
| `PING` | 양방향 | 연결 유지 핑 (30초 주기) | `{}` |
| `PONG` | 양방향 | 핑에 대한 응답 | `{}` |
| `EVENT` | Plugin → Server | 마인크래프트 이벤트 업로드 | [EVENT_CONTRACTS.md](EVENT_CONTRACTS.md) 공통 봉투 |
| `COMMAND_REQ` | Server → Plugin | 마인크래프트 명령어 실행 요청 | `{ "request_id": "...", "command": "...", "args": [] }` |
| `COMMAND_RES` | Plugin → Server | 명령어 실행 결과 반환 | `{ "request_id": "...", "success": true, "output": "..." }` |

### 1.3 하트비트 및 Stale 감지 정책
- **주기**: 플러그인은 30초마다 `PING` 프레임을 서버로 전송.
- **타임아웃**: 서버가 90초 동안 유효한 프레임(PING/EVENT 등)을 수신하지 못하면 세션을 비정상 종료하고, DB의 인스턴스 상태를 `STALE`로 전환.
- **재연결 백오프**: 플러그인은 연결 끊김 시 지수 백오프(Exponential Backoff: 1s, 2s, 4s, 8s, 최대 60s + Jitter)를 적용하여 재연결 시도 (Thundering Herd 방지).

---

## 2. 내부 통신: Redis Streams & Pub/Sub 명세

API Service, Discord Bot, Workflow Worker 간 통신은 VPC 내부 격리 Redis 인스턴스를 사용한다.

### 2.1 토픽 및 키 네임스페이스

| 키 패턴 | 타입 | 주요 프로듀서 | 주요 컨슈머 | Consumer Group | 설명 |
|---|---|---|---|---|---|
| `stream:events:{tenant_id}` | Streams | API Service, Bot | Workflow Worker | `worker-group` | 비즈니스 이벤트 (보상, 인터랙션, 레벨업) |
| `stream:commands:request` | Streams | Workflow Worker, API | API Service | `api-ingress-group` | 마인크래프트 명령 실행 요청 |
| `stream:commands:result` | Streams | API Service | Workflow Worker | `worker-group` | 명령 실행 완료 결과 |
| `stream:discord:actions` | Streams | Workflow Worker | Discord Bot | `bot-group` | Discord 메시지 발송, 역할 부여 요청 |
| `pubsub:chat:{tenant_id}` | Pub/Sub | API Service, Bot | API Service, Bot | N/A | 유실 허용 양방향 인게임-디스코드 채팅 |
| `pubsub:heartbeat` | Pub/Sub | All | Observability | N/A | 3개 서비스 생존 상태 모니터링 |

### 2.2 Redis Streams 컨슈머 루프 규격 (Worker 의사코드)

```kotlin
suspend fun consumeEventStream(streamKey: String, groupName: String, consumerId: String) {
    // 1. Consumer Group 생성 (없으면 최초 1회 생성)
    runCatching { redis.xgroupCreate(streamKey, groupName, id = "0", makeStream = true) }

    while (isActive) {
        // 2. 미처리 보류 메시지 점유 (XAUTOCLAIM으로 장애 복구)
        val claimed = redis.xautoclaim(streamKey, groupName, consumerId, minIdleTimeMs = 60_000, startId = "0-0", count = 10)
        processMessages(claimed.messages)

        // 3. 신규 메시지 수신 (Block 2초 대기)
        val entries = redis.xreadgroup(groupName, consumerId, streamKey, id = ">", count = 10, blockMs = 2000)
        processMessages(entries)
    }
}

suspend fun processMessages(entries: List<StreamEntry>) {
    for (entry in entries) {
        try {
            // 이벤트 역직렬화 및 멱등 검증
            val event = parseEnvelope(entry.fields["payload"]!!)
            workflowEngine.execute(event)

            // 처리 완료 즉시 ACK
            redis.xack(entry.stream, "worker-group", entry.id)
        } catch (e: Exception) {
            // 실패 시 재시도하지 않고 감사 로그에 실패 기록 후 ACK (DLQ 또는 최종 유실 처리 원칙)
            logErrorAndAudit(entry, e)
            redis.xack(entry.stream, "worker-group", entry.id)
        }
    }
}
```

### 2.3 Streams 메모리 보호 정책 (`MAXLEN` 트리밍)
- 모든 `XADD` 명령 호출 시 반드시 `MAXLEN ~ 10000` (대략적 트리밍) 플래그를 포함한다.
  ```text
  XADD stream:events:tenant_01J MAXLEN ~ 10000 * payload "..."
  ```
- 이를 통해 컨슈머 다운 시에도 Redis 힙 메모리가 무제한 증가하여 OOM을 유발하는 상황을 원천 차단한다.
