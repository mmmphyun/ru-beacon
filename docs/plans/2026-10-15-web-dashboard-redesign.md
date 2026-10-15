# Ru-Beacon Web Dashboard 전면 개편 상세 실행 계획서 (v1.4)

본 문서는 `web-dashboard` 모듈의 랜딩 페이지, 3단계 온보딩 위저드, 그리고 Gemini 및 Jev/OpenJev 대응 의사결정 모델 기반 워크플로우 생성기(Ru-Beacon 에이전트)의 세부 설계 및 실행 지침을 정의합니다.

---

## 1. 디자인 시스템 & 시맨틱 토큰 명세 (루비-샌드 고유 팔레트 기반 Linear/Resend 감성 구현)

타 서비스의 색상값을 복제하지 않고, **Ru-Beacon의 고유 루비-샌드(Ruby-Sand) 정체성 안에서 Linear와 Resend 수준의 절제미와 정돈된 깊이감**을 구현합니다.

### 1.1 루비-샌드 시그니처 캔버스 & 서피스 토큰
- **Canvas Base**: `hsl(0, 0%, 8%)` (딥 차콜 베이스)
  - 인위적인 보라/파랑 그라디언트를 배제하고 마인크래프트 심층암 톤의 차분하고 깊이 있는 무광 다크 캔버스.
- **Card & Surface**: `hsl(0, 0%, 11%)` (다크 흑연 서피스)
  - 그림자가 아닌 **1px 웜 샌드 헤어라인 테두리**로 계층 분리.
- **Border & Hairline**: `hsl(45, 8%, 18%)` (웜 샌드 헤어라인)
  - 차가운 회색 선 대신, 은은한 모래빛 온기가 도는 1px 미세 테두리로 고급스러운 경계선 형성.
- **Primary Accent (브랜드 루비)**: `hsl(340, 85%, 45%)` (쿨 와인 마젠타 루비)
  - 버튼을 원색으로 채워 남발하지 않고, 제품 쇼케이스의 핵심 상태 태그, 활성 인디케이터, 텍스트 강조에만 극도로 절제하여 사용.
- **Typography & Text**:
  - Foreground: `hsl(45, 10%, 96%)` (샌드 틴트 소프트 화이트, 눈부심 없는 매트 텍스트)
  - Muted: `hsl(45, 8%, 55%)` (모래빛이 은은하게 감도는 설명문)
  - Monospace: `JetBrains Mono`, `D2Coding` (엔지니어링 텔레메트리 및 코드 전용)

### 1.2 시스템 관제 상태 시맨틱 분리
- **장애/에러 (`destructive`)**: `hsl(4, 90%, 55%)` (Warm Scarlet Red)
  - 표현 문법: 브랜드 루비와 절대 혼동되지 않도록 면 채우기 금지. `animate-ping` 펄스 도트, 아웃라인 테두리, `AlertTriangle` 아이콘 3중 결합으로만 표기.
- **경고 (`warning`)**: `hsl(38, 92%, 50%)` (Amber Yellow)
- **정상 (`success`)**: `hsl(150, 75%, 40%)` (Emerald Green)

### 1.3 3-Dial 구성 (루비-샌드 Linear 스타일)
- **랜딩 페이지 (`src/app/page.tsx`)**:
  - `Variance: 5` (정갈하고 예측 가능한 Linear 그리드 구조)
  - `Density: 5` (충분한 호흡과 여백)
  - `Motion: 2` (화려한 3D나 파티클 배제, 150-200ms ease-out의 정제되고 은은한 전환)
- **대시보드 / 위저드 (`src/app/app/page.tsx`)**:
  - `Variance: 3` (예측 가능하고 단정한 콕핏 구조)
  - `Density: 6` (정보 밀도 완화, 과도한 빽빽함 해소)
  - `Motion: 3` (탭 전환 및 핑 레이더의 매끄러운 반응성)

---

## 2. 카피라이팅 프로세스 및 팩트 준수 원칙

- **인프라 팩트 수치 엄밀화**:
  - 링버퍼 내부 큐잉 지연: `0.08ms`
  - 마인크래프트 메인 틱 점유 시간: `0.00ms` (완전 비동기 격리)
  - E2E 디스코드 도착 시간: 실측 `평균 40~50ms` (허위 과장 0.1ms 표기 영구 금지)
- **어휘 쇄신 (비전공자 눈높이 반영)**:
  - 카테고리 뱃지: `초저지연 마인크래프트-디스코드 브릿지`
  - 메인 헤드라인: `서버 TPS 저하 없는 마인크래프트-디스코드 실시간 연동`
  - 서브헤드: `서버에 이벤트가 몰려도 렉이 걸리지 않습니다. 게임 속 사건을 디스코드 알림과 역할에 곧바로 반영합니다.`
  - '관제탑' 어색한 직역투 제거 -> `루비콘 콘솔` 또는 `서버 관리 콘솔`로 통일.
  - 설명용 괄호 병기 전면 제거 및 AI 상투어 100% 배제.

---

## 3. 세부 작업 명세 (Task Breakdown)

### Task 1: 랜딩 페이지(`src/app/page.tsx`) 및 제품 텔레메트리 쇼케이스 개편
1. **헤더 & Hero**:
   - `RotatingHeadline` 완전 제거.
   - 단호한 헤드라인 + 미니멀 서브헤드 + 2개 액션 버튼(`[3분 만에 시작하기]`, `[아키텍처 명세]`).
2. **Hero 제품 텔레메트리 쇼케이스 (`InteractiveSimulator.tsx` -> 실전 제품 목업)**:
   - 장난감 같은 3D 블록 미니어처 전면 배제.
   - **루비-샌드 1px 헤어라인 기반의 정돈된 SaaS 제품 텔레메트리 카드**:
     - **좌측**: 게임 속 실시간 이벤트 스트림 (루비빛 모노스페이스 태그, 타임스탬프).
     - **중앙**: LMAX 링버퍼 지연 텔레메트리 (은은한 1px 회전 원형 게이지, 0.08ms 큐잉 지연, 20.0 TPS 안정).
     - **우측**: 디스코드 실제 다크 테마 규격(#2b2d31)의 정갈한 임베드 알림 카드.
     - **인터랙션 & 모션**: 이벤트 발생 시 1px 웜 샌드 연결선을 따라 은은한 루비빛 림 라이트(Rim light)가 스치며 카드가 부드럽게 갱신되는 150-200ms 트랜지션.
3. **핵심 인프라 3대 명세 카드 (Linear 피처 섹션 구조)**:
   - 1px 웜 샌드 헤어라인 다크 카드 그리드.
   - **WSS 단일 계층 게이트웨이**: 중계 서버 없는 WebSocket 직접 연결.
   - **LMAX Disruptor 링버퍼**: 초당 수천 건의 이벤트 폭증에도 메인 스레드 20 TPS 사수.
   - **디스코드 권한 동기화**: 인게임 권한과 디스코드 역할 0ms 일치화.
4. **온보딩 3단계 요약 카드 (랜딩 페이지)**:
   - 긴 `config.yml` 전체 코드 블록을 제거하여 심리적 장벽 해소.
   - 1단계(봇 초대) -> 2단계(토큰 발급) -> 3단계(핑 확인)의 가벼운 인포그래픽 카드로 대체.

### Task 2: 온보딩 위저드(`OnboardingView.tsx`) 3단계 동선 단일화
1. **Step 1: 디스코드 서버(길드) 연결**:
   - 봇 초대 버튼 (`NEXT_PUBLIC_DISCORD_CLIENT_ID` 환경변수 또는 기본 템플릿 URL).
   - 연결할 길드 ID(`discordGuildId`) 및 서버 이름 입력 및 검증.
   - `tenantId`는 새로고침 시에도 변하지 않는 고정 불변 식별자로 보장.
2. **Step 2: 인스턴스 토큰 발급 & 플러그인 설정 (핵심 집중)**:
   - 인스턴스 이름 입력 후 `[인스턴스 토큰 발급]` 원클릭 실행.
   - 발급된 토큰이 자동 치환된 `plugins/RuBeacon/config.yml` 전체 코드 스니펫 및 원클릭 복사 버튼 제공.
3. **Step 3: 연결 상태 실시간 핑(Ping) 확인**:
   - 마인크래프트 서버 연결 대기 인터랙티브 레이더 뷰.
   - 2초 간격 백엔드 인스턴스 상태 폴링 (`fetchTenantDetail`).
   - `ONLINE` 전환 시 연결 성공 배지와 함께 메인 대시보드(`workflow` 탭)로 자동 진입.

### Task 3: Ru-Beacon 에이전트 (자연어 워크플로우 생성기)
1. **의존성 설치**:
   - `pnpm --filter web-dashboard add @google/genai`
2. **확장 가능한 이벤트/액션 레지스트리 구조**:
   - `TRIGGER_REGISTRY` 및 `ACTION_REGISTRY` 메타데이터 배열 도입.
   - 향후 새 이벤트(`PLAYER_JOIN` 등) 추가 시 레지스트리 선언만으로 LLM 프롬프트, JSON Schema, UI 드롭다운이 자동 확장.
3. **결정 엔진 추상화 (`DecisionEngineAdapter`) & TypeSafe Jev / OpenJev 대비**:
   - `DecisionEngineAdapter` 인터페이스 구축:
     ```typescript
     interface DecisionEngineResult {
       triggerType: string;
       triggerConfig: Record<string, unknown>;
       actionType: string;
       actionConfig: Record<string, unknown>;
       confidence: number; // 0.0 ~ 1.0 (보정된 확신도)
       reasoning: string;
       supported: boolean;
     }
     ```
   - 1차 구현: Gemini 2.5 Flash 기반 `GeminiDecisionAdapter` 구현.
   - 향후 전환: TypeSafe AI의 공식 `api.typesafe.ai` Jev 또는 `OpenJev` 도커 컨테이너 엔드포인트로 무중단 교체 가능하게 결합도 격리.
4. **시맨틱 추론 및 확신도(Confidence Score) 기반 3단계 판정**:
   - 단순 화이트리스트가 아닌 비전문가 일상어("박스 형태로" -> `DISCORD_EMBED`) 의미 매핑.
   - 확신도 >= 0.7: 폼 자동 입력 및 Step 2 즉시 이동.
   - 0.0 < 확신도 < 0.7: "의도가 모호합니다: 혹시 [레벨 달성] 조건을 원하셨습니까?" 선택 배너 노출.
   - 확신도 === 0.0 (도메인 무관 요청): 즉시 거절(HTTP 400) 및 지원 가능한 이벤트 가이드 반환.
5. **타임스탬프 슬라이딩 윈도우 로그 (Sliding Window Log) 및 엄격한 쿼터**:
   - IP/테넌트별 타임스탬프 배열 기반 오래된 기록(trim) 처리.
   - 경계면 급증(Boundary Burst) 0% 차단.
   - **무료 티어: 일일 5회, 분당 2회 상한** (남용 방어).
   - 초과 시 HTTP 429 Too Many Requests 반환.
   - 향후 유료 구독 확장을 위한 `SubscriptionTier` (FREE, PRO, ENTERPRISE) 쿼터 체크 스켈레톤 포함.
6. **UI 연동 (`WorkflowWizard.tsx`)**:
   - **상단 커맨드 바 (Command Bar)**: "Ru-Beacon 에이전트"
   - F-Pattern 인지 흐름(자연어 의도 입력 -> 아래 메인 폼 결과 반영) 준수.
   - 500ms 디바운스 및 로딩 상태 처리 후 Step 2로 자동 연계.

---

## 4. 검증 및 빌드 계획
- `pnpm --filter web-dashboard lint` (TypeScript 컴파일 오류 0건)
- `pnpm --filter web-dashboard test` (단위 테스트 통과)
- Conventional Commit 규격 준수: `feat(dashboard): ...`
- 사용자 승인 전 원격 푸시 절대 금지.