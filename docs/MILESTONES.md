# Ru-Beacon 자율 주행 마일스톤 로드맵 (MILESTONES.md)

이 문서는 Antigravity 코딩 에이전트가 `/goal` 모드로 자율 주행할 때 마일스톤 단위로 진행 상황을 추적하고 검증하기 위한 **공식 실행 체크리스트**입니다.

새로운 대화 세션을 시작할 때마다 이 문서의 체크리스트를 기준으로 다음 미완료 마일스톤을 지정하여 주행합니다.

---

## 1. 마일스톤 실행 철칙 (Execution Rules)

1. **단일 마일스톤 원칙**: 한 번의 `/goal` 호출에는 오직 하나의 마일스톤만 실행한다. 여러 마일스톤을 한 번에 진행하지 않는다.
2. **Ponytail & Searching-Codebases 상시 적용**: [AGENTS.md](../AGENTS.md)의 The Ladder(불필요한 추상화 금지, 최단 diff 우선)와 토큰 가드(슬라이싱 읽기)를 엄격히 준수한다.
3. **Green-State Commit**: 각 세부 태스크는 테스트 통과(`BUILD SUCCESSFUL`) 시 즉시 커밋을 생성하며, 커밋 메시지는 `<type>(<scope>): <한글 요약>` 포맷을 준수한다. (우회 플래그 `--no-verify`, `--force` 사용 금지)
4. **3-Strike Rollback**: 동일 컴파일/테스트 에러가 3회 연속 실패할 경우 `git reset --hard HEAD`로 즉시 롤백하고 단순한 대안으로 재설계한다.
5. **체크박스 갱신**: 마일스톤 완료 시 본 문서의 체크박스 `[ ]`를 `[x]`로 갱신하고 최종 마일스톤 완료 커밋을 남긴다.

---

## 2. 전체 마일스톤 현황 요약 (2-Session Cycle)

각 마일스톤은 **[개발 세션: Builder]**과 **[점검 세션: Inspector]**의 2단계로 진행된다.

- [x] **마일스톤 0**: 10대 아키텍처 의사결정 확정, 저수준 상세 명세서 구축 및 하드 가드 설치
- [x] **마일스톤 1**: Gradle 멀티모듈 뼈대 구성 및 `common` 모듈 (공통 이벤트/WSS 계약)
  - [x] 개발 세션 (Builder): 멀티모듈 셋업, `common` DTO 및 직렬화 테스트
  - [x] 점검 세션 (Inspector): `/ponytail-review`, [INSPECTION_CHECKLIST.md](INSPECTION_CHECKLIST.md) 전수 점검 및 리팩터링
- [x] **마일스톤 2**: `minecraft-plugin` WSS 클라이언트 및 MockBukkit 인코드 테스트 하네스
  - [x] 개발 세션 (Builder): Paper 뼈대, WSS 클라이언트, 메인 틱 디스패처, MockBukkit 테스트
  - [x] 점검 세션 (Inspector): `/ponytail-review`, 틱 렉/스레드 안전성/재연결 점검
- [x] **마일스톤 3**: `api-service` Ingress & PostgreSQL/Redis 연동 (Testcontainers 하네스)
  - [x] 개발 세션 (Builder): Flyway V1, WSS 인그레스, Testcontainers 통합 테스트
  - [x] 점검 세션 (Inspector): `/ponytail-review`, 세션 릭/토큰 보안/동시성 점검
- [x] **마일스톤 4**: `workflow-worker` 인메모리 DAG 엔진 및 대표 템플릿 2종 검증
  - [x] 개발 세션 (Builder): Streams 컨슈머, DAG 엔진, 대표 템플릿 2종 E2E 테스트
  - [x] 점검 세션 (Inspector): `/ponytail-review`, 사이클 검증/부분 실패/멱등성 점검
- [x] **마일스톤 5**: `bot-service` Kord Discord 상호작용 및 이벤트 정규화
  - [x] 개발 세션 (Builder): Kord 봇, 인터랙션 핸들러, Fake Discord 테스트
  - [x] 점검 세션 (Inspector): `/ponytail-review`, Gateway 레이트리밋/에러 격리 점검
- [x] **마일스톤 5.5**: 분산 동시성 제어 및 인프라 하드닝 (Hardening Sprint)
  - [x] 개발 세션 (Builder): 가변 쿼터 2-Tier Redis 동시성 제어 및 Fast-Fail 구현
  - [x] 점검 세션 (Inspector): `/ponytail-review`, Redis 단절 Fallback 및 보상 롤백 감사
- [ ] **마일스톤 6**: `web-dashboard` Next.js + Shadcn UI 위저드형 대시보드
  - [ ] 개발 세션 (Builder): Next.js 위저드 워크플로우 폼 빌더 구현
  - [ ] 점검 세션 (Inspector): `/ponytail-review`, 폼 유효성/반응형/UX 점검
- [ ] **마일스톤 7**: K3s + Helm 차트 패키징 및 GitHub Actions CI/CD 파이프라인
  - [ ] 개발 세션 (Builder): Dockerfile, Helm 차트, CI 워크플로우 작성
  - [ ] 점검 세션 (Inspector): `/ponytail-review`, FinOps 리소스 리밋/보안 스캔 점검

---

## 3. 마일스톤별 상세 실행 체크리스트

### [마일스톤 1] Gradle 멀티모듈 뼈대 구성 및 `common` 모듈
> **목표**: 전체 백엔드의 기초가 되는 멀티프로젝트 빌드 환경을 구축하고, 서비스 간 공유되는 순수 도메인 이벤트/WSS 프레임 계약을 Kotlin 100% 코루틴/직렬화 스택으로 완결한다.

#### 1. 개발 세션 (Builder Session)
- [x] **Root Build 셋업**:
  - [x] `settings.gradle.kts` 구성 (모듈: `common`, `minecraft-plugin`, `api-service`, `bot-service`, `workflow-worker`)
  - [x] Root `build.gradle.kts`에 JVM 21, Kotlin 1.9+, `kotlinx.serialization`, `kotlinx.coroutines` 공통 설정
  - [x] `gradlew` 래퍼 스크립트 및 `.gitattributes` 검증
- [x] **`common` 모듈 구현**:
  - [x] [EVENT_CONTRACTS.md](EVENT_CONTRACTS.md) 기반의 `EventEnvelope` 불변 `data class` 작성
  - [x] [TRANSPORT_PROTOCOL_SPEC.md](TRANSPORT_PROTOCOL_SPEC.md) 기반의 `WebSocketFrame` (`EVENT`, `COMMAND_REQ`, `COMMAND_RES`, `PING`, `PONG`) DTO 작성
  - [x] Redis Streams 키 네임스페이스 및 상수 객체 정의
- [x] **단위 테스트 및 검증**:
  - [x] `EventEnvelope` 및 `WebSocketFrame` JSON 직렬화/역직렬화 JUnit 5 테스트 작성
  - [x] `./gradlew :common:test` 100% 통과 확인
  - [x] Green 커밋: `feat(common): 공통 이벤트 봉투 및 WebSocket 프레임 계약 구현`

#### 2. 점검 세션 (Inspector Session)
- [x] **`/ponytail-review` 복잡도 사냥**: 불필요한 추상화, DTO 1:1 단순 매퍼, 과도한 계층 제거 (`net: -N lines`)
- [x] **[INSPECTION_CHECKLIST.md](INSPECTION_CHECKLIST.md) 전수 점검**:
  - [x] `common` 모듈의 단방향 의존성 확인 (타 모듈 참조 0개 원칙)
  - [x] 직렬화 불변성, `schema_version` 호환성, 엣지 케이스 확인
- [x] **리팩터링 커밋 및 푸시**:
  - [x] `./gradlew :common:test` 통과 후 커밋: `refactor(common): 공통 모델 복잡도 다이어트 및 직렬화 엣지케이스 보강`
  - [x] 본 문서의 마일스톤 1 체크박스를 `[x]`로 완료하고 `git push origin main`


---

### [마일스톤 2] `minecraft-plugin` WSS 클라이언트 및 MockBukkit 하네스
> **목표**: Paper API 1.20.4+ 기반 플러그인을 구축하고, MockBukkit을 통해 실제 마인크래프트 서버 없이도 가상 틱 환경에서 비동기 WSS 통신과 메인 틱 동기 명령어 디스패치를 100% 자동 검증한다.

#### 1. 개발 세션 (Builder Session)
- [x] **Paper 플러그인 뼈대 및 MockBukkit 셋업**:
  - [x] `minecraft-plugin/build.gradle.kts` (Paper API 1.20.4, Java 21, MockBukkit 1.20 의존성)
  - [x] `plugin.yml` 명세 및 메인 클래스 `RuBeaconPlugin` 선언
- [x] **비동기 WSS 클라이언트 구현**:
  - [x] 전용 백그라운드 스레드 풀 기반 WebSocket 클라이언트 (Ktor Client Engine 사용)
  - [x] 30초 주기 Ping/Pong 하트비트 및 지수 백오프 재연결 루프
  - [x] 인스턴스 인증 토큰 핸드셰이크 (`X-Tenant-Id`, `X-Instance-Token`) 헤더 전송
- [x] **메인 틱 명령어 디스패처 및 이벤트 리스너**:
  - [x] WSS `COMMAND_REQ` 수신 시 `Bukkit.getScheduler().runTask()`로 메인 틱 동기 실행
  - [x] Bukkit 이벤트(`PlayerLevelUpEvent`, `PlayerAdvancementDoneEvent`) 가로채기 및 WSS 송신 큐 적재
- [x] **MockBukkit 시뮬레이션 테스트**:
  - [x] [TESTING_STRATEGY.md](TESTING_STRATEGY.md) §2.1 기반 가상 플레이어 레벨업 및 명령어 실행 테스트
  - [x] `./gradlew :minecraft-plugin:test` 100% 통과 확인
  - [x] Green 커밋: `feat(plugin): WSS 클라이언트 및 MockBukkit 기반 명령어 디스패처 구현`

#### 2. 점검 세션 (Inspector Session)
- [x] **`/ponytail-review` 복잡도 사냥**:
  - [x] `RuBeaconPlugin.kt`: `handleIncomingCommand` 중복 디스패치 제거 및 `executeCommandOnMainTick` 단일화 (`shrink`)
  - [x] `MinecraftEventListener.kt`: 중복된 `EventEnvelope` 생성 코드를 제네릭 헬퍼로 통합 (`shrink`)
  - [x] `RuBeaconPlugin.kt`: 아웃바운드 큐 50개 제한으로 메모리 누수 방지 (`FinOps OOM 방어`)
- [x] **[INSPECTION_CHECKLIST.md](INSPECTION_CHECKLIST.md) 전수 점검**:
  - [x] 하트비트 PONG 타임아웃 감지 및 좀비 WSS 소켓 세션 재연결 트리거 (`RuBeaconWebSocketClient.kt`)
  - [x] 이벤트 중복 실행 방지를 위한 비즈니스 기반 멱등키(`mc:lvl:...`, `mc:adv:...`) 전면 적용
  - [x] Paper `PlayerAdvancementDoneEvent` 및 디스플레이 필터링 엣지케이스 MockBukkit 테스트 완비
  - [x] 플러그인 비활성화 상태(`!isEnabled`) 명령어 수신 거부 가드 보강
- [x] **리팩터링 커밋 및 푸시**:
  - [x] `./gradlew test` 통과 후 커밋: `refactor(plugin): WSS 클라이언트 좀비세션 방어 및 멱등키·디스패처 단순화`
  - [x] 본 문서의 마일스톤 2 체크박스를 `[x]`로 완료하고 `git push origin main`

---

### [마일스톤 3] `api-service` Ingress & DB/Redis 연동
> **목표**: Ktor 기반 API 서버를 구축하여 외부 마인크래프트 WSS 클라이언트를 수용하고, Testcontainers를 통해 실제 PostgreSQL 16 DDL 마이그레이션과 Redis Streams 발행 파이프라인을 검증한다.

#### 1. 개발 세션 (Builder Session)
- [x] **인프라 테스트 하네스 구축**:
  - [x] `api-service/build.gradle.kts` (Ktor Server Netty, Exposed, Flyway, Testcontainers PostgreSQL/Redis)
  - [x] [TESTING_STRATEGY.md](TESTING_STRATEGY.md) §2.2 기반 `BaseIntegrationTest` 싱글톤 컨테이너 베이스 클래스 작성
- [x] **데이터베이스 계층**:
  - [x] `src/main/resources/db/migration/V1__init_schema.sql`에 [DATABASE_SCHEMA.sql](DATABASE_SCHEMA.sql) 배치 및 Flyway 마이그레이션 실행 검증
  - [x] Exposed DSL 테이블 객체 및 리포지토리 작성 (`Tenants`, `MinecraftInstances`, `AccountLinks`, `Workflows`)
- [x] **WebSocket Ingress 엔드포인트**:
  - [x] Ktor `/ws/minecraft/v1` 라우트 구현
  - [x] 인스턴스 토큰 SHA-256 검증 및 세션 레지스트리 관리
  - [x] 수신된 `EVENT` 프레임을 Redis Streams(`stream:events:{tenant_id}`, `MAXLEN ~ 10000`)로 발행
- [x] **계정 연동 & 관리 API**:
  - [x] 계정 후보 등록(Pending) 및 5분 만료 일회성 코드 발급/확증 로직
- [x] **통합 테스트 검증**:
  - [x] Testcontainers 기반 WSS 인증 및 DB/Redis 연동 통합 테스트
  - [x] `./gradlew :api-service:test` 100% 통과 확인
  - [x] Green 커밋: `feat(api): WebSocket 인그레스 서버 및 Flyway DB 연동 구현`

#### 2. 점검 세션 (Inspector Session)
- [x] **`/ponytail-review` 복잡도 사냥**:
  - [x] `SessionRegistry.kt`: 미사용 `Mutex` 필드 삭제 (`delete`)
  - [x] `InstanceAuthService.kt`: `authenticate` 쿼리 조건 결합 및 count 체크로 축약 (`shrink`)
  - [x] `MinecraftWebSocketRoute.kt`: 미사용 opcode 분기 통합 정리 (`shrink`)
  - [x] 단일 구현체 인터페이스 0개 유지 및 DTO 1:1 매퍼 배제 (`yagni`)
- [x] **[INSPECTION_CHECKLIST.md](INSPECTION_CHECKLIST.md) 전수 점검**:
  - [x] `SessionRegistry.kt` & `MinecraftWebSocketRoute.kt`: 재연결 시 이전 세션 종료의 finally 블록이 신규 세션의 ONLINE 상태를 덮어쓰지 않도록 `ConcurrentHashMap.remove(k, v)` 원자적 세션 비교 해제 적용 (분산 소켓 레이스 컨디션 차단)
  - [x] `AccountLinkService.kt`: 이미 ACTIVE 연동된 계정에 대한 타인의 탈취(requestLink) 차단 가드(`AlreadyLinked`, HTTP 409 Conflict) 적용
  - [x] `AccountLinkService.kt`: 인증 5회 연속 실패 시 1회용 코드 즉시 파기(`null`) 및 계정 잠금 처리 (무차별 대입 공격 차단)
  - [x] `MinecraftWebSocketRoute.kt`: Redis Streams 발행 실패 시 WebSocket 세션 단절 방지를 위한 예외 격리 (장애 격리 원칙)
  - [x] `WebSocketIngressAndRedisIntegrationTest.kt` & `FlywayMigrationAndRepositoryTest.kt`: 재연결 동시성 및 계정 탈취 방어 통합 테스트 추가
- [x] **리팩터링 커밋 및 푸시**:
  - [x] `./gradlew test` 통과 후 커밋: `refactor(api): WSS 재연결 세션 덮어쓰기 방어 및 계정 탈취 차단 가드 보강`
  - [x] 본 문서의 마일스톤 3 체크박스를 `[x]`로 완료하고 `git push origin main`

---

### [마일스톤 4] `workflow-worker` 인메모리 DAG 엔진
> **목표**: Ktor Worker 프로세스를 구축하여 Redis Streams 이벤트를 안전하게 소비하고, 순환 참조 검증과 코루틴 병렬 디스패치를 통해 대표 템플릿 2종(보상 수령, 일일 출석)을 완벽히 실행한다.

#### 1. 개발 세션 (Builder Session)
- [x] **Redis Streams 컨슈머 루프**:
  - [x] `worker-group` 컨슈머 그룹 생성 및 `XREADGROUP`, `XACK`, `XAUTOCLAIM` 에러 복구 루프 구현
- [x] **인메모리 DAG 엔진**:
  - [x] [WORKFLOW_ENGINE_SPEC.md](WORKFLOW_ENGINE_SPEC.md) 기반 AST 파서 및 Tarjan Cycle 검증기
  - [x] `NodeExecutor` 인터페이스 및 기본 노드 구현 (조건 분기, 마인크래프트 명령, Discord 액션 요청, 출석 예약/확정/복구)
  - [x] `coroutineScope` 기반 Parallel 블록 병렬 실행 디스패처
  - [x] 종단 1회 비동기 감사 로그(`audit_logs`) 저장기
- [x] **대표 템플릿 2종 통합 테스트**:
  - [x] 템플릿 A: 마인크래프트 레벨업 → 보상 명령 발행 흐름
  - [x] 템플릿 B: 일일 출석 보상 선착순 100명 원자적 수량 예약/확정/복구 흐름
  - [x] `./gradlew :workflow-worker:test` 100% 통과 확인
  - [x] Green 커밋: `feat(worker): 인메모리 DAG 워크플로우 디스패처 및 템플릿 실행 엔진 구현`

#### 2. 점검 세션 (Inspector Session)
- [x] **`/ponytail-review` 복잡도 사냥**:
  - [x] `RedisStreamsConsumer.kt`: 중복 entry 처리 로직 `processEntry` 단일 헬퍼로 통합 (`shrink: -20 lines`)
  - [x] `TarjanCycleDetector.kt`: 불필요한 선행 노드 초기화 루프 제거 및 `getOrPut` 통합 (`shrink: -5 lines`)
  - [x] `DagWorkflowDispatcher.kt`: `contextMutex` Context 복제 단일화 및 JSONB 정규 배열 구조화 (`shrink: -3 lines`)
  - [x] `NodeExecutor` 단일 구현체 인터페이스 0개 유지 및 DTO 1:1 매퍼 배제 (`yagni`)
- [x] **[INSPECTION_CHECKLIST.md](INSPECTION_CHECKLIST.md) 전수 점검**:
  - [x] `AttendanceReservationExecutor.kt`: 당일 동일 플레이어 중복 예약 사전 차단(`ALREADY_RESERVED`) 및 롤백 언더플로우 방어
  - [x] `NodeExecutors.kt`: Redis Streams `xadd`에 `MAXLEN ~ 10000` 트리밍 강제 적용 (FinOps OOM 방어)
  - [x] `DagWorkflowDispatcher.kt`: 코루틴 `CancellationException` 명시적 재전파로 라이프사이클 격리 보장
  - [x] `WorkflowTemplateIntegrationTest.kt`: 중복 예약 차단 및 언더플로우 방어 통합 테스트 추가
- [x] **리팩터링 커밋 및 푸시**:
  - [x] `./gradlew test` 통과 후 커밋: `refactor(worker): DAG 디스패처 안전 가드 보강 및 출석 중복 예약·FinOps 트리밍 방어`
  - [x] 본 문서의 마일스톤 4 체크박스를 `[x]`로 완료하고 `git push origin main`

---

### [마일스톤 5] `bot-service` Discord 인터랙션
> **목표**: Kord 라이브러리 기반으로 Discord Gateway 이벤트를 수신하고, 버튼/모달/슬래시 명령을 공통 이벤트로 정규화하여 Redis Streams로 발행하며, Ephemeral 응답을 렌더링한다.

#### 1. 개발 세션 (Builder Session)
- [x] **Kord 봇 클라이언트 셋업**:
  - [x] `bot-service/build.gradle.kts` (Kord 0.14.0, coroutines, jedis, testcontainers)
  - [x] 봇 토큰 로드 및 Gateway 수명주기 관리 (`RuBeaconBot.kt`, `DiscordBotConfig.kt`)
- [x] **이벤트 정규화 및 인터랙션 핸들러**:
  - [x] 슬래시 명령어(`/verify`, `/attend`) 핸들러 및 글로벌 커맨드 등록
  - [x] 연동 모달 및 보상 수령 버튼 클릭 가로채기 -> `EventEnvelope` 변환 후 Streams 발행 (`DiscordEventNormalizer.kt`, `DiscordEventPublisher.kt`)
  - [x] Ephemeral 안내 메시지 템플릿 렌더러 (`DiscordResponseRenderer.kt`)
- [x] **Fake Discord 모킹 및 통합 테스트**:
  - [x] 인터랙션 수신 시 올바른 이벤트 봉투가 Redis Streams로 전송되는지 검증 (`DiscordEventPublisherTest.kt`)
  - [x] `stream:discord:actions` 비동기 메시지 수신 및 ACK 검증 (`DiscordActionConsumerTest.kt`)
  - [x] `./gradlew :bot-service:test` 100% 통과 확인
  - [x] Green 커밋: `feat(bot): Kord 기반 Discord 인터랙션 이벤트 핸들러 구현`

#### 2. 점검 세션 (Inspector Session)
- [x] **`/ponytail-review` 복잡도 사냥**:
  - [x] 단일 구현체 인터페이스 0개 유지 및 DTO 1:1 매퍼 배제 (`yagni`)
  - [x] Kord Event에서 직접 `EventEnvelope`로 1단계 정규화 (`shrink`)
- [x] **[INSPECTION_CHECKLIST.md](INSPECTION_CHECKLIST.md) 전수 점검**:
  - [x] `RuBeaconBot.kt`: 슬래시/버튼/모달 처리 시 `deferEphemeralResponse()` 단계의 네트워크 타임아웃 예외를 try-catch로 감싸 Gateway 연결 단절 방어 (장애 격리)
  - [x] `RuBeaconBot.kt`: `/verify` 명령 시 공백 코드에 대한 선제적 유효성 검증 가드 추가
  - [x] `DiscordActionConsumer.kt`: 존재하지 않는 채널 등 Discord API 영구 오류(`RestRequestException`) 발생 시 poison pill로 격리 및 XACK 처리 (소비 루프 무한 블로킹 차단)
  - [x] `DiscordActionConsumerTest.kt`: 채널 ID 누락 등 비정상 메시지 유입 시 크래시 없는 정상 격리 회귀 테스트 추가
- [x] **리팩터링 커밋 및 푸시**:
  - [x] `./gradlew test` 통과 후 커밋: `refactor(bot): Discord Gateway Defer 예외 격리 및 Action 컨슈머 Poison Pill 방어 보강`
  - [x] 본 문서의 마일스톤 5 체크박스를 `[x]`로 완료하고 `git push origin main`

---

### [마일스톤 5.5] 분산 동시성 제어 및 인프라 하드닝 (Hardening Sprint)
> **목표**: 일일 선착순 보상 쿼터에 1차 Redis 인메모리 빠른 탈락(Admission Control)과 2차 PostgreSQL 최종 영속화 및 보상 트랜잭션(Dual-Write 롤백)을 적용하여 트래픽 폭증 시 RDBMS 커넥션 풀을 보호하고 무중단 Graceful Fallback을 구축한다.

#### 1. 개발 세션 (Builder Session)
- [x] **가변 쿼터 2-Tier Redis 동시성 제어 (`workflow-worker`)**:
  - [x] `AttendanceReservationExecutor.kt`: `inputs["total_limit"]` 기반 동적 가변 쿼터(N) 수용
  - [x] Redis Lua Script 기반 1차 Admission Control: 당일 중복 참여 및 남은 정원 0ms 메모리 판별
  - [x] 48시간 만료 TTL 적용으로 메모리 누수 방지
  - [x] N번째 초과 요청 및 중복 요청 시 DB 커넥션 호출 0회 즉시 Fast-Fail
  - [x] 2차 관문 통과 요청만 DB 트랜잭션 진입, DB 장애 시 Redis 선점 원복(보상 트랜잭션/Dual-Write 롤백)
  - [x] `jedis == null` 또는 Redis 연결 장애 시 기존 PostgreSQL 조건부 UPDATE 자동 Graceful Fallback
- [x] **분산 추적(Correlation ID) 관측성 로깅 보강**:
  - [x] `EventEnvelope.correlationId` 기반 SLF4J 경량 로깅 포맷 보강 (`DagWorkflowDispatcher`, `RedisStreamsConsumer`, `NodeExecutors`)
- [x] **통합 테스트 검증 (`AttendanceConcurrencyIntegrationTest.kt`)**:
  - [x] 동시성 경합 검증: 30개 동시 요청 시 정확히 N(5)개만 1차 통과 및 DB 저장 성공
  - [x] Fast-Fail 검증: 초과 및 중복 요청 시 Redis 차단 및 DB 변동 0건 검증
  - [x] 중복 차단 검증: 동일 플레이어 당일 재요청 즉시 차단 검증
  - [x] 보상 트랜잭션 검증: DB 저장 실패 시 Redis 선점 카운트 정상 원복 검증
  - [x] Graceful Fallback 검증: `jedis == null` 상태에서도 안전 동작 검증
  - [x] `./gradlew test` 전체 모듈 100% 통과 확인
  - [x] Green 커밋: `feat(worker): 가변 쿼터 2-Tier Redis Admission Control 및 보상 롤백 동시성 제어 구현`

#### 2. 점검 세션 (Inspector Session)
- [x] **`/ponytail-review` 복잡도 사냥**:
  - [x] Lua Script 복잡도 및 불필요한 키/파라미터 정리
  - [x] 단일 구현체 인터페이스 0개 유지 및 DTO 1:1 매퍼 배제 (`yagni`)
- [x] **[INSPECTION_CHECKLIST.md](INSPECTION_CHECKLIST.md) 전수 점검**:
  - [x] Redis 다운/타임아웃 시 PostgreSQL 원자적 UPDATE 전환 무중단 연속성 검증
  - [x] 2-Tier Dual-Write 보상 트랜잭션 실패 시 데이터 불일치 방어 가드 감사
  - [x] `correlationId` 분산 추적 로깅 전파 누락 구간 점검
- [x] **리팩터링 커밋 및 푸시**:
  - [x] `./gradlew test` 통과 후 커밋: `refactor(worker): 2-Tier Redis 동시성 제어 엣지케이스 방어 및 점검 완료`
  - [x] 본 문서의 마일스톤 5.5 점검 체크박스를 `[x]`로 완료하고 `git push origin main`

---

### [마일스톤 6] `web-dashboard` Next.js 위저드 폼 (고객 유치 & SaaS 운영)
> **목표**: 실제 마인크래프트 커뮤니티 운영자가 사용할 Next.js + Shadcn UI 기반 대시보드를 구축하여 초기 Discord 서버 설정 및 카드형 위저드 워크플로우 빌더를 제공한다.

- [ ] **Next.js 프로젝트 셋업**:
  - [ ] `web-dashboard/` (Next.js 14 App Router, Tailwind CSS, Shadcn UI, TypeScript)
- [ ] **온보딩 & 위저드 워크플로우 폼**:
  - [ ] Discord 서버/역할/채널 연동 및 인스턴스 인증 토큰 발급 UI
  - [ ] 3단계 카드형 워크플로우 빌더 (1단계: 트리거 선택 → 2단계: 조건 필터 → 3단계: 실행 액션)
  - [ ] 복잡한 그래프 캔버스 라이브러리 배제, 직관적 Form State 기반 린(Lean) UI 확립
  - [ ] API Service REST API 연동 및 워크플로우 JSONB 저장/배포/롤백 연동
- [ ] **빌드 및 린트 검증**:
  - [ ] `pnpm build` 또는 `npm run build` 성공 확인
  - [ ] Green 커밋: `feat(dashboard): Next.js 위저드형 워크플로우 빌더 UI 구현`

---

### [마일스톤 7] 인프라 K3s & CI/CD·관측성 파이프라인 (클라우드/SRE 포트폴리오 정점)
> **목표**: 서비스 3종의 경량 컨테이너화와 Helm 차트를 패키징하고, KEDA 이벤트 기반 오토스케일링, Prometheus/Grafana 관측성, k6 분산 부하 테스트 벤치마크를 완비하여 클라우드/인프라 직무 역량을 완벽히 증명한다.

- [ ] **컨테이너화 및 보안**:
  - [ ] `api-service`, `bot-service`, `workflow-worker` 멀티스테이지 Dockerfile 작성 (Eclipse Temurin 21 JRE, Non-root 사용자 격리)
- [ ] **관측성(Observability) 엔드포인트**:
  - [ ] Ktor Micrometer Prometheus 레지스트리 연동 및 `/metrics` 엔드포인트 노출 (JVM Heap, Coroutine Dispatcher, Redis Connection Pool, HTTP latency)
  - [ ] Grafana 대시보드 명세 (`deploy/observability/grafana-dashboard.json`) 작성
- [ ] **Helm 차트 패키징 & 클라우드 네이티브 설계**:
  - [ ] `deploy/helm/ru-beacon` 차트 작성 (Deployment, Service, Ingress, ConfigMap, Secret, Probe)
  - [ ] Liveness/Readiness Probe, 리소스 Request/Limit, PodDisruptionBudget 설정
  - [ ] **KEDA ScaledObject**: Redis Streams 컨슈머 랙(`stream:events:*` lag > 100) 기반 `workflow-worker` 파드 자동 증설(HPA) 정의
  - [ ] Prometheus Operator `ServiceMonitor` 매니페스트 포함
- [ ] **k6 분산 동시성 부하 테스트 & 성능 리포트**:
  - [ ] `deploy/load-test/k6-concurrency-benchmark.js` 작성
  - [ ] 선착순 출석 이벤트 1,000 RPS 동시 요청 시 2-Tier Redis Admission Control의 Fast-fail 0ms 및 DB 커넥션 풀 안정성 실측 검증
- [ ] **GitHub Actions CI 워크플로우 & 하드 가드**:
  - [ ] `.github/workflows/ci.yml` 작성 (전체 Gradle 테스트, 도커 빌드 검증, Helm lint)
  - [ ] `.githooks/pre-commit`에 `./gradlew test` 자동 검증 연결
  - [ ] Green 커밋: `ci: Helm 차트 패키징·KEDA 오토스케일링 및 관측성 CI/CD 파이프라인 구축`

