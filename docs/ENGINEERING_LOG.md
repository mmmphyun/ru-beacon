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
- **상태 관리 및 명령어 중복 방어: 인메모리 실행 + 비즈니스 멱등키 위임(선택) vs 노드별 DB 체크포인팅(기각)**:
  - *기각한 대안 (노드 단위 DB 체크포인팅 및 사가 엔진)*:
    - *기각으로 잃은 이익*: Worker 크래시(OOM, SIGKILL) 시 재개(Resume) 지점을 정확히 추적하여 인게임 명령 중복 디스패치를 원천 방지.
    - *기각한 이유 (엔지니어 판단)*:
      - 소형 VM 환경에서 DB I/O 병목 회피는 절대적 과제임. 단기 실행(수 초 이내) 워크플로우를 위해 매 노드마다 DB 커밋을 발생시키는 것은 주객전도이며, 플랫폼 서버의 디스크 I/O와 커넥션 풀을 고갈시킴.
      - 고객 서버의 틱 렉을 주지 않는 것만큼 내 플랫폼 서버의 부하와 병목을 통제하는 것 역시 핵심 가치임.
    - *채택한 구조 (상류-하류 책임 분리)*:
      - Worker는 코루틴 인메모리 초저지연 실행을 유지하고, 종단 1회 비동기 Audit Log만 기록.
      - Worker 재부팅 및 Redis `XAUTOCLAIM`으로 인한 이벤트 재실행 시의 명령어 중복 디스패치 위험은, 마인크래프트 플러그인 제어 계층의 **비즈니스 멱등키(`mc:lvl:{uuid}:{lvl}`, `idempotency_key`) 검증에 위임**하여 하류에서 2회차 실행을 거부하도록 역할을 분리함.
- **선착순 쿼터 동시성 통제: RDBMS 원자적 UPDATE(현 단계 확정) 및 2단계 방어선(Two-Tier) 진화 설계**:
  - *기각한 대안 (단순 Redis 원자적 카운터 `DECR` 단독 운용)*:
    - *기각한 이유*: Redis에서 100명 안에 들었더라도 후속 DB `INSERT INTO reward_reservations` 시점에 DB 커넥션 풀 고갈이나 네트워크 타임아웃이 발생하면, Redis 수량만 깎이고 DB에는 99명만 남아 정원이 채워지지 않는 **이중 쓰기(Dual-Write) 불일치 및 고아 데이터** 발생. 이를 애플리케이션 보상 트랜잭션으로 복구하는 것은 장애 전파 위험을 가중시킴.
  - *채택한 구조 및 아키텍처 진화 로드맵*:
    - **현 단계(마일스톤 4)**: PostgreSQL `reserved_count < total_limit` 조건부 원자적 UPDATE와 `(tenant_id, reward_date, player_uuid)` UNIQUE 제약을 단일 트랜잭션으로 묶어, Dual-Write 리스크 0%의 절대적 데이터 무결성을 보장.
    - **대규모 트래픽 확장 로드맵 (2단계 방어선: Two-Tier Defense)**: 동시 접속자가 수천 명으로 폭증하여 단일 행 락 경합이 심화될 경우, Redis Lua 스크립트를 1차 관문(Admission Control)으로 두어 100명 초과 트래픽을 메모리에서 0ms로 튕겨내 DB 커넥션 풀을 보호하고, 통과한 100명만 DB 원자적 제약으로 완결하는 2단계 방어선 구조로 진화할 수 있도록 설계를 확정함.

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

---

### [마일스톤 5] `bot-service` Kord Discord 상호작용 및 이벤트 정규화 (완료)

#### 1. 아키텍처 트레이드오프 & 핵심 의사결정
- **Discord 인터랙션 처리: 즉각적인 비동기 접수(Fire-and-Forget Ingress) 및 비동기 이벤트 콜백 채택**
  - *기각한 대안 1 (동기식 종단 RPC 대기)*:
    - *기각 이유*: 마인크래프트 서버 틱 렉(TPS 저하)이나 워커 지연 발생 시 Discord Gateway의 3초 타임아웃을 초과하여 `Interaction Failed` 오류 노출.
  - *기각한 대안 2 (타임아웃 회피용 폴링/지속 갱신 요청)*:
    - *기각 이유*: 클라이언트 스레드가 게임 서버 응답을 대기하며 Gateway에 주기적 신호를 보내는 것은 스레드 고갈 및 네트워크 커넥션 낭비를 초래함.
  - *채택한 구조 (Fire-and-Forget Ingress + 비동기 콜백 로드맵)*:
    - `deferEphemeralResponse()` 즉시 호출로 Gateway 3초 셧다운을 원천 차단하고, `EventEnvelope`로 정규화하여 Redis Streams에 발행한 뒤 "접수 완료" 피드백을 0ms 수준으로 즉각 반환.
    - 향후 최종 지급 결과 안내(UX 고도화)가 필요할 경우, 스레드를 묶어두는 것이 아니라 마인크래프트 플러그인이 실행 결과를 다시 Redis Streams로 발행하고 봇이 이를 소비하여 Discord 채널/웹훅으로 후속 안내하는 **완전 비동기 이벤트 주도 콜백(Event-Driven Callback)** 구조로 확장할 것을 확정.
- **Discord Action 컨슈머의 영구 실패 처리: Poison Pill 즉각 격리 및 XACK 채택**
  - *기각한 대안*: 채널 미존재, 권한 없음 등의 4xx 오류 발생 시 별도의 DLQ(Dead Letter Queue)를 구축하거나 Redis에 무한 재시도 보류.
  - *기각한 이유*: 1인 개발 및 소형 VM 운영 환경에서 복잡한 DLQ 아키텍처는 운영 오버헤드와 디스크 낭비를 유발함. 또한 존재하지 않는 채널 ID나 봇 권한 누락은 재시도해도 영원히 성공할 수 없는 불변의 클라이언트 에러임.
  - *채택한 구조*: Kord `RestRequestException` 감지 시 에러 로그를 남기고 즉시 `XACK`하여 컨슈머 루프의 무한 블로킹 및 `XAUTOCLAIM` 누수를 원천 차단.

#### 2. AI 통제 및 거버넌스 (Human-in-the-Loop)
- **Discord Defer 단계 예외 격리 (Gateway 연결 단절 방어)**:
  - 빌더 세션에서 `interaction.deferEphemeralResponse()`가 네트워크 지연으로 실패할 경우 발생한 예외가 상위 이벤트 디스패처로 전파되어 Gateway 세션 루프를 중단시킬 수 있던 결함을 점검 세션에서 적발. Defer 호출부 자체를 독립 `try-catch`로 감싸 Gateway 수명주기를 완벽히 격리.
- **포니테일 강제 강령 (`/ponytail-review`) 준수**:
  - `bot-service` 내 단일 구현체 인터페이스 0개 유지. 별도 Repository/Service 레이어를 만들지 않고 `RuBeaconBot`, `DiscordEventNormalizer`, `DiscordEventPublisher`, `DiscordActionConsumer` 4개 컴포넌트로 가장 단순하고 응집도 높은 구조 확립.
  - Discord Interaction 객체에서 Ru-Beacon 공통 `EventEnvelope`로 1단계 직접 정규화하여 불필요한 중간 DTO/매퍼 전면 배제.

#### 3. 도출된 엣지케이스 & 방어 체계
- **입력값 선제 유효성 검증**: `/verify` 호출 시 공백 코드 유입을 사전에 차단하여 무의미한 Redis Streams 발행 방지.
- **Poison Pill 방어**: 비정상 메시지(채널 ID 누락 등) 유입 시 크래시 없이 건너뛰고 정상 ACK 처리 (`DiscordActionConsumerTest`).
- **FinOps OOM 방어**: Discord 이벤트 발행 시 `MAXLEN ~ 10000` 근사 트리밍 강제로 Redis 메모리 통제.

#### 4. 정량적 엔지니어링 지표
- **테스트 커버리지**: Testcontainers Redis 7 기반 이벤트 정규화, 직렬화, 스트림 발행, 액션 소비 및 엣지케이스 테스트 100% 통과 (4개 테스트 클래스).
- **성능 및 빌드 속도**: 전체 멀티모듈 5개 프로젝트 통합 테스트 통과 시간 8초 이내.
- **코드 규모**: 5개 신규 핵심 파일 및 테스트 4종 구축 완료 (`+965 lines`).

---

### [마일스톤 5.5] 분산 동시성 제어 및 인프라 하드닝 (Hardening Sprint) (완료)

#### 1. 아키텍처 트레이드오프 & 핵심 의사결정
- **2-Tier 동시성 제어: Redis 1차 Admission Control + PostgreSQL 2차 원자적 영속화**
  - *포니테일(YAGNI) 맹종 탈피 및 클라우드 포트폴리오 가치 확립 (엔지니어 핵심 의사결정)*:
    - 초기에는 포니테일 규칙("가장 게으른 해결책", "미래 대비 코드 지양")에 매몰되어 단일 PostgreSQL 조건부 UPDATE(선택지 B)만으로 구현하고 Redis 동시성 제어를 나중으로 미루어 두었음.
    - 그러나 엔지니어가 **"소규모 가정을 핑계로 RDBMS 병목을 방치하는 것은 클라우드 직무 포트폴리오 관점에서 올바른 엔지니어링이 아니다"**라고 판단. 소형 VM 환경(HikariCP 커넥션 10~20개)에서 1,000명 동시 클릭 스파이크가 발생할 경우 단일 행 락 경합으로 커넥션 풀이 고갈되어 전체 서비스가 다운되는 치명적 한계를 선제 직시함.
    - 이에 따라 Redis 1차 인메모리 빠른 탈락(Admission Control)과 PostgreSQL 2차 영속화 및 보상 트랜잭션 롤백(선택지 A)으로 아키텍처를 과감히 전진 배치함.
  - *채택한 2-Tier 구조*:
    - 1차 Redis Set 기반 빠른 탈락: 당일 중복 참여(`SISMEMBER`) 및 남은 쿼터(`SCARD >= limit`)를 인메모리 0ms로 판별. 초과/중복 요청은 DB 커넥션을 0회 호출하고 즉시 반환 (`Fast-Fail`).
    - 2차 PostgreSQL 영속화: 관문을 통과한 N개 요청만 DB 트랜잭션(`AttendanceQuotas` 조건부 UPDATE 및 `RewardReservations` INSERT) 진입.
    - 무중단 Graceful Fallback: Redis 다운/타임아웃 발생 시에도 시스템 중단 없이 PostgreSQL 조건부 UPDATE로 자동 전환되어 정상 동작 보장.
- **Redis 키 구조 단순화: 단일 Set 키 (`quota:{tenantId}:{date}:players`) 단일화**
  - *기각한 대안*: `count` 문자열 키와 `players` Set 키를 이원화하여 관리하던 초기 설계.
  - *기각 이유*: 카운트 증가(`INCR`)와 집합 추가(`SADD`)를 동기화하기 위해 Lua 스크립트 복잡도가 증가하고, 롤백 시에도 별도의 `ROLLBACK_LUA` 스크립트가 필요하며 두 키 간의 정합성 불일치 위험 존재.
  - *채택한 구조*: Redis Set은 `SCARD`를 통해 O(1) 시간 복잡도로 요소 수를 즉시 반환하므로, `count` 키를 완전히 삭제하고 Set 하나로 통합. 롤백 또한 Redis 내장 명령인 `srem` 1줄로 단순화하여 `ROLLBACK_LUA`를 영구 제거.

#### 2. AI 통제 및 거버넌스 (Human-in-the-Loop)
- **오버엔지니어링 사냥 (`/ponytail-review`)**:
  - Redis Set이 자체 크기(`SCARD`)를 O(1)로 제공함에도 불필요하게 `count`와 `players` 2개 키를 유지하고 복잡한 `ROLLBACK_LUA`를 수동 작성했던 오버엔지니어링을 적발 및 제거 (`net: -36 lines`).
  - Redis 원시 명령 `srem` 단일 호출로 롤백을 단순화하고, `DiscordResponseRenderer`의 중복 문자열 템플릿 분기 축약.
- **분산 추적 관측성 누락 점검**:
  - `commit`, `release`, `rollbackRedis` 및 Discord 봇/컨슈머 전반의 로깅에 `[{}]` `correlationId` 전파를 누락 없이 표준화.

#### 3. 도출된 엣지케이스 & 카오스 방어 체계
- **Redis 장애 시 무중단 DB Fallback 카오스 검증**:
  - Redis 포트가 닫히거나 타임아웃 예외가 발생하는 상황에서도 요청이 실패하지 않고 PostgreSQL 원자적 조건부 UPDATE로 매끄럽게 전환되어 정원 통제 및 멱등성이 완벽히 유지됨을 회귀 테스트(`Redis 다운 및 타임아웃 장애 시에도 DB 조건부 UPDATE로 무중단 Fallback 동작 검증`)로 입증.
- **보상 트랜잭션 롤백 장애 시 데이터 불일치 경보**:
  - DB 영속화 실패 후 Redis 보상 롤백 과정에서 네트워크 이상이 발생할 경우, 예외를 조용히 삼키지 않고 `[CRITICAL]` 에러 로그를 남겨 운영자 인지 및 수동 조정 가능성 확보.

#### 4. 정량적 엔지니어링 지표
- **테스트 커버리지**: Redis 동시성 경합(30개 동시 요청), Fast-Fail, 중복 차단, 보상 트랜잭션 롤백, Null Fallback, Redis 장애 타임아웃 Fallback 총 6개 통합 테스트 100% 통과.
- **FinOps & 메모리 절감**: Redis 키 2종(count, players) -> 1종(players Set)으로 50% 절감 및 48시간 TTL 강제 유지.
- **테스트 수행 속도**: 전체 멀티모듈 24개 테스트 19초 이내 완벽 통과 (`BUILD SUCCESSFUL in 19s`).

---

### [마일스톤 6] `web-dashboard` Next.js 위저드형 대시보드 & 워크플로우 엔진 연동 (완료)

#### 1. 아키텍처 트레이드오프 & 핵심 의사결정
- **워크플로우 DAG 영속화 모델: 불변 JSON AST 단일 컬럼(Document Store) 채택**
  - *기각한 대안*: Workflows, Nodes, Edges로 세분화된 정규화 RDBMS 테이블 분할 (선택지 A).
  - *기각 이유*: 노드 수정 및 버전 복제 시 복합 JOIN 및 재귀적 INSERT 오버헤드로 인한 성능 저하와 N+1 쿼리 발생. 다양한 액션 파라미터 확장에 따른 DDL 마이그레이션 및 EAV 안티패턴 위험.
  - *채택한 구조 및 포기한 가치*:
    - `WorkflowVersions.definition`에 불변(Immutable) JSON AST 스냅샷을 통째로 영속화.
    - 버전 생성, 배포(ACTIVE 전환), 롤백이 단 1회의 SQL UPDATE로 0ms에 완결되는 극단적 단순성과 원자성 확보.
    - DB 레벨의 노드/외래키 제약조건과 부분 인덱싱을 포기하는 대신, Ktor `WorkflowRoute.kt`의 `detectCycle` DFS 알고리즘을 통해 배포 및 롤백 전 순환 참조 및 문법 결함을 애플리케이션 계층에서 엄격히 선제 차단.
- **런타임 버전 배포 및 전파 메커니즘: RDBMS 원자적 포인터 스왑 + 워커 인메모리 Single-Flight TTL 캐싱**
  - *기각한 대안*: Redis Pub/Sub 즉시 핫 리로드 (Fire-and-Forget 브로드캐스트, 선택지 A).
  - *기각 이유*:
    - Redis Pub/Sub은 영속성과 컨슈머 오프셋이 없어 네트워크 단절이나 워커 재부팅 시 배포 이벤트가 영구 유실됨.
    - 특정 워커 노드만 구버전을 계속 실행하는 분산 상태 분열(Split-brain)이 발생할 경우, 인게임 보상 버그나 롤백 실패로 인한 고객 클레임(SLA 위반)의 전적인 책임이 플랫폼에 귀속됨.
    - 유실 복구를 위해 워커 간 수동 핸드셰이크/동기화 큐를 덧대는 것은 바퀴를 재발명하는 전형적인 오버엔지니어링(RDD).
  - *채택한 구조 및 포기한 가치*:
    - RDBMS를 단일 진실 공급원(Single-Source-of-Truth)으로 삼고, 배포/롤백 시 트랜잭션 내에서 `active_version` 포인터만 원자적으로 스왑.
    - 배포 후 최대 TTL(30초) 동안 구버전이 일시 실행될 수 있는 최종 일관성(Eventual Consistency) 지연을 의도적으로 감수.
  - *DB 부하 및 단일 장애점(SPOF) 방어 체계*:
    - **Single-Flight (Mutex)**: 캐시 만료 시 다수 워커 코루틴 중 단 1개만 DB 조회를 수행하고 결과를 공유하여 Thundering Herd(Cache Stampede)를 원천 차단 (최대 부하를 30초당 1건의 SELECT로 고정).
    - **Stale-While-Revalidate Fallback**: DB 일시 장애/지연 시 즉시 에러를 내지 않고 메모리에 보관 중이던 구버전 워크플로우 DAG(Stale Cache)를 유지하여 인게임 이벤트 실행을 100% 무중단 보장. 제어 평면(DB)의 장애가 데이터 평면(워커 실행)으로 전파되지 않도록 완벽히 격리.

#### 2. AI 통제 및 거버넌스 (Human-in-the-Loop)
- **과도한 캔버스 라이브러리(React Flow 등) 도입 거부 및 위저드 폼 강제**:
  - 관리자 사용자 경험에서 진입 장벽이 높은 자유형 2D 노드 드래그 앤 드롭 캔버스 라이브러리를 전면 배제하고, 3단계(트리거 → 조건 → 액션) 카드형 위저드 폼 State 구조로 단순화.
  - UI 폼 상태에서 백엔드 표준 DAG AST로의 단방향 컴파일(`generateWorkflowAst`)을 단일 순수 함수로 일원화하여 불필요한 상태 관리 복잡도 제거.
- **포니테일 강제 강령 (`/ponytail-review`) 준수**:
  - `web-dashboard` 내 무거운 전역 상태 라이브러리(Redux/Zustand 등)를 배제하고 React 기본 Hook(`useState`, `useMemo`) 및 단일 API 클라이언트(`api.ts`)로 완결.
  - `api-service` 워크플로우 엔드포인트에서 불필요한 중간 Repository 인터페이스 없이 Exposed DSL 트랜잭션 블록으로 직결하고, 배포·롤백의 중복 상태 전이 쿼리를 `switchActiveWorkflowVersion` 단일 헬퍼로 통합(`shrink: -25 lines`)하여 최단 diff 확립.

#### 3. 도출된 엣지케이스 & 방어 체계
- **DAG 순환 참조(Cycle Loop) 사전 차단 (배포 및 롤백)**:
  - 배포(`POST /api/v1/workflows/{id}/deploy`) 및 롤백(`POST /api/v1/workflows/{id}/rollback`) 호출 시 인메모리 DFS 그래프 탐색(`detectCycle`)을 강제 실행하여 사이클 감지 시 HTTP 400 `WORKFLOW_CYCLE_DETECTED`와 함께 순환 경로 노드 목록을 반환하고 전이 차단.
- **템플릿 변수 인젝션 방어**:
  - 위저드 폼 검증 단계에서 `{User_Nickname}`, `{Minecraft_UUID}` 등 `ALLOWED_VARIABLES` 화이트리스트 외의 변수 유입을 차단하여 워커 런타임 NullPointerException 및 포맷 스트링 오류 방지.
- **워크플로우 불변 버전 관리 (Immutability)**:
  - 기존 ACTIVE 버전을 직접 수정(In-place Mutation)하지 않고 항상 새로운 DRAFT 버전을 생성(`POST /api/v1/workflows/{id}/versions`)한 뒤 검증을 거쳐 배포하도록 강제하여 이력 추적성 및 롤백 무결성 보장.

#### 4. 정량적 엔지니어링 지표
- **테스트 커버리지**:
  - Kotlin 백엔드: Ktor `WorkflowRoutesTest` (목록, 상세, 신규 버전 생성, 사이클 배포/롤백 차단, 깨진 JSON/미존재 테넌트 방어 등 7개 통합 테스트 100% 통과).
  - TypeScript 프론트엔드: Vitest 기반 `workflow-generator.test.ts` (AST 컴파일, 변수/정원/조건 검증, 사이클 탐지), `WorkflowWizard.test.tsx` 총 12개 테스트 100% 통과.
- **빌드 및 린트 속도**: Next.js App Router 빌드 및 TypeScript 정적 타입 검사 무결점 통과, Gradle 멀티모듈 통합 테스트 통과 시간 7초 이내.
- **코드 규모**: 대시보드 UI 컴포넌트 14개, Ktor API 라우트 2개, 단위/통합 테스트 3개 클래스 구축 완료.

---

### [마일스톤 7] K3s Helm 패키징·KEDA 오토스케일링 및 관측성 CI/CD 파이프라인 (완료)

#### 1. 아키텍처 트레이드오프 & 핵심 의사결정
- **인프라 런타임: 상용 매니지드 K8s(EKS/GKE) vs K3s + Helm 기반 FinOps 아키텍처**
  - *기각한 대안*: AWS EKS / GCP GKE 클러스터 구성.
  - *기각 이유*: 컨트롤 플레인 고정비만 월 $70+ 이상 청구되어 소규모 서버/부트스트랩 프로젝트에서 지속 불가능한 인프라 낭비(Negative ROI) 초래.
  - *채택 이유 및 포기한 가치*:
    - CNCF 공식 인증 경량 배포판인 K3s를 단일/소형 노드에 배치하여 월 인프라 비용을 $0~$20 미만으로 통제.
    - Helm 매니페스트(`deploy/helm/ru-beacon`)를 순수 표준 Kubernetes API 규격으로 완결하여, 향후 EKS/GKE 전환 시 단 1줄의 설정 수정 없이 즉시 배포 가능한 클라우드 이식성(Portability) 확보.
- **오토스케일링 지표: CPU/메모리 HPA vs KEDA Redis Streams Consumer Lag 기반 이벤트 오토스케일링**
  - *기각한 대안*: Kubernetes 표준 CPU 70% 기반 HorizontalPodAutoscaler (HPA).
  - *기각 이유*: Kotlin 코루틴 기반 Worker는 논블로킹 I/O로 동작하여, 큐에 이벤트가 10,000건 적체되어도 CPU 사용률이 30%를 넘지 않음. CPU 지표가 큐 지연을 포착하지 못하는 "메트릭 맹점(Lag Detection Blindness)"으로 인해 장애가 발생해도 스케일 아웃이 누락됨.
  - *채택 이유*: KEDA CRD(`ScaledObject`)를 도입하여 Redis Streams 컨슈머 랙(`lagThreshold: 100`)을 15초 주기로 감지, 큐 적체 발생 즉시 Pod를 2대에서 10대까지 0초 지연으로 증설하여 실시간 처리 SLA를 방어.
- **동시성 스파이크 방어: 순수 비동기 큐잉(SRP) vs Ingress 2-Tier Admission Fast-Fail**
  - *기각한 대안*: Ingress의 완전한 관심사 분리를 위해 모든 이벤트를 무조건 Redis Streams에 밀어넣는 완전 비동기 큐잉.
  - *기각 이유*: 1,000 RPS 선착순 이벤트 시 99.9%의 탈락자 이벤트까지 큐를 점유하여 메모리 급증(OOM) 및 컨슈머 랙 폭증을 야기하고 워커 노드 비용을 낭비함.
  - *채택 이유 및 방어 설계*:
    - Ingress 진입부(`POST /api/v1/events/simulate`)에서 Redis Lua 1차 관문을 단 1회 왕복(`O(1)`) 실행하여 초과 요청을 5ms 미만(평균 1.82ms)에 `429 Too Many Requests`로 즉시 Fast-Fail 차단.
    - 결합도 증가 위험은 **Traffic Admission(관문 통제)과 Business Execution(비즈니스 실행)의 엄격한 역할 분리**로 제어하고, Redis 장애 시 **Fail-Open(무중단 통과)**으로 후선 DB 트랜잭션이 안전하게 수용하도록 이중 방어선 구축.

#### 2. AI 통제 및 거버넌스 (Human-in-the-Loop)
- **개인 프로젝트 핑계 배제 및 실측 부하 테스트 하네스(k6) 강제**:
  - "유저 트래픽이 없다"는 이유로 동시성 검증을 포기하려는 AI의 안일한 가정을 차단하고, k6 부하 테스트 스크립트(`k6-concurrency-benchmark.js`)와 전용 시뮬레이션 라우트를 직접 구축하도록 강제.
- **포니테일 복잡도 다이어트 및 치명적 결함 적발**:
  - `WorkerMain.kt`에서 `AttendanceReservationExecutor(jedis)`에 `jedis` 인자 누락으로 2-Tier 락이 인메모리로 우회되던 치명적 결함을 정밀 diff 감사 중 적발 및 교정.
  - 미사용 `Workflows` Table 선언 제거(`net: -15 lines`) 및 Worker 메트릭 모듈 분리(`workerMetricsModule`)로 단위 테스트 하네스 확립.

#### 3. 도출된 엣지케이스 & 방어 체계
- **Thundering Herd 45,210건 동시 요청 실측 방어**:
  - k6 1,000 RPS 벤치마크 결과, 45,200건의 탈락 요청이 4.12ms(P99) 이내에 429로 초고속 차단되었으며, DB 커넥션 풀(`HikariCP max 10`)과 CPU 사용률(5% 미만)이 완벽히 안정 유지됨.
- **컨테이너 보안 하드닝 (CIS Kubernetes Benchmark 준수)**:
  - `api-service`, `bot-service`, `workflow-worker` 전 파드에 비루트 사용자(`UID 10001`), `allowPrivilegeEscalation: false`, `capabilities.drop: [ALL]`을 강제하여 컨테이너 탈옥 위험 원천 격리.

#### 4. 정량적 엔지니어링 지표
- **테스트 커버리지**: `EventSimulationRoutesTest` (5종), `WorkerMetricsAndHealthTest` (2종) 포함 전체 멀티모듈 24개 테스트 100% 통과 (`BUILD SUCCESSFUL in 10s`).
- **부하 성능 실측치**: k6 1,000 RPS 주입 시 초과 요청 Fast-Fail 평균 1.82ms / P99 4.12ms, Overselling Zero (정원 10개 엄격 준수).
- **인프라 비용 효율**: AWS EKS 대비 클러스터 유지비 85% 이상 절감 (소형 단일 노드 구동 검증).


