# Ru-Beacon 이벤트 계약

## 1. 목적

Ru-Beacon 서비스와 전용 Minecraft 모듈 사이의 이벤트 형식·전달 보장·중복 처리 기준을 정의한다. 이벤트별 상세 기능은 [PRODUCT_REQUIREMENTS.md](./PRODUCT_REQUIREMENTS.md), 서비스 구조는 [REENGINEERING_ARCHITECTURE_BLUEPRINT.md](./REENGINEERING_ARCHITECTURE_BLUEPRINT.md)를 따른다.

## 2. 식별자 모델

```text
Tenant/Workspace
└── Minecraft Network
    ├── Proxy Instance
    └── Backend Instance
```

- `tenant_id`: Discord Community와 Minecraft Network를 묶는 관리 범위
- `minecraft_network_id`: 하나의 Proxy와 Backend 집합
- `source_instance_id`: 이벤트를 발생시킨 Proxy·Backend·서비스 인스턴스
- `event_id`: producer가 발급하는 이벤트 고유 ID
- `correlation_id`: 하나의 사용자 동작 또는 Workflow 실행 추적
- `causation_id`: 현재 이벤트를 만든 상위 이벤트·노드 실행
- `idempotency_key`: 부작용 중복 실행 방지

## 3. 공통 이벤트 봉투

모든 표준 이벤트는 다음 봉투를 사용한다.

```json
{
  "event_id": "evt_01J...",
  "event_type": "minecraft.player.level_up",
  "source": "minecraft",
  "source_instance_id": "backend-survival-01",
  "tenant_id": "tenant_01J...",
  "minecraft_network_id": "network_01J...",
  "occurred_at": "2026-08-21T00:00:00Z",
  "received_at": "2026-08-21T00:00:01Z",
  "correlation_id": "corr_01J...",
  "causation_id": null,
  "idempotency_key": "tenant:workflow:event:subject",
  "actor": {
    "type": "minecraft_player",
    "id": "minecraft-uuid"
  },
  "subject": {
    "type": "minecraft_player",
    "id": "minecraft-uuid"
  },
  "schema_version": 1,
  "payload": {}
}
```

### 3.1 필드 규칙

| 필드 | 필수 | 규칙 |
|---|---:|---|
| `event_id` | 예 | Producer가 발급하는 전역 고유값 |
| `event_type` | 예 | `source.subject.action` 형태의 안정적인 이름 |
| `source` | 예 | `minecraft`, `discord`, `system`, `external` |
| `source_instance_id` | 예 | 발생 인스턴스. 논리 이벤트는 system ID 사용 |
| `tenant_id` | 예 | 모든 이벤트는 Tenant 경계를 가져야 함 |
| `minecraft_network_id` | 조건부 | Minecraft 관련 이벤트는 필수 |
| `occurred_at` | 예 | 원천 발생 시각, UTC 저장 |
| `received_at` | 예 | Ru-Beacon 수신 시각, UTC 저장 |
| `correlation_id` | 예 | 실행 추적용. 없으면 ingress에서 생성 |
| `causation_id` | 조건부 | 파생 이벤트가 아니면 null |
| `idempotency_key` | 예 | 부작용 실행 전에 중복 확인 |
| `actor` | 아니오 | 시스템 이벤트는 null 가능 |
| `subject` | 아니오 | 대상 없는 전역 이벤트는 null 가능 |
| `schema_version` | 예 | payload 계약 버전 |
| `payload` | 예 | 이벤트별 데이터. 봉투 필드와 중복 금지 |

필드의 의미나 타입을 깨는 변경은 새 `schema_version`으로 처리한다. 호환 가능한 필드 추가는 기존 버전 소비자가 무시할 수 있어야 한다.

## 4. 표준 이벤트 카탈로그

### 4.1 Minecraft 이벤트

| 이벤트 | 주요 payload |
|---|---|
| `minecraft.player.level_up` | UUID, 닉네임, 이전 레벨, 현재 레벨 |
| `minecraft.player.achievement` | UUID, 닉네임, 업적 ID, 표시명 |
| `minecraft.command.executed` | UUID, 닉네임, 명령어 식별자, 인자, 결과 |
| `minecraft.player.joined` | UUID, 닉네임, Backend ID |
| `minecraft.player.left` | UUID, 닉네임, Backend ID |
| `minecraft.player.backend_changed` | UUID, 이전 Backend, 새 Backend |
| `minecraft.instance.heartbeat` | 인스턴스 타입, 모듈 버전, 상태, 플레이어 수 |
| `minecraft.command.result` | 요청 ID, 대상, 성공 여부, 결과 요약 |
| `minecraft.verification.completed` | 인증 코드 ID, UUID, 닉네임, 결과 |

### 4.2 Discord 이벤트

| 이벤트 | 주요 payload |
|---|---|
| `discord.button.clicked` | 사용자 ID, 메시지 ID, custom ID, 값 |
| `discord.modal.submitted` | 사용자 ID, 모달 ID, 입력값 |
| `discord.command.executed` | 사용자 ID, 명령어, 인자 |
| `discord.message.received` | 사용자 ID, 채널 ID, 메시지, 정제 결과 |
| `discord.poll.interacted` | 사용자 ID, 메시지 ID, 선택값 |
| `discord.guild.member_left` | 사용자 ID, Tenant ID |
| `discord.interaction.response` | interaction ID, 결과, ephemeral 여부 |

### 4.3 시스템 이벤트

| 이벤트 | 주요 payload |
|---|---|
| `system.attendance.reset` | Tenant ID, 시간대, 보상일, 수량 |
| `system.workflow.activated` | Workflow ID, version |
| `system.external.received` | 외부 source, 원본 event type, payload reference |
| `system.discord.connection_changed` | 상태, 오류 코드, 시각 |
| `system.minecraft.connection_changed` | Instance ID, 상태, 오류 코드 |
| `system.workflow.execution_failed` | Workflow ID, node ID, 원인 |

## 5. 전달 보장

| 이벤트 종류 | 유실 | 중복 | 순서 | 처리 방식 |
|---|---:|---:|---:|---|
| 채팅·상태 지표 | 허용 | 허용 | 불필요 | Pub/Sub 또는 최신값 상태 |
| 인증·보상·관리자 명령 | 불허 | 가능 | 필요한 범위 | Streams/명령 큐 + 멱등성 |
| Sandbox 이벤트 | 장애 시 유실 | 가능 | Workflow 기준 | 자동 재시도 없음, 실패 기록 |
| heartbeat·presence | 허용 | 허용 | 최신값 우선 | Redis TTL 상태 |

자동 재시도는 기본적으로 사용하지 않는다. 실패는 실행 로그·감사 로그·실패 저장소에 남긴다.

## 6. 중복 방지와 쿨타임

중복 방지와 쿨타임은 별도 정책이다.

- 이벤트 ID가 있는 전역 이벤트: `event_id` 기준 24시간 중복 방지 TTL
- 이벤트 ID가 없는 전역 이벤트: source·type·payload fingerprint 기준 10초 중복 방지 TTL
- 사용자 이벤트: `tenant_id + workflow_id + event_id + subject_id` 기준
- 쿨타임 기본값: 0초
- 쿨타임 범위: Workflow·Trigger·Tenant 또는 사용자 scope 설정

쿨타임은 서로 다른 정상 이벤트를 제한할 수 있으므로 중복 방지의 대체 수단으로 사용하지 않는다.

## 7. 명령 요청과 결과

Minecraft 명령은 이벤트와 구분되는 요청·결과 흐름으로 처리한다.

```text
Workflow Worker / API Service
→ Redis Streams: stream:commands:request
→ API Service (WSS Ingress)
→ WebSocket Frame (COMMAND_REQUEST)
→ 대상 Backend Plugin (Paper / Velocity)
→ BukkitScheduler 메인 틱 동기 실행
→ WebSocket Frame (COMMAND_RESPONSE)
→ API Service
→ Redis Streams: stream:commands:result
→ Workflow Worker (결과 처리 및 감사 로그 기록)
```

- 플레이어 명령: 플레이어가 있는 Backend Instance의 WebSocket 세션으로 전달
- 네트워크 공지: 등록된 Backend Instance 전체 세션으로 브로드캐스트
- `request_id`는 멱등성 키와 감사 로그 연결에 사용
- Plugin은 DB 및 Redis에 직접 접근하지 않고 오직 WSS로만 소통
- 명령 결과가 10초 이내에 회신되지 않으면 타임아웃 실패 처리

## 8. 출석 보상 이벤트 계약

출석 보상은 수량 예약과 지급 결과를 분리한다.

```text
discord.attendance.clicked
→ eligibility.checked
→ reward.reserved
→ minecraft.command.request (via WSS)
→ minecraft.command.result (via WSS)
→ reward.committed | reward.released
```

- 중복 키: Tenant + 보상일 + Minecraft UUID
- 보상 예약은 원자적으로 수행
- 명령 성공 시 commit
- 실패·타임아웃 시 release
- 성공·실패 결과는 Ephemeral 응답과 감사 로그에 반영

## 9. 전송 계층 매핑 및 키 네임스페이스

### 9.1 내부 Redis 네임스페이스
- Redis Streams (영속/신뢰성, `MAXLEN ~ 10000`):
  - `stream:events:{tenant_id}`: Discord/Minecraft 비즈니스 이벤트 (Consumer Group: `worker-group`)
  - `stream:commands:request`: Minecraft 명령 실행 요청 (Consumer Group: `api-ingress-group`)
  - `stream:commands:result`: Minecraft 명령 실행 결과 (Consumer Group: `worker-group`)
  - `stream:discord:actions`: Discord 메시지/역할/채널 변경 요청 (Consumer Group: `bot-group`)
- Redis Pub/Sub (유실 허용 실시간):
  - `pubsub:chat:{tenant_id}`: 양방향 채팅 릴레이
  - `pubsub:heartbeat`: 모듈/서비스 생존 핑

### 9.2 외부 WebSocket 프레임 구조
외부 Minecraft Plugin과 중앙 API Service 간의 WSS 통신은 JSON 텍스트 프레임을 사용한다.

```json
{
  "op": "EVENT | COMMAND_REQ | COMMAND_RES | PING | PONG",
  "trace_id": "trc_01J...",
  "timestamp": 1757318838000,
  "payload": {}
}
```

## 10. 이벤트 계약 변경 규칙

- 기존 필드 의미 변경 금지
- 호환 필드 추가는 schema version 유지 가능
- 필드 삭제·타입 변경·필수성 변경은 새 버전
- Producer와 Consumer 계약 테스트 필수
- 변경 이유와 영향은 `DECISION_LOG.md`에 기록

