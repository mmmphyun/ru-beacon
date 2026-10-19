# Ru-Beacon 베타 전환 5단계 원자적 구현 계획 (BETA_READINESS_PLAN.md)

이 문서는 알파 프로토타입 단계의 Ru-Beacon을 실서비스 외부 베타 테스터 유입이 가능한 상태로 전환하기 위한 **공식 실행 명세서**입니다.
Flash 모델 자율 주행 시 `.githooks/pre-commit` 하드 가드(단일 서브모듈, 최대 4개 파일, 최대 250줄, `src/main` + `src/test` 동시 스테이징)를 기계적으로 준수하도록 5개의 독립된 원자적 커밋 단위로 설계되었습니다.

---

## 1. 배경 및 해결할 5대 핵심 결함

1. **OAuth2 콜백 리다이렉트 누락 및 DTO 불일치**:
   - `api-service`의 `AuthRoute.kt` 콜백이 세션 쿠키 발급 후 프론트엔드(`/app`)로 리다이렉트하지 않고 JSON만 응답함.
   - `AuthModels.kt`의 `UserProfileDto`/`TenantSummaryDto`와 `web-dashboard`의 `UserProfile`/`TenantMemberItem` 간 필드명(`id` vs `tenantId`, `name` vs `guildName`) 불일치.
   - 신규 가입 유저가 온보딩 시 필요한 Discord 소유 길드 목록(`ownedGuilds`) 누락.
2. **프론트엔드 API 클라이언트의 무조건적 Mock Fallback**:
   - `lib/api.ts`가 401/403/500 에러 발생 시에도 인메모리 `mockUserProfile`로 폴백하여 미인증 상태를 숨김.
3. **트리거 이벤트 타입(`event_type`) 명칭 불일치**:
   - 위저드는 `"MINECRAFT_LEVEL_UP"` 등을 생성하나, 플러그인과 봇은 `"minecraft.player.level_up"` 등을 발행하여 `CachedWorkflowLookup`에서 100% 매칭 실패.
4. **조건 분기(`CONDITION_BRANCH`) 입력 스키마 및 페이로드 변수 추출 누락**:
   - 위저드는 `{ field, operator, expected_value }`를 생성하나, 워커 엔진은 `{ left, operator, right }`를 기대함.
   - `VariableResolver.kt`가 이벤트 `payload`의 `newLevel`, `playerName`, `advancementId`, `custom_id`를 예약 변수로 추출하지 않음.
5. **위저드 단일 액션 제약 및 미구현 액션 노출**:
   - 백엔드 미구현 노드(`DISCORD_ADD_ROLE`)가 선택지에 노출됨.
   - 대표 기능인 선착순 출석체크 보상 파이프라인(`RESERVE` -> `MINECRAFT_DISPATCH_COMMAND` -> `COMMIT` + `DISCORD_SEND_MESSAGE`)을 위저드에서 생성할 수 없음.

---

## 2. 5단계 원자적 구현 상세 명세 (Atomic Commits)

### [Step 1] `workflow-worker`: 페이로드 변수 추출 및 조건 분기 호환성 보강
- **목표**: 마인크래프트 레벨업/발전과제 및 디스코드 인터랙션의 페이로드 데이터를 워크플로우 템플릿 변수로 자동 바인딩하고, 위저드 조건 분기 AST를 수용함.
- **대상 서브모듈**: `workflow-worker`
- **수정 및 스테이징 파일 (3개)**:
  1. `workflow-worker/src/main/kotlin/com/rubeacon/worker/engine/VariableResolver.kt`
  2. `workflow-worker/src/main/kotlin/com/rubeacon/worker/engine/NodeExecutors.kt`
  3. `workflow-worker/src/test/kotlin/com/rubeacon/worker/engine/VariableResolverTest.kt`
- **구체적 구현 내용**:
  - `VariableResolver.kt`:
    - `DEFAULT_VARIABLE_NAMES`에 `"Level"`, `"Advancement"`, `"Custom_ID"`, `"Command"` 추가.
    - `extractDefaultVariables()`에서 `event.payload`의 `newLevel`(`Level`), `playerName`(`User_Nickname` 우선순위 바인딩), `advancementId`(`Advancement`), `custom_id`(`Custom_ID`), `command`(`Command`)를 안전하게 추출.
  - `NodeExecutors.kt`:
    - `ConditionBranchExecutor`에서 `inputs["left"]`가 없으면 `inputs["field"]`를 `"{${field}}"`로 대체.
    - `inputs["right"]`가 없으면 `inputs["expected_value"]`로 대체.
    - 연산자에 `"GREATER_OR_EQUAL"`을 `"GTE"`와 동일하게 지원.
  - `VariableResolverTest.kt`: 페이로드 변수 치환 및 조건 분기 평가 단위 테스트 작성.
- **검증 및 커밋**:
  ```powershell
  .\gradlew.bat :workflow-worker:test
  git add workflow-worker/src/main/kotlin/com/rubeacon/worker/engine/VariableResolver.kt workflow-worker/src/main/kotlin/com/rubeacon/worker/engine/NodeExecutors.kt workflow-worker/src/test/kotlin/com/rubeacon/worker/engine/VariableResolverTest.kt
  git commit -m "feat(worker): 이벤트 페이로드 예약 변수 추출 및 조건 분기 호환성 보강"
  ```

---

### [Step 2] `api-service`: OAuth2 프론트엔드 리다이렉트 및 소유 길드 목록 반환
- **목표**: Discord OAuth2 콜백 완료 시 웹 대시보드로 안전하게 리다이렉트하고, 신규 가입 유저의 온보딩을 위해 소유 길드 목록을 반환함.
- **대상 서브모듈**: `api-service`
- **수정 및 스테이징 파일 (4개)**:
  1. `api-service/src/main/kotlin/com/rubeacon/api/auth/AuthModels.kt`
  2. `api-service/src/main/kotlin/com/rubeacon/api/auth/DiscordOAuthService.kt`
  3. `api-service/src/main/kotlin/com/rubeacon/api/routes/AuthRoute.kt`
  4. `api-service/src/test/kotlin/com/rubeacon/api/TenantAuthAndRbacTest.kt`
- **구체적 구현 내용**:
  - `DiscordOAuthService.kt`:
    - `AuthConfig`에 `val frontendUrl: String = System.getenv("FRONTEND_URL") ?: "http://localhost:3000"` 추가.
  - `AuthModels.kt`:
    - `UserProfileDto`에 `val ownedGuilds: List<DiscordGuildInfo> = emptyList()` 필드 추가.
  - `AuthRoute.kt`:
    - `GET /api/v1/auth/discord/callback` 완료 시 `call.respondRedirect("${AuthConfig.frontendUrl}/app")` 호출.
    - `GET /api/v1/auth/me` 응답 시 `cachedGuilds.filter { it.isOwner }`를 `ownedGuilds`로 매핑하여 반환.
  - `TenantAuthAndRbacTest.kt`: `/api/v1/auth/me` 호출 시 `ownedGuilds` 반환 검증 테스트 추가.
- **검증 및 커밋**:
  ```powershell
  .\gradlew.bat :api-service:test --tests "com.rubeacon.api.TenantAuthAndRbacTest"
  git add api-service/src/main/kotlin/com/rubeacon/api/auth/AuthModels.kt api-service/src/main/kotlin/com/rubeacon/api/auth/DiscordOAuthService.kt api-service/src/main/kotlin/com/rubeacon/api/routes/AuthRoute.kt api-service/src/test/kotlin/com/rubeacon/api/TenantAuthAndRbacTest.kt
  git commit -m "feat(api): OAuth2 콜백 리다이렉트 및 미온보딩 소유 길드 목록 반환 추가"
  ```

---

### [Step 3] `web-dashboard`: 실서버 API 연동 및 Discord 로그인 게이트 구축
- **목표**: Mock Fallback을 개발/테스트 환경으로 한정하고, 미인증 사용자에게 Discord 로그인 게이트 화면을 노출하며 백엔드 DTO를 정상 정규화함.
- **대상 서브모듈**: `web-dashboard`
- **수정 및 스테이징 파일 (4개)**:
  1. `web-dashboard/src/types/workflow.ts`
  2. `web-dashboard/src/lib/api.ts`
  3. `web-dashboard/src/app/app/page.tsx`
  4. `web-dashboard/src/__tests__/api-mock.test.ts`
- **구체적 구현 내용**:
  - `types/workflow.ts`:
    - `UserProfile`에 `ownedGuilds?: { id: string; name: string }[]` 추가.
  - `lib/api.ts`:
    - `IS_DEV_MOCK = process.env.NEXT_PUBLIC_DEV_MOCK === "true" || process.env.NODE_ENV === "test"` 조건 도입.
    - `fetchUserProfile()`: 401 수신 시 `IS_DEV_MOCK`가 아니면 `null` 반환. 백엔드 `UserProfileDto.tenants`를 프론트엔드 `TenantMemberItem`(`tenantId`, `guildName`, `role`)으로 정규화.
    - `getDiscordLoginUrl()` (`${API_BASE}/api/v1/auth/discord/login`), `logoutUser()` (`POST /api/v1/auth/logout`) 추가.
    - `fetchTenantDetail()`: 백엔드 응답 `res.data.id`를 `tenantId`로 정규화 매핑.
  - `app/app/page.tsx`:
    - `user === null`인 경우 **"Discord로 로그인"** 게이트 카드 UI 노출.
    - `user.tenants.length === 0`인 신규 유저는 온보딩(`onboarding`) 탭으로 자동 유도.
    - `DevMock` 스위처를 `NEXT_PUBLIC_DEV_MOCK === "true"`일 때만 표시되도록 격리.
- **검증 및 커밋**:
  ```powershell
  npm --prefix web-dashboard test -- src/__tests__/api-mock.test.ts
  git add web-dashboard/src/types/workflow.ts web-dashboard/src/lib/api.ts web-dashboard/src/app/app/page.tsx web-dashboard/src/__tests__/api-mock.test.ts
  git commit -m "feat(dashboard): Discord OAuth2 로그인 게이트 및 백엔드 DTO 정규화 연동"
  ```

---

### [Step 4] `web-dashboard`: 온보딩 길드 드롭다운 자동 완성 및 설정 보강
- **목표**: 온보딩 Step 1에서 사용자가 소유한 Discord 길드를 드롭다운으로 한 번에 선택할 수 있도록 개선하고, 관리자 역할 ID 및 보안 정책 설정을 연동함.
- **대상 서브모듈**: `web-dashboard`
- **수정 및 스테이징 파일 (2개)**:
  1. `web-dashboard/src/components/onboarding/OnboardingView.tsx`
  2. `web-dashboard/src/__tests__/OnboardingView.test.tsx`
- **구체적 구현 내용**:
  - `OnboardingView.tsx`:
    - Props에 `ownedGuilds?: { id: string; name: string }[]`를 전달받아, 소유 길드가 있을 경우 선택 시 `discordGuildId`, `name`, `tenantId`가 자동 완성되는 선택 UI 제공 (수동 입력 병행 지원).
    - 관리자 역할 ID(`adminRoleId`) 및 2FA 정책(`MONITOR` / `ENFORCE`) 필드 연결.
    - Step 3의 `simulatePing`은 `IS_DEV_MOCK` 상태에서만 노출되도록 격리.
- **검증 및 커밋**:
  ```powershell
  npm --prefix web-dashboard test -- src/__tests__/OnboardingView.test.tsx
  git add web-dashboard/src/components/onboarding/OnboardingView.tsx web-dashboard/src/__tests__/OnboardingView.test.tsx
  git commit -m "feat(dashboard): 온보딩 길드 자동 선택 및 테넌트 초기 설정 필드 보강"
  ```

---

### [Step 5] `web-dashboard`: 위저드 표준 이벤트 매핑 및 다중 노드 파이프라인 생성
- **목표**: 위저드 트리거를 백엔드 표준 이벤트명으로 매핑하고, 단일 액션 한계를 벗어나 복합 액션 및 출석체크 보상 파이프라인을 온전히 생성하도록 확장함.
- **대상 서브모듈**: `web-dashboard`
- **수정 및 스테이징 파일 (4개)**:
  1. `web-dashboard/src/types/workflow.ts`
  2. `web-dashboard/src/lib/workflow-generator.ts`
  3. `web-dashboard/src/components/workflow/WorkflowWizard.tsx`
  4. `web-dashboard/src/__tests__/workflow-generator.test.ts`
- **구체적 구현 내용**:
  - `workflow-generator.ts`:
    - 트리거 타입을 표준 이벤트명(`"minecraft.player.level_up"`, `"minecraft.player.advancement_done"`, `"discord.button.clicked"`, `"discord.command.executed"`)으로 매핑.
    - `CONDITION_BRANCH` AST 생성 시 `{ left: "{${field}}", operator: "GREATER_THAN_OR_EQUAL", right: value }` 표준 포맷으로 출력.
    - 미구현 노드 `DISCORD_ADD_ROLE` 제거.
    - 액션 타입 4종 지원:
      1. `DISCORD_SEND_MESSAGE` (단독 디스코드 알림)
      2. `MINECRAFT_DISPATCH_COMMAND` (단독 인게임 명령어)
      3. `COMMAND_AND_DISCORD` (명령어 실행 후 디스코드 알림 동시 체이닝)
      4. `ATTENDANCE_REWARD_PIPELINE` (`RESERVE` -> `MINECRAFT_DISPATCH_COMMAND` -> `COMMIT` + `DISCORD_SEND_MESSAGE` 4단계 체이닝)
  - `WorkflowWizard.tsx`: 복합 액션 및 출석체크 보상 파이프라인 입력 폼 UI 구성.
- **검증 및 커밋**:
  ```powershell
  npm --prefix web-dashboard test
  git add web-dashboard/src/types/workflow.ts web-dashboard/src/lib/workflow-generator.ts web-dashboard/src/components/workflow/WorkflowWizard.tsx web-dashboard/src/__tests__/workflow-generator.test.ts
  git commit -m "feat(dashboard): 위저드 표준 이벤트 매핑 및 다중 노드 파이프라인 생성 구현"
  ```
