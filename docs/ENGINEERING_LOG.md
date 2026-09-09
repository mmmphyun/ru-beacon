# Ru-Beacon 포트폴리오 엔지니어링 일지 (ENGINEERING_LOG.md)

이 문서는 각 마일스톤의 개발 및 점검 세션이 끝날 때마다, **에이전트와 엔지니어가 인터뷰(Q&A)를 통해 실제 고민과 트레이드오프, 엣지케이스 대응 과정을 기록하는 공식 기술 포트폴리오 원천**입니다.

AI가 일방적으로 미사여구를 지어내지 않고, **실제 엔지니어의 의사결정 근거와 교정 과정**을 솔직하게 기록하여 기술 면접관에게 진정성 있는 엔지니어링 역량을 증명합니다.

---

## 1. 인터뷰 기반 로깅 프로토콜 (Interview-driven Logging)

1. **시점**: 각 마일스톤의 [점검 세션(Inspector Session)] 마무리 직전 실행.
2. **진행 방식**:
   - 에이전트는 점검 diff와 버그 수정 내역을 분석한 후, 엔지니어에게 **가장 중요했던 설계 결정 및 고민 2~3가지**를 질문한다.
   - 엔지니어가 자신의 생각과 판단 이유를 답변한다.
   - 에이전트는 엔지니어의 실제 답변을 바탕으로 본 문서의 해당 마일스톤 섹션을 작성한다.
3. **4대 필수 기록 항목**:
   - **아키텍처 트레이드오프**: 채택한 방식 vs 기각한 대안의 이유 (비용, 지연시간, 복잡도)
   - **AI 통제 및 교정 (Human-in-the-Loop)**: 에이전트의 환각/오버엔지니어링을 엔지니어가 어떻게 감지하고 제어했는가
   - **도출된 엣지케이스 & 카오스 실험**: WSS 단절, 틱 렉, 동시성 광클 시의 시스템 거동과 방어 테스트
   - **정량적 엔지니어링 수치**: 테스트 커버리지, 라인 감축량(net: -N lines), 리소스 점유율(RAM MB)

---

## 2. 마일스톤별 엔지니어링 기록

### [마일스톤 0] 설계 거버넌스 및 하드 가드 구축 (완료)

#### 1. 아키텍처 트레이드오프 & 핵심 의사결정
- **외부 마인크래프트 통신: Redis 직접 노출 폐기 및 WebSocket(WSS) 단일화**
  - *기각한 대안*: 외부 마인크래프트 플러그인이 중앙 Redis에 직접 TCP 접속하는 초기 설계.
  - *판단 근거*: 외부 사용자 서버에 Redis 포트와 패스워드가 노출되면 테넌트 간 데이터 도청 및 Redis 커맨드 인젝션 보안 참사 발생. API Service가 외부 WSS 인그레스를 수용하고 Redis는 VPC 내부 서비스 버스로만 격리.
- **백엔드 기술 스택: 100% Kotlin Coroutine 스택 (Ktor + Exposed + Kord)**
  - *기각한 대안*: 국내 채용 표준인 Spring Boot 3 + JPA.
  - *판단 근거*: 서비스 3종(API, Bot, Worker)에 각각 Spring을 띄우면 최소 2GB 이상의 RAM을 소비하여 소형 VM에서 OOM 발생. Ktor와 Exposed의 경량성으로 컨테이너당 RAM을 100~200MB 미만으로 억제하여 OCI 무료 티어/소형 VM 1대에서 3개 서비스를 여유롭게 구동하는 극대화된 FinOps 실현.
- **워크플로우 저장: 하이브리드 모델 (Metadata Columns + Versioned JSONB)**
  - *기각한 대안*: 노드, 엣지, 설정을 4개 테이블로 쪼개는 완전 정규화 RDBMS.
  - *판단 근거*: 워크플로우는 그래프 전체가 하나의 애그리거트(Aggregate)이며, 원자적 롤백과 배포를 위해 단일 JSONB 스냅샷 저장이 우수함. 에이전트가 복잡한 다중 테이블 외래키 트랜잭션 에러를 발생시키는 위험도 원천 차단.

#### 2. AI 통제 및 거버넌스 (Human-in-the-Loop)
- **소프트 가드의 한계 인식 및 물리적 하드 가드(`.githooks`) 구축**:
  - 프롬프트로만 "컨벤션을 지켜라"고 지시하면 장기 주행 시 100% 망각 및 우회가 발생함을 인지.
  - 로컬 `.githooks/commit-msg`와 `pre-push`를 구축하여 커밋 메시지 포맷 위반 시 Exit 1로 물리적 차단.
- **1인 개발 트렁크 기반 개발(Trunk-Based Development) 확정**:
  - 에이전트가 매번 브랜치를 따고 PR 자가 머지 루프를 돌리는 오버헤드를 배제하고, `main` 브랜치에 직접 원자적 Green 커밋을 쌓는 실무 린(Lean) 프로세스 수립.
- **점검 세션의 과잉 수정(Over-polishing) 부작용 차단**:
  - 기존 3회 반복 점검 관행에서 LLM이 "뭐라도 고쳐야 한다"는 강박으로 멀쩡한 코드를 오버엔지니어링으로 회귀시키는 역효과(Negative ROI)를 도출.
  - "1회 완결형 집중 점검 + 대규모 변경 시 조건부 스모크 체크"로 프로세스를 합리화.

#### 3. 도출된 엣지케이스 & 방어 체계
- **Thundering Herd 방어**: 외부 마인크래프트 서버 연결 단절 시 지수 백오프(Exponential Backoff)와 Jitter를 적용해 동시 재연결 폭풍 차단.
- **Bukkit 메인 틱 안전성**: 비동기 WSS 스레드에서 Bukkit API 직접 호출 시 서버 크래시가 발생하므로, 모든 인바운드 명령은 `BukkitScheduler.runTask`로 메인 틱 루프에 동기화 디스패치 강제.
- **Redis OOM 방어**: 모든 Streams `XADD`에 `MAXLEN ~ 10000` 트리밍을 강제하여 느린 컨슈머 상황에서도 메모리 고갈 방지.

#### 4. 정량적 엔지니어링 지표
- **아키텍처 문서화**: 저수준 스펙 4종 신규 구축 (DDL, Transport, Workflow AST, Test Harness).
- **인프라 비용 추정**: 월 \$70~\$150 이상(AWS 다중 인스턴스) → 월 \$0~\$20 미만(OCI Free Tier / K3s)으로 85% 이상 절감 설계.

---

### [마일스톤 1] Gradle 멀티모듈 뼈대 및 `common` 모듈 (완료)

#### 1. 아키텍처 트레이드오프 & 핵심 의사결정
- **이벤트 페이로드: `sealed class` (컴파일 타임 안전성) vs `JsonObject` (분산 결합도 격리)**
  - *기각한 대안 (`sealed class` 다형성 모놀리스)*:
    - `common` 모듈에 모든 이벤트 DTO(`PlayerLevelUpPayload` 등)를 정의하고 `sealed class` 기반 다형성 직렬화를 적용하는 방식.
    - *기각으로 잃은 이익*: 컴파일 타임에 `when (payload)` 전수 검사(Exhaustiveness)를 통해 신규 이벤트 누락을 빌드 시점에 차단할 수 없음. IDE 자동완성 및 필드명 원클릭 리팩터링의 편의성을 포기함. 1-Pass 단일 스트림 역직렬화 대비 2-Pass 파싱(`JSON` → `JsonObject` → 구체 DTO)으로 인한 중간 객체 할당 및 GC 오버헤드 감수.
  - *채택한 이유 (`JsonObject` + `decodePayload<T>()` 인라인 디코딩)*:
    - **배포 독립성(Decoupled Release Cycle)**: 마인크래프트 플러그인, 디스코드 봇, 워커는 릴리즈 주기가 상이함. 신규 인게임 이벤트가 추가될 때마다 `common`을 수정하고 전체 서비스를 재빌드·재배포해야 하는 배포 병목을 원천 차단.
    - **바이트코드 오염 방지**: API Ingress 서버는 라우팅과 테넌트 격리만 담당할 뿐 페이로드 내부 스키마를 알 필요가 없음. Ingress 메모리에 무의미한 도메인 DTO 클래스를 로드하지 않음.
    - **위험 통제 방안**: `schema_version` 필드 및 각 컨슈머 서비스 단위의 단위/계약 테스트(Contract Test)로 런타임 타입 안전성을 보장.
- **시간 표현 포맷의 계층별 이원화: WSS `timestamp: Long` vs EventEnvelope `occurred_at: String (ISO-8601 UTC)`**
  - *기각한 대안 1 (전역 `Long` 에포크 밀리초 단일화)*:
    - *기각으로 잃은 이익*: 모든 계층의 시간 타입이 일치하여 계층 간 변환 오버헤드가 0이며, 패킷 크기가 8바이트로 극소화되고 단순 산술 대소 비교(`t1 > t2`)가 용이함.
    - *기각한 이유*: PostgreSQL DB 직접 조회 및 운영 로그 검색 시 `1755734400000` 같은 숫자는 인간이 즉시 시각을 식별할 수 없어 `TO_TIMESTAMP()` 변환 쿼리가 필수적이며, 타임존 정보가 소실되어 다중 리전 운영 시 심각한 가독성/감사 편의성 저하를 초래함.
  - *기각한 대안 2 (전역 `String` ISO-8601 UTC 단일화)*:
    - *기각으로 잃은 이익*: 패킷 덤프나 로그만 봐도 사람이 즉시 시각(`2026-08-21T00:00:00Z`)을 판독할 수 있고 시스템 전역에서 단일 문자열 포맷만 다루는 일관성 확보.
    - *기각한 이유*: 초당 수십~수백 건 오가는 고빈도 WSS Ping/Pong 하트비트와 RTT 산술 연산마다 문자열 파싱(`Instant.parse()`) 및 포맷팅 객체 할당 오버헤드가 발생하여 네트워크 계층의 FinOps/지연시간 최적화를 저해함.
  - *채택한 이유 (전송=Long, 도메인/영속화=ISO-8601 String 계층별 분리)*:
    - 네트워크 전송 계층은 성능/비용(고속 RTT 산술, 8B 패킷 최소화)에 집중하고, 비즈니스/감사 계층은 운영성/가독성(JSONB 쿼리 용이성, 명시적 UTC)에 집중하도록 책임을 분리.
  - *위험 통제 방안*:
    - API Ingress 경계에서 WSS 프레임을 `EventEnvelope`로 변환할 때 단 1회 `Instant.ofEpochMilli(frame.timestamp).toString()`으로 명시적 변환하여 내부로 전파하며, 서비스 내부 계층 간 중복 변환 발생을 원천 차단.

#### 2. AI 통제 및 거버넌스 (Human-in-the-Loop)
- **규칙 맹종 인터뷰 교정**:
  - 에이전트가 "왜 포니테일 규칙대로 외부 라이브러리를 안 썼는가?"와 같은 자명하고 형식적인 질문을 던졌을 때, 엔지니어가 질문의 무가치함을 지적하고 "선택하지 않은 대안을 통해 얻을 수 있었던 이익과 잃은 대가"를 중심으로 질문의 방향을 전환.
  - LLM의 자기만족적 확인 편향을 깨고, 실질적인 아키텍처 양방향 트레이드오프(결합도 vs 타입 안전성)를 심층 기록하도록 유도.
- **포니테일 기반 복잡도 사냥 (`/ponytail-review`)**:
  - 모듈/테스트마다 제각각 생성되던 `Json { ignoreUnknownKeys = true }` 인스턴스를 전역 싱글톤 `RuBeaconJson.default`로 단일화하여 파편화 방지.
  - `WebSocketFrame`의 `event()`, `commandReq()`, `commandRes()` 내부의 중복 JSON 인코딩 보일러플레이트를 인라인 제네릭 팩토리 `of<T>()`로 통합 (`net: -18 lines`).

#### 3. 도출된 엣지케이스 & 방어 체계
- **외부 경계 입력값 검증 분리 (`validate()`)**:
  - 생성자 `init` 블록 대신 명시적 `validate()` 함수를 제공하여 네트워크 인그레스가 원시 프레임(Raw Frame) 파싱 후 메타데이터(`trace_id`, `tenant_id`)를 먼저 로깅/식별할 수 있도록 단계 분리.
  - `eventId`, `tenantId`, `idempotencyKey` 공백 검증 및 미정의 `source` 유입 차단.
- **도메인 조건부 제약 강제**:
  - `source == "minecraft"` 조건 시 `minecraft_network_id` 누락을 즉시 차단하여 테넌트-네트워크 매핑 누락 방지.
- **전진 호환성 (Schema Evolution)**:
  - `ignoreUnknownKeys = true` 설정을 통해 신규 필드(`future_field`, `client_version`)가 포함된 페이로드가 유입되어도 기존 소비자가 크래시 없이 안전하게 무시함을 회귀 테스트로 검증.

#### 4. 정량적 엔지니어링 지표
- **테스트 커버리지**: 직렬화 및 엣지케이스 테스트 8종 신규 추가 (JUnit 5, 100% 통과).
- **코드 다이어트**: 중복 JSON 설정 및 수동 매핑 제거로 `net: -18 lines` 절감.
- **빌드 속도**: Gradle 멀티모듈 캐시 기반 테스트 6초 이내 완료 (`BUILD SUCCESSFUL in 6s`).

---

### [마일스톤 2] `minecraft-plugin` WSS 클라이언트 및 MockBukkit 하네스 (완료)

#### 1. 아키텍처 트레이드오프 & 핵심 의사결정
- **고객 게임 서버 무영향 원칙(Guest Zero-Impact Principle) 확립**:
  - *엔지니어링 철학*: Ru-Beacon 플러그인은 고객이 운영하는 마인크래프트 서버에 기생(Guest)하는 소프트웨어이므로, 서비스 연동 장애나 네트워크 이상이 고객의 게임 플레이(TPS 20 유지, 틱 루프, 힙 메모리)에 0.001%의 악영향도 주어서는 안 됨.
- **아웃바운드 이벤트 버퍼링: 고정 슬라이딩 링 버퍼(선택) vs 로컬 디스크 WAL/SQLite 영속화(기각)**:
  - *기각한 대안 (로컬 디스크 WAL / 임베디드 SQLite)*:
    - *기각으로 잃은 이익*: 네트워크 장기 단절 상황에서도 발생한 모든 인게임 이벤트(레벨업, 발전과제)를 100% 무손실(At-Least-Once)로 보존하여 디스코드 알림 및 보상 지급의 완전성을 확보할 수 있음.
    - *기각한 이유 (엔지니어 판단)*: 고객 서버의 디스크 I/O와 힙 메모리를 점유하고 OOM 위험을 고객에게 전가하는 것은 부적절함. 고객 서버 측 네트워크 단절은 플랫폼이 디스크를 긁어가며 책임질 영역이 아니며, 플러그인은 메모리 점유를 50개(수십 KB)로 엄격히 통제하고 초과분을 폐기(Drop-oldest)하여 고객 서버의 생존성을 절대적으로 우선시함. 대신 중앙 인프라의 가용성을 극대화하여 WSS 단절 자체를 최소화하는 것이 올바른 책임 분계선임.
- **명령어 디스패치 동기화: 코루틴 일대일 즉시 브릿징(선택) vs 틱당 밸브 제어형 큐(기각 및 상류 보완 정책 수립)**:
  - *기각한 대안 (복합 틱당 배치 큐 & 요청-응답 매핑 머신)*:
    - *기각으로 잃은 이익*: 메인 틱 루프에 틱당 최대 N개 실행 한도를 두어 명령 폭주 시에도 마인크래프트 메인 스레드를 완벽히 보호.
    - *기각한 이유*: 플러그인 내부에 비동기 요청 ID 매핑 레지스트리(`ConcurrentHashMap`), 타임아웃 추적기, 스케줄러 밸브 등 수백 줄의 복잡한 상태 머신을 구축해야 하며, 불필요한 틱 지연(Queueing latency)이 발생함.
  - *채택한 구조 및 틱 방어 정책 (즉시 브릿징 + 중앙 서버 Throttling)*:
    - 플러그인은 `suspendCancellableCoroutine` + `BukkitScheduler.runTask`로 30줄 미만의 가장 단순하고 즉각적인 실행 경로를 유지함.
    - *엔지니어의 틱 방어 통찰*: "선택지 A를 가더라도 고객 서버 틱 방어 로직이 반드시 전제되어야 한다." 고객 서버에 수백 개의 명령 폭탄이 쏟아지는 위험은 플러그인을 무겁게 만들어 방어할 것이 아니라, **중앙 `api-service` 및 `workflow-worker`에서 테넌트/인스턴스별 디스패치 속도 제한(Token Bucket Rate Limiting, 초당 최대 10~20 cmd)을 걸어 고객 서버 메인 틱 루프를 상류(Upstream)에서 선제 보호**하는 방어 아키텍처를 확정함.

#### 2. AI 통제 및 거버넌스 (Human-in-the-Loop)
- **고객 중심 엔지니어링 철학으로 기술적 줄타기 정리**:
  - 에이전트가 "UX와 안정성 간의 양방향 트레이드오프"로 문제를 던졌을 때, 엔지니어가 "내 서비스로 인해 고객이 피해를 보는 것은 좋은 엔지니어링이 아니다. 게임 서버의 무중단 무부하 운영 및 메모리 누수 방지에 절대적 무게추를 두어야 한다"는 명확한 설계 헌장을 제시.
  - 이를 통해 무손실이라는 명목하에 플러그인을 무겁게 만들려던 시도를 원천 차단하고, 고객 런타임의 극단적 경량화와 상류(Upstream) 트래픽 통제라는 명확한 책임 분리 도출.
- **포니테일 기반 복잡도 사냥 및 메모리 누수 교정 (`/ponytail-review`)**:
  - 빌더 세션에서 무심코 도입된 무제한 `ConcurrentLinkedQueue<WebSocketFrame>`의 메모리 누수(OOM) 취약점을 점검 세션에서 적발, 최대 50개 슬라이딩 버퍼(`MAX_OUTBOUND_HISTORY`)로 즉시 교정.
  - 프로덕션 코드와 불일치하던 반쪽짜리 동기 진입점 `handleIncomingCommand` 삭제(`delete: -15 lines`) 및 중복 `EventEnvelope` 생성 코드를 인라인 제네릭 빌더로 단일화(`shrink: -20 lines`).

#### 3. 도출된 엣지케이스 & 방어 체계
- **좀비 소켓(Zombie Connection) 능동 차단**:
  - WSS 세션 수립 후 PING만 보내고 PONG이 오지 않는 유령 커넥션 상황에서, 마지막 PONG 수신 시각(`lastPongReceivedAt`)이 3주기(90초)를 초과하면 즉시 `session.close(GOING_AWAY)`로 세션을 강제 파기하고 지수 백오프 재연결을 트리거.
- **비즈니스 식별자 기반 멱등키 (`idempotency_key`) 전면 개편**:
  - 기존 랜덤 UUID 기반 멱등키는 네트워크 재전송 시 중복 처리를 막지 못함. 이를 비즈니스 키(`mc:lvl:{uuid}:{newLevel}`, `mc:adv:{uuid}:{advId}`)로 개편하여 중복 레벨업/발전과제 이벤트가 워커에 유입되어도 1회만 실행되도록 멱등성 보장.
- **Paper API 공변 반환 타입 호환성 & 백그라운드 이벤트 격리**:
  - Paper 1.20.4의 `Advancement.getDisplay()`가 `io.papermc.paper.advancement.AdvancementDisplay`를 반환하는 런타임 특성을 파악하고 Java Reflection Proxy 기반 무의존성 모킹 구현.
  - 디스플레이가 없는 백그라운드 발전과제(레시피 언락 등)가 무의미하게 이벤트를 발행하지 않도록 필터링 가드 적용.
- **플러그인 비활성화 상태(`!isEnabled`) 안전 가드**:
  - 서버 셧다운 중 비동기 WSS 스레드에서 명령어 요청이 도달해도 스케줄러 크래시 없이 즉시 실패 응답(`Plugin is disabled`)을 반환하도록 방어.

#### 4. 정량적 엔지니어링 지표
- **테스트 커버리지**: MockBukkit 가상 틱 환경 + Ktor Netty 모의 WSS 서버 기반 통합 테스트 100% 통과 (10/10 tests).
- **코드 다이어트**: 프로덕션 소스 49 lines 삭제 및 안전 가드 보강 (`RuBeaconPlugin.kt`, `MinecraftEventListener.kt`).
- **메모리 통제 (FinOps)**: 아웃바운드 큐 50개 엄격 제한으로 고객 마인크래프트 서버의 힙 메모리 점유율을 100KB 미만으로 영구 고정.

---

### [마일스톤 3] `api-service` Ingress & DB/Redis 연동 (완료)

#### 1. 아키텍처 트레이드오프 & 핵심 의사결정
- **외부 통신망 보안 경계: WSS L7 게이트웨이(선택) vs Redis TLS 직접 노출(기각)**:
  - *기각한 대안 (외부 마인크래프트 클라이언트의 Redis TLS 직접 접속)*:
    - *기각으로 잃은 이익*: WebSocket 프로토콜 변환 및 Ingress 서버 구현 없이 클라이언트가 Redis Streams로 직접 이벤트를 밀어넣을 수 있어 개발 속도 극대화.
    - *기각한 이유 (엔지니어 판단 & 보안 참사 방어)*:
      - **전송 암호화(TLS)와 인가(Authorization)의 혼동 배제**: Redis가 TLS(`rediss://`)를 지원하더라도 이는 단순 패킷 도청만 방지할 뿐, 클라이언트에게 Redis 접속 자격 증명을 쥐어주는 순간 클라이언트 JAR 디컴파일을 통해 패스워드가 탈취됨.
      - **멀티테넌트 침해 및 DoS 위험**: Redis ACL은 세밀한 비즈니스 이벤트 검증이 불가능하며, 단일 스레드 기반 Redis에 고비용 커맨드(`KEYS`, 폭풍 `XRANGE`) 공격이 가해질 경우 플랫폼 전체 내부 버스가 마비됨.
      - **L7 보안 부재**: 6379 포트는 WAF나 L7 리버스 프록시 뒤에 둘 수 없음. 따라서 외부망은 443 WSS로 단일화하고 API 서버가 페이로드를 살균(Sanitize)한 뒤 VPC 내부 Redis Streams로 전달하는 구조를 확정함.
- **계정 연동 영속화 및 제약: 단일 테이블 인플레이스 갱신 + DB CASCADE(선택) vs 무외래키/다중 이력 테이블(기각)**:
  - *기각한 대안 (MSA식 FK 배제 및 다중 감사 엔티티 분리)*:
    - *기각으로 잃은 이익*: 샤딩 확장성 확보 및 계정 시도 이력의 엔티티 단위 영구 보존.
    - *기각한 이유*: Ru-Beacon은 단일 PostgreSQL 기반 B2B 멀티테넌트 SaaS임. 대규모 분산 환경의 "FK 배제" 트렌드를 맹목적으로 추종할 경우, 테넌트 탈퇴 시 5개 테이블의 종속 데이터를 애플리케이션 코드로 순회 삭제해야 하며 서버 크래시 시 고아 데이터(Orphan records)가 영구 누적되어 테넌트 격리 무결성을 파괴함.
    - *채택한 구조*: `account_links` 단일 테이블에서 `(tenant_id, minecraft_uuid)` UQ 인덱스로 동시성 충돌을 차단하고, 보안 실패/탈취 시도 모니터링은 전역 `audit_logs` 테이블로 책임을 분리하여 단순성과 감사 완결성을 동시에 확보함.

#### 2. AI 통제 및 거버넌스 (Human-in-the-Loop)
- **가상의 아키텍처 고민(Redis 분산 세션) 적발 및 YAGNI 원칙 환원**:
  - 에이전트가 인터뷰 질문을 도출하는 과정에서 "단일 VM/K3s 단일 노드 운영"이라는 마일스톤 0 대원칙을 망각하고, 멀티 파드 확장을 가정한 "Redis 분산 세션 레지스트리 vs 로컬 세션"이라는 가상의 갈림길을 인위적으로 지어낸 사실을 엔지니어가 날카롭게 지적.
  - 불필요한 미래 예측과 분산 복잡도를 원천 차단하고, 실제 마일스톤 3의 본질인 "WSS 게이트웨이 보안 격리 및 단일 테이블 UQ 트레이드오프"로 논의 초점을 성공적으로 재정렬함.
- **분산 소켓 재연결 레이스 컨디션 적발 및 원자적 CAS 비교 교정**:
  - 빌더 세션에서 `MinecraftWebSocketRoute.kt`의 `finally` 블록에 무조건 `sessionRegistry.unregister(instanceId); authService.updateStatus(instanceId, "OFFLINE")`를 호출하던 전형적인 분산 버그 적발.
  - 마인크래프트 서버 플러그인이 빠른 재연결을 수행할 때 세션 2가 수립된 직후 세션 1의 `finally`가 실행되어 정상 연결된 세션 2를 레지스트리에서 지우고 DB를 `OFFLINE`으로 덮어써버리는 레이스 컨디션을 엔지니어링 관점에서 도출.
  - `ConcurrentHashMap.remove(instanceId, session)` 원자적 비교 연산을 적용하여 "내가 마지막 유효 세션일 때만 OFFLINE으로 전이"하도록 아키텍처를 교정.
- **포니테일 기반 복잡도 사냥 (`/ponytail-review`)**:
  - `SessionRegistry.kt`에 무심코 선언되어 있던 미사용 `private val mutex = Mutex()` 삭제 (`delete: -1 line`).
  - `InstanceAuthService.kt`의 조회 후 메모리 비교 로직을 SQL WHERE 절 결합 및 `.count() > 0` 단일 쿼리로 다이어트 (`shrink: -3 lines`).

#### 3. 도출된 엣지케이스 & 방어 체계
- **마인크래프트 기연동 계정 탈취(Account Hijacking) 원천 차단**:
  - 기존 연동 레코드가 `ACTIVE` 상태일 때 제3자가 동일한 `minecraft_uuid`로 `/request`를 호출하여 연동을 가로채거나 무효화하는 것을 방지하기 위해, 요청자의 `discordUserId`가 다를 경우 `LinkResult.AlreadyLinked` 및 HTTP 409 Conflict로 즉시 거부.
- **브루트포스 무차별 대입 방어**:
  - 6자리 1회용 인증 코드에 대해 5회 연속 인증 실패 시, DB의 `verification_code`와 만료 시각을 즉시 `null`로 영구 파기하여 추가적인 대입 공격을 원천 차단.
- **재연결 시 세션 보존 회귀 테스트 하네스 구축**:
  - Testcontainers 기반 실제 Ktor 클라이언트 2개를 동시에 띄워 세션 1이 열린 상태에서 세션 2를 연결하고 세션 1을 닫았을 때 `activeCount == 1` 및 DB `ONLINE` 상태가 완벽히 보존됨을 통합 테스트로 입증.
- **장애 격리 (Fault Tolerance)**:
  - Redis Streams 일시 순단 시에도 마인크래프트 플러그인과의 제어 소켓(COMMAND 채널)이 함께 파괴되지 않도록 예외를 격리.

#### 4. 정량적 엔지니어링 지표
- **테스트 커버리지**: Testcontainers PostgreSQL 16 + Redis 7 기반 통합 테스트 100% 통과 (4개 테스트 클래스, 7개 시나리오 전수 통과).
- **보안 무결성**: 인스턴스 토큰 원문 평문 노출 0건, 5회 실패 시 1회용 코드 즉시 파기, 타인 기연동 탈취 시도 409 차단.
- **빌드 및 검증 속도**: 컨테이너 싱글톤 패턴 적용으로 전체 모듈 빌드 및 Docker 컨테이너 통합 테스트를 37초 이내 완료.

---

### [마일스톤 4] `workflow-worker` 인메모리 DAG 엔진 및 선착순 동시성 제어 (완료)

#### 1. 아키텍처 트레이드오프 & 핵심 의사결정
- **워크플로우 엔진 아키텍처: 인메모리 코루틴 DAG 엔진(선택) vs 분산 오케스트레이터(Temporal, Camunda 등)(기각)**:
  - *기각한 대안 (Temporal/Camunda 등 무거운 외부 분산 오케스트레이터 도입)*:
    - *기각으로 잃은 이익*: 장기 실행(Long-running) 워크플로우의 단계별 자동 상태 저장/체크포인팅 및 서버 재부팅 시의 인플라이트 재개(Resume) 기능을 프레임워크 수준에서 무상 획득.
    - *기각한 이유*: Ru-Beacon의 워크플로우는 마인크래프트-디스코드 간 즉각적인 보상 지급 및 알림 트리거로, 실행 시간이 수 초 이내에 종결되는 단기 작업(Short-lived task)임. Temporal 같은 클러스터를 구축하면 최소 2GB~4GB 이상의 메모리를 추가 점유하여 소형 VM/OCI Free Tier 단일 노드 운영 원칙(마일스톤 0)이 파괴됨. Kotlin Coroutine 기반 인메모리 DAG 엔진과 단 1회 비동기 Audit Log 기록으로 극단적인 초저지연 및 FinOps 효율성 확보.
- **선착순 쿼터 동시성 통제: RDBMS 조건부 원자적 UPDATE(선택) vs Redis 분산 락 / Lua 스크립트(기각)**:
  - *기각한 대안 (Redis 분산 락 `Redlock` 또는 Lua 스크립트)*:
    - *기각으로 잃은 이익*: Redis 메모리 상에서 초당 수천 건의 고속 쿼터 차감 및 락 획득 가능.
    - *기각한 이유*: 출석 보상 선착순 100명 이벤트는 최종적으로 RDBMS의 `reward_reservations` 레코드와 100% 일치해야 함. Redis에서 먼저 카운트를 차감하고 DB 쓰기를 시도하면, DB 연결 실패나 데드락 발생 시 Redis를 원복해야 하는 이중 쓰기(Dual-write) 불일치 및 데이터 고아화가 불가피함. PostgreSQL의 `reserved_count < total_limit` 조건부 원자적 UPDATE와 `(tenant_id, reward_date, player_uuid)` UNIQUE 제약을 단일 트랜잭션으로 묶어 데이터 무결성과 동시성 방어를 한 번에 완결.

#### 2. AI 통제 및 거버넌스 (Human-in-the-Loop)
- **코루틴 취소 예외(CancellationException) 삼킴 적발 및 재전파 교정**:
  - 빌더 세션에서 `DagWorkflowDispatcher.kt`가 `runCatching`으로 노드를 실행하여, 상위 스코프 취소 시 발생하는 `CancellationException`까지 `Failure` 결과로 변환하고 코루틴 취소 협동성을 깨뜨리던 결함을 점검 세션에서 적발. 명시적 try-catch로 취소 예외를 즉시 rethrow 하도록 교정.
- **포니테일 기반 복잡도 사냥 (`/ponytail-review`)**:
  - `RedisStreamsConsumer.kt`: `processBatch`와 `autoClaimStaleMessages`에 100% 중복되어 있던 15줄의 이벤트 파싱·디스패치·XACK 로직을 `processEntry` 단일 헬퍼 함수로 추출 (`shrink: -20 lines`).
  - `TarjanCycleDetector.kt`: `definition.nodes`를 2번 순회하던 불필요한 노드 초기화 루프를 엣지 수집의 `getOrPut` 단일 루프로 통합 (`shrink: -5 lines`).
  - `DagWorkflowDispatcher.kt`: `contextMutex` 내 `fold`를 통한 중복 Context 복제를 맵 병합으로 축약 (`shrink: -3 lines`).
  - `DagWorkflowDispatcher.kt`: 감사 로그 `details`의 `completed_nodes`, `failed_nodes`가 이스케이프된 문자열로 들어가지 않고 정규 JSON 배열로 적재되도록 `buildJsonArray` 구조화.

#### 3. 도출된 엣지케이스 & 방어 체계
- **일일 출석 보상 중복 예약 사전 차단 (`ALREADY_RESERVED`)**:
  - 동일 플레이어가 당일 다중 클릭하거나 동시 요청을 유입시킬 경우, DB 레벨의 UNIQUE 제약 충돌(500 에러)을 유발하지 않고 사전 검증을 통해 `ALREADY_RESERVED` 비즈니스 에러 코드로 즉각 차단.
- **보상 트랜잭션 롤백(RELEASE) 시 언더플로우 방어**:
  - 중간 노드 실패로 인한 롤백 실행 시, 악의적 중복 호출이나 오류로 `reserved_count`가 음수로 떨어지지 않도록 `reserved_count > 0` 조건을 원자적 UPDATE에 강제.
- **FinOps OOM 방어**:
  - Worker가 외부로 발행하는 마인크래프트 명령어(`commands:request`) 및 디스코드 액션(`discord:actions`) 스트림에 `MAXLEN ~ 10000` 근사 트리밍을 강제 적용하여 장기 운영 시 Redis 메모리 고갈 차단.

#### 4. 정량적 엔지니어링 지표
- **테스트 커버리지**: Testcontainers PostgreSQL 16 + Redis 7 기반 템플릿 A/B 및 엣지케이스 테스트 100% 통과 (8/8 tests).
- **코드 다이어트**: 중복 루프 및 파싱 보일러플레이트 제거로 `net: -28 lines` 절감.
- **동시성 검증**: 100개 코루틴 선착순 동시 요청 경합 완벽 통과, 101번째 초과 요청 및 중복 예약 100% 차단 입증.
- **빌드 및 검증 속도**: 전체 멀티모듈 통합 테스트 32초 이내 완료.


