# Ru-Beacon Agent Operation Rules (AGENTS.md)

이 파일은 Antigravity 코딩 에이전트가 `/goal` 모드 자율 주행을 포함한 모든 개발 작업 시 매 턴 최상위 우선순위로 강제 적용받는 불변 운영 규칙입니다.

---

## 1. Ponytail 강제 강령 (The Lazy Senior Dev Protocol)

모든 코드 작성, 리팩터링, 설계 시 `/ponytail full` 규칙을 무조건 적용합니다. 대화 세션이 길어지거나 컨텍스트가 압축되어도 절대 임의로 오버엔지니어링으로 회귀하지 않습니다.

### 1.1 The Ladder (행동 사다리 - 위에서부터 검토)
1. **Does this need to exist at all? (YAGNI)**: 추측에 의한 미래 대비 코드, 쓰이지 않는 인터페이스/팩토리는 작성하지 않고 스킵한다.
2. **Already in this codebase?**: 프로젝트 내 이미 존재하는 유틸, 클래스, 확장 함수가 있는지 먼저 확인하고 재사용한다.
3. **Kotlin stdlib does it?**: Kotlin 표준 라이브러리 및 코루틴 내장 기능으로 가능한 것은 별도 클래스를 만들지 않는다.
4. **Already-installed dependency solves it?**: Ktor, Exposed, Kord 등 이미 도입된 라이브러리 기능으로 해결한다.
5. **Fewest files & Shortest diff**: 파일 분할을 최소화하고 가장 짧은 동작 코드(Shortest working diff)를 우선한다.

### 1.2 금지 사항 (Strict Prohibitions)
- 구현체가 1개뿐인 인터페이스(Single-implementation interface) 생성 금지.
- 생성하는 프로덕트가 1개뿐인 팩토리(Factory) 패턴 금지.
- 단 한 번도 변경되지 않는 상수를 위한 외부 설정 파일/클래스 남발 금지.
- 불필요한 DTO/도메인 계층 간 1:1 단순 복제 매퍼(Mapper) 남발 금지.

### 1.3 생략 금지 대상 (Non-Negotiable)
- 외부 경계(API, WebSocket, Discord 입력)에서의 입력값 검증(Validation).
- 인스턴스 인증 토큰, allowlist, 권한 확인 등 보안 검증.
- 데이터 손실을 방지하는 DB 트랜잭션 및 에러 핸들링.

---

## 2. Searching-Codebases 강제 강령 (AST Search & Token Guards)

코드 탐색, 분석, 디버깅 시 토큰 낭비와 컨텍스트 오염을 원천 차단하기 위해 다음 규칙을 강제합니다.

### 2.1 파일 전체 읽기 원천 금지 (No Full-File Dumps)
- `view_file` 호출 시 인자 없이 파일 전체를 읽는 행위를 엄격히 금지한다.
- 반드시 `StartLine`과 `EndLine`을 지정하여 필요한 클래스/함수 본문만 100~200줄 단위로 슬라이싱하여 읽는다.

### 2.2 핀포인트 앵커 검색 우선 (Grep-First Search)
- 코드를 탐색할 때는 파일 목록을 뒤적거리지 말고, 반드시 `grep_search`로 진입점 앵커를 먼저 검색한다:
  - 클래스 선언: `class ClassName`
  - 함수/메서드: `fun methodName(`
  - 라우트/엔드포인트: `route(`, `get(`, `post(`, `webSocket(`
  - 이벤트 핸들러: `on<`, `handle(`
- 위치가 특정된 후에만 해당 라인 범위를 `view_file`로 정밀 발췌한다.

---

## 3. 자율 주행(/goal) 커밋 및 롤백 프로토콜

에이전트가 `/goal` 모드로 주행할 때 무한 수정 땜질(Thrashing Loop)에 빠지지 않도록 Git 워크플로우를 강제합니다.

### 3.1 Green-State Commit (성공 시 즉시 커밋)
- 기능 작업 단위: `도메인/엔티티` → `인코드 테스트(MockBukkit/Testcontainers)` → `구현체`
- `./gradlew test` 또는 빌드가 통과하면 즉시 터미널에서 커밋을 실행한다:
  ```powershell
  git add .
  git commit -m "<type>(<scope>): <한글 요약>"
  ```
- 커밋 메시지는 Conventional Commits 형식을 엄격히 준수한다.

### 3.2 3-Strike Rollback (3회 연속 실패 시 강제 롤백)
- 동일한 컴파일 에러 또는 테스트 실패를 해결하기 위한 수정이 3회 연속 실패할 경우:
  - 기존 코드를 계속 누더기로 수정하지 않는다.
  - 즉시 직전 정상 커밋으로 롤백한다:
    ```powershell
    git reset --hard HEAD
    ```
  - 롤백 후 동일한 코드를 반복하지 말고, 설계를 단순화하거나 다른 방식으로 재시도한다.

### 3.3 우회 플래그 및 강제 명령 절대 금지 (Bypass Prohibition)
- 하드 가드(Git 훅)를 건너뛰기 위한 `--no-verify` 또는 `-n` 플래그 사용을 엄격히 금지한다.
- 원격 히스토리를 덮어쓰는 `git push --force` 사용을 엄격히 금지한다.
- 훅에서 거부당하면 커밋 메시지를 컨벤션에 맞게 수정하여 정상 재시도한다.

### 3.4 1인 개발 트렁크 기반 운용 (Trunk-Based Commit & Push)
- 1인 개발 자율 주행에서는 PR/브랜치 분기 오버헤드를 배제하고 `main` 브랜치에 직접 원자적 커밋(Green-State Commit)을 쌓는다.
- 기능 마일스톤 완료 시 즉시 `git push origin main`으로 원격에 동기화한다.

---


## 4. 마일스톤 분할 실행 지침

- 단일 `/goal` 호출로 전체 서비스를 한 번에 구현하려 하지 않는다.
- 모든 자율 주행은 **[docs/MILESTONES.md](docs/MILESTONES.md)**에 정의된 마일스톤 체크리스트를 단일 기준으로 삼는다.
- 새 세션에서 에이전트는 `docs/MILESTONES.md`의 미완료 마일스톤 1개만 완수하고, 해당 마일스톤의 체크박스 `[ ]`를 `[x]`로 수정한 뒤 커밋 및 원격 푸시를 완료해야 한다.

