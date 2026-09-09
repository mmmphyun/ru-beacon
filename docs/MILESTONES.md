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
- [ ] **마일스톤 3**: `api-service` Ingress & PostgreSQL/Redis 연동 (Testcontainers 하네스)
  - [ ] 개발 세션 (Builder): Flyway V1, WSS 인그레스, Testcontainers 통합 테스트
  - [ ] 점검 세션 (Inspector): `/ponytail-review`, 세션 릭/토큰 보안/동시성 점검
- [ ] **마일스톤 4**: `workflow-worker` 인메모리 DAG 엔진 및 대표 템플릿 2종 검증
  - [ ] 개발 세션 (Builder): Streams 컨슈머, DAG 엔진, 대표 템플릿 2종 E2E 테스트
  - [ ] 점검 세션 (Inspector): `/ponytail-review`, 사이클 검증/부분 실패/멱등성 점검
- [ ] **마일스톤 5**: `bot-service` Kord Discord 상호작용 및 이벤트 정규화
  - [ ] 개발 세션 (Builder): Kord 봇, 인터랙션 핸들러, Fake Discord 테스트
  - [ ] 점검 세션 (Inspector): `/ponytail-review`, Gateway 레이트리밋/에러 격리 점검
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

- [ ] **인프라 테스트 하네스 구축**:
  - [ ] `api-service/build.gradle.kts` (Ktor Server Netty, Exposed, Flyway, Testcontainers PostgreSQL/Redis)
  - [ ] [TESTING_STRATEGY.md](TESTING_STRATEGY.md) §2.2 기반 `BaseIntegrationTest` 싱글톤 컨테이너 베이스 클래스 작성
- [ ] **데이터베이스 계층**:
  - [ ] `src/main/resources/db/migration/V1__init_schema.sql`에 [DATABASE_SCHEMA.sql](DATABASE_SCHEMA.sql) 배치 및 Flyway 마이그레이션 실행 검증
  - [ ] Exposed DSL 테이블 객체 및 리포지토리 작성 (`Tenants`, `MinecraftInstances`, `AccountLinks`, `Workflows`)
- [ ] **WebSocket Ingress 엔드포인트**:
  - [ ] Ktor `/ws/minecraft/v1` 라우트 구현
  - [ ] 인스턴스 토큰 SHA-256 검증 및 세션 레지스트리 관리
  - [ ] 수신된 `EVENT` 프레임을 Redis Streams(`stream:events:{tenant_id}`, `MAXLEN ~ 10000`)로 발행
- [ ] **계정 연동 & 관리 API**:
  - [ ] 계정 후보 등록(Pending) 및 5분 만료 일회성 코드 발급/확증 로직
- [ ] **통합 테스트 검증**:
  - [ ] Testcontainers 기반 WSS 인증 및 DB/Redis 연동 통합 테스트
  - [ ] `./gradlew :api-service:test` 100% 통과 확인
  - [ ] Green 커밋: `feat(api): WebSocket 인그레스 서버 및 Flyway DB 연동 구현`

---

### [마일스톤 4] `workflow-worker` 인메모리 DAG 엔진
> **목표**: Ktor Worker 프로세스를 구축하여 Redis Streams 이벤트를 안전하게 소비하고, 순환 참조 검증과 코루틴 병렬 디스패치를 통해 대표 템플릿 2종(보상 수령, 일일 출석)을 완벽히 실행한다.

- [ ] **Redis Streams 컨슈머 루프**:
  - [ ] `worker-group` 컨슈머 그룹 생성 및 `XREADGROUP`, `XACK`, `XAUTOCLAIM` 에러 복구 루프 구현
- [ ] **인메모리 DAG 엔진**:
  - [ ] [WORKFLOW_ENGINE_SPEC.md](WORKFLOW_ENGINE_SPEC.md) 기반 AST 파서 및 Tarjan Cycle 검증기
  - [ ] `NodeExecutor` 인터페이스 및 기본 노드 구현 (조건 분기, 마인크래프트 명령, Discord 액션 요청)
  - [ ] `coroutineScope` 기반 Parallel 블록 병렬 실행 디스패처
  - [ ] 종단 1회 비동기 감사 로그(`audit_logs`) 저장기
- [ ] **대표 템플릿 2종 통합 테스트**:
  - [ ] 템플릿 A: 마인크래프트 레벨업 → 보상 명령 발행 흐름
  - [ ] 템플릿 B: 일일 출석 보상 선착순 100명 원자적 수량 예약/확정/복구 흐름
  - [ ] `./gradlew :workflow-worker:test` 100% 통과 확인
  - [ ] Green 커밋: `feat(worker): 인메모리 DAG 워크플로우 디스패처 및 템플릿 실행 엔진 구현`

---

### [마일스톤 5] `bot-service` Discord 인터랙션
> **목표**: Kord 라이브러리 기반으로 Discord Gateway 이벤트를 수신하고, 버튼/모달/슬래시 명령을 공통 이벤트로 정규화하여 Redis Streams로 발행하며, Ephemeral 응답을 렌더링한다.

- [ ] **Kord 봇 클라이언트 셋업**:
  - [ ] `bot-service/build.gradle.kts` (Kord, Ktor, kotlinx-coroutines)
  - [ ] 봇 토큰 로드 및 Gateway 수명주기 관리
- [ ] **이벤트 정규화 및 인터랙션 핸들러**:
  - [ ] 슬래시 명령어(`/verify`, `/attend` 등) 핸들러
  - [ ] 연동 모달 및 보상 수령 버튼 클릭 가로채기 -> `EventEnvelope` 변환 후 Streams 발행
  - [ ] Ephemeral 안내 메시지 템플릿 렌더러
- [ ] **Fake Discord 모킹 테스트**:
  - [ ] 인터랙션 수신 시 올바른 이벤트 봉투가 Redis Streams로 전송되는지 검증
  - [ ] `./gradlew :bot-service:test` 100% 통과 확인
  - [ ] Green 커밋: `feat(bot): Kord 기반 Discord 인터랙션 이벤트 핸들러 구현`

---

### [마일스톤 6] `web-dashboard` Next.js 위저드 폼
> **목표**: 실제 마인크래프트 커뮤니티 운영자가 사용할 Next.js + Shadcn UI 기반 대시보드를 구축하여 초기 Discord 서버 설정 및 카드형 위저드 워크플로우 빌더를 제공한다.

- [ ] **Next.js 프로젝트 셋업**:
  - [ ] `web-dashboard/` (Next.js 14 App Router, Tailwind CSS, Shadcn UI, TypeScript)
- [ ] **온보딩 & 위저드 워크플로우 폼**:
  - [ ] Discord 서버/역할/채널 드롭다운 설정 화면
  - [ ] 3단계 카드형 워크플로우 빌더 (1단계: 트리거 선택 → 2단계: 조건 필터 → 3단계: 실행 액션)
  - [ ] API Service REST API 연동 및 워크플로우 JSONB 저장/배포/롤백 연동
- [ ] **빌드 및 린트 검증**:
  - [ ] `pnpm build` 또는 `npm run build` 성공 확인
  - [ ] Green 커밋: `feat(dashboard): Next.js 위저드형 워크플로우 빌더 UI 구현`

---

### [마일스톤 7] 인프라 K3s & CI/CD 파이프라인 (포트폴리오 증거)
> **목표**: 서비스 3종의 Dockerfile과 Helm 차트를 작성하고, GitHub Actions CI/CD를 구축하여 클라우드 직무 핵심 역량을 완벽히 증명한다.

- [ ] **컨테이너화**:
  - [ ] `api-service`, `bot-service`, `workflow-worker` 멀티스테이지 Dockerfile 작성 (Eclipse Temurin 21 JRE, 경량 이미지)
- [ ] **Helm 차트 패키징**:
  - [ ] `deploy/helm/ru-beacon` 차트 작성 (Deployment, Service, Ingress, ConfigMap, Secret, Probe)
  - [ ] Liveness/Readiness Probe 및 리소스 리밋 설정
- [ ] **GitHub Actions CI 워크플로우**:
  - [ ] `.github/workflows/ci.yml` 작성 (전체 Gradle 테스트, 도커 빌드 검증)
- [ ] **로컬 `pre-commit` 하드 가드 연동**:
  - [ ] `.githooks/pre-commit`에 `./gradlew test` 자동 검증 연결
  - [ ] Green 커밋: `ci: Helm 차트 패키징 및 GitHub Actions CI 워크플로우 구축`
