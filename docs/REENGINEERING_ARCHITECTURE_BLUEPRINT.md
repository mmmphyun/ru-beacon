# Ru-Beacon 재설계 아키텍처 청사진

## 문서 역할

이 문서는 Ru-Beacon의 최종 구조, 서비스 책임, 데이터 흐름, 기술 선택, 비기능 요구사항을 정의한다. 제품 동작의 상세 완료 조건은 [PRODUCT_REQUIREMENTS.md](PRODUCT_REQUIREMENTS.md), 이벤트 필드는 [EVENT_CONTRACTS.md](EVENT_CONTRACTS.md), 운영 절차는 [OPERATIONS_RUNBOOK.md](OPERATIONS_RUNBOOK.md), 상세 데이터베이스 DDL은 [DATABASE_SCHEMA.sql](DATABASE_SCHEMA.sql), 통신 프로토콜은 [TRANSPORT_PROTOCOL_SPEC.md](TRANSPORT_PROTOCOL_SPEC.md), 워크플로우 엔진은 [WORKFLOW_ENGINE_SPEC.md](WORKFLOW_ENGINE_SPEC.md), 테스트 하네스는 [TESTING_STRATEGY.md](TESTING_STRATEGY.md)에서 관리한다.

## 1. 최종 구조

```text
[External Minecraft Networks]
  Proxy Instance / Backend Instances (Paper / Velocity)
        │ (WebSocket Client over WSS / TLS)
        ▼
┌──────────────────────────────────────────────────────────────┐
│ Ru-Beacon Platform (Kubernetes / K3s)                         │
│                                                              │
│   Web Dashboard (Next.js + Shadcn UI)                        │
│         │ (HTTPS REST / Session)                             │
│         ▼                                                    │
│   API Service (Ktor) ────► PostgreSQL (Flyway + JSONB)       │
│     ▲ (WebSocket Ingress)                                    │
│     │                                                        │
│     ▼ (Redis Streams & Pub/Sub - Internal VPC Only)          │
│   ┌────────────────────────────────────────────────────────┐ │
│   │ Redis (In-Memory Streams, Pub/Sub, TTL, ZSET)          │ │
│   └────────────────────────────────────────────────────────┘ │
│     ▲                            ▲                           │
│     │ (Redis Streams/PubSub)     │ (Redis Streams/PubSub)    │
│     ▼                            ▼                           │
│   Discord Bot (Kord)      Workflow Worker (Ktor)             │
│         │ (Gateway WebSocket)                                │
│         ▼                                                    │
│   Discord API                                                │
└──────────────────────────────────────────────────────────────┘
```

Ru-Beacon 서비스는 여러 운영 단위(Tenant)를 관리한다. 한 운영 단위는 하나의 Discord 커뮤니티와 하나의 Minecraft 네트워크를 묶으며, Minecraft 네트워크는 프록시 인스턴스와 여러 백엔드 인스턴스로 표현한다.

구성 모듈은 다음과 같다.

- **Web Dashboard**: Next.js + Shadcn UI 기반 관리 화면. 초기 설정, 관리자 2FA 설정, 카드/위저드형 워크플로우 빌더, 상태·로그 조회
- **API Service**: Ktor 기반 백엔드. API 인증·인가, 설정 CRUD, 조회 API, 외부 Minecraft Module을 수용하는 WebSocket(WSS) Ingress Server
- **Discord Bot**: Kord 기반 비동기 코루틴 봇. Discord Gateway 상호작용, 메시지·버튼·모달·슬래시 명령 정규화 및 Ephemeral 결과 응답
- **Workflow Worker**: Ktor 기반 백그라운드 워커. 이벤트 수신, 인메모리 DAG 해석, 코루틴 기반 순차/병렬 실행 및 종단 단일 감사 로그 기록
- **Ru-Beacon Minecraft Module**: Paper 1.20.4+ 및 Velocity 3.3+ 전용 플러그인. 전용 비동기 스레드 풀 기반 WSS 클라이언트 통신, `BukkitScheduler` 메인 틱 동기 디스패치
- **PostgreSQL**: 영속 제품 데이터와 감사 데이터 소유 저장소. 메타데이터 정규화 컬럼 + Versioned JSONB 워크플로우 스냅샷
- **Redis (VPC 내부 격리)**: 서비스 간 내부 버스. Streams(인증/보상/명령/워크플로우), Pub/Sub(실시간 채팅/하트비트), Presence TTL
- **Observability**: Prometheus 메트릭, 구조화 JSON 로그, Grafana 대시보드, 장애 알림 집계

## 2. 서비스 간 책임

| 구성요소 | 책임 | 하지 않는 일 |
|---|---|---|
| Web Dashboard | 설정·위저드 폼 편집·운영 화면 제공 | DB 직접 접근, 권한 우회, 직접 실행 |
| API Service | API 인증·인가, Tenant 격리, 설정 저장, WebSocket Ingress | 워크플로우 전체 실행, 게임 로직 직접 처리 |
| Discord Bot | Discord Gateway 입력·응답, 인터랙션 이벤트 정규화 | DB 직접 접근, 임의 정책 변경 |
| Workflow Worker | 워크플로우 DAG 순회·조건 평가·변수 해석·결과 감사 로그 기록 | 외부 Discord/Minecraft 직접 연결 (Redis 경유) |
| Minecraft Module | 게임 이벤트 수집, 메인 틱 스케줄링 명령어 실행, WSS 클라이언트 연결 | PostgreSQL 및 내부 Redis 직접 접근 (API WSS만 접속) |
| PostgreSQL | 설정·사용자 연결·감사 로그·JSONB 워크플로우 영속화 | 실시간 메시지 브로커 |
| Redis | VPC 내부 서비스 간 Streams/PubSub 메시징 및 짧은 상태 캐시 | 외부 노출, 장기 데이터 유일 원장 |

API가 워크플로우를 직접 실행하지 않는 것은 기능 분리를 위한 핵심 결정이다. 실행 부하와 장시간 처리의 격리를 위해 Worker를 독립 컨테이너로 배포한다.

## 3. 데이터 소유권과 식별자

- **Ru-Beacon 플랫폼**: 여러 Tenant와 연결된 서비스·인스턴스·권한의 운영
- **Tenant/Workspace**: Discord 커뮤니티와 Minecraft 네트워크의 결합, 구성·권한·연결 계정·워크플로우
- **Minecraft Network**: 프록시와 백엔드 인스턴스의 집합, 네트워크 전체 공지와 온라인 상태
- **Proxy Instance**: 네트워크 라우팅·전역 이벤트 진입점
- **Backend Instance**: 실제 플레이어와 게임 명령이 실행되는 서버
- **Minecraft Module**: 플랫폼이 발급한 인스턴스 토큰으로 인증된 WebSocket 연결

Minecraft 계정 연결은 Tenant 전체에 적용한다. 플레이어가 네트워크 어디에 온라인인지 판별해 보상은 해당 백엔드에서 실행하고, 공지·네트워크 명령은 전체 네트워크로 전달할 수 있어야 한다.

## 4. 데이터 흐름

### 4.1 Minecraft 이벤트
Minecraft Module이 표준 이벤트를 생성하고 WSS를 통해 API Service에 전송한다. API Service는 토큰과 테넌트 경계를 검증한 뒤 Redis Streams(`stream:events:{tenant_id}`)로 이벤트를 발행한다. Workflow Worker가 이를 컨슘하여 워크플로우를 평가하고 필요한 액션을 Redis로 재발행한다.

### 4.2 Discord 상호작용
Discord Bot이 버튼·모달·명령·메시지를 공통 이벤트로 정규화하여 Redis Streams로 발행한다. Worker는 해당 이벤트를 워크플로우 시작점으로 삼고, 결과 메시지·Ephemeral 응답·Minecraft 명령을 각 어댑터에 전달한다.

### 4.3 명령 실행
API·Worker는 Redis Streams에 명령 요청(`minecraft.command.request`)을 발행한다. API Service가 이를 읽어 대상 인스턴스의 WebSocket 세션으로 전달한다. 대상 Minecraft Module은 비동기로 수신한 뒤 `BukkitScheduler.runTask`를 통해 메인 스레드에서 allowlist 검증 후 실행하고, 결과(`minecraft.command.result`)를 WSS로 회신한다.

### 4.4 이벤트 계약
모든 이벤트는 `event_id`, `event_type`, `source`, `tenant_id`, `minecraft_network_id`, 시각, 상관관계 ID, 멱등성 키, 버전, payload를 갖는 공통 envelope를 사용한다. 상세 스키마는 [EVENT_CONTRACTS.md](EVENT_CONTRACTS.md), 전송 프로토콜은 [TRANSPORT_PROTOCOL_SPEC.md](TRANSPORT_PROTOCOL_SPEC.md)의 단일 기준을 따른다.

## 5. Sandbox 실행 모델

- **저장 구조**: 하이브리드 모델. 메타데이터(id, tenant, version, status)는 RDBMS 컬럼으로 관리하고, 노드/엣지 그래프 전체는 `definition JSONB` 스냅샷으로 영속화하여 원자적 롤백과 배포를 보장한다.
- **런타임 엔진**: 인메모리 DAG 디스패처. 배포(`ACTIVE`) 시 Tarjan/Kahn 알고리즘으로 순환 참조를 사전 차단한다.
- **실행**: 순차 노드는 직렬 순회, `Parallel` 블록은 Kotlin Coroutines(`awaitAll`)로 병렬 실행한다.
- **상태 및 로그**: 중간 스텝별 DB 저장을 배제하고, 워크플로우 종료 시 단 1회의 비동기 `INSERT`로 최종 결과(`SUCCESS` 또는 `PARTIAL_FAILURE`) 및 감사 로그를 저장하여 지연 시간과 DB I/O를 최소화한다.
- **변수 평가**: 불변 `WorkflowContext` 맵을 체이닝 전달하며, `{User_Nickname}` 등의 템플릿 변수는 정규식 파서로 치환한다.
- **생명주기**: `DRAFT → TESTING → ACTIVE → INACTIVE → ARCHIVED`.

## 6. 기술 선택

| 영역 | 선택 | 목적 |
|---|---|---|
| 백엔드 언어/런타임 | Kotlin, JVM 21 | 공통 언어, 불변성, 타입 안정성, 가상 스레드/코루틴 호환 |
| 서비스 프레임워크 | Ktor (Netty Engine) | API, Bot, Worker의 통일된 초경량 비동기 코루틴 스택, FinOps 최적화 (RAM <200MB/pod) |
| 데이터베이스 접근 | Exposed (Kotlin SQL DSL) | 타입 세이프 SQL, 불변 data class 및 JSONB 매핑 최적화 |
| Discord 라이브러리 | Kord | Kotlin 코루틴 네이티브 비동기 Discord API 래퍼 |
| Minecraft 모듈 | Paper API 1.20.4+ / Velocity 3.3+ | 95% 이상 점유율 호환, 비동기 WSS I/O + 메인 틱 동기 디스패치 |
| 웹 대시보드 | Next.js, React, Tailwind, Shadcn UI | 실서버 운영자 온보딩 최적화 카드/위저드 폼 (2단계 2D 캔버스 확장) |
| 내부 메시징 | Redis Streams & Pub/Sub | Streams(신뢰성 작업, MAXLEN 트리밍, XACK), Pub/Sub(채팅/하트비트) |
| 영속 저장소 | PostgreSQL 16 | 관계·트랜잭션·JSONB 스냅샷·감사 데이터 |
| 인프라 오케스트레이션 | K3s (경량 Kubernetes), Helm | \$0~\$20 미만 FinOps 실현, K8s 매니페스트/Helm/자가치유 증명 |
| 자동화 & GitOps | Terraform, GitHub Actions, ArgoCD | 클라우드 직무 핵심 역량(IaC, CI/CD 파이프라인, 무중단 배포) 입증 |
| 관측성 | Prometheus, Grafana, JSON 구조화 로그 | SLO 측정 및 분산 추적 |

## 7. 비기능 요구사항

- **보안**: 
  - 외부 Minecraft Module은 WSS로만 연결하며 내부 Redis 직접 노출을 엄격히 차단한다.
  - 인스턴스별 일회성 발급 토큰, TLS 암호화, Tenant 격리, 불변 hard deny 및 allowlist 검증.
- **안전한 실패**: 
  - Ru-Beacon 장애 시 일반 Minecraft 플레이는 100% 정상 유지.
  - 2FA Enforce 차단은 명시적 opt-in으로 관리자에게 위험 사전 고지.
- **전달 보장 및 리소스 보호**:
  - Redis Streams의 `MAXLEN ~ 10000` 트리밍으로 OOM 방지.
  - 미처리 메시지는 `XAUTOCLAIM`으로 장애 복구. 자동 무한 재시도는 배제.
- **모듈 상태 관리**: 
  - WSS Ping/Pong 30초 주기, 90초 무응답 시 해당 인스턴스 stale 처리.
- **목표 SLO**: 
  - 상태 반영 90초 이내, 이벤트 처리 p95 5초, 상호작용 p95 3초, 보상 처리 p95 10초, 대시보드 API p95 1초.
- **백업 및 복구**: 
  - 백업 보존 7일, RPO 24시간, RTO 15분.
