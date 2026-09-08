# Ru-Beacon 개발 컨벤션

## 문서 역할

이 문서는 Ru-Beacon을 구현·리뷰·테스트할 때 지켜야 할 공통 규칙을 정의한다. 제품 요구사항은 [PRODUCT_REQUIREMENTS.md](PRODUCT_REQUIREMENTS.md), 구조는 [REENGINEERING_ARCHITECTURE_BLUEPRINT.md](REENGINEERING_ARCHITECTURE_BLUEPRINT.md), 이벤트 형식은 [EVENT_CONTRACTS.md](EVENT_CONTRACTS.md)를 기준으로 한다.

## 1. 기본 원칙

- 읽기 쉬운 코드와 작은 책임을 우선한다.
- API, Bot, Worker, Minecraft Module의 경계를 코드 구조에도 반영한다.
- 도메인 규칙은 프레임워크나 Discord·Minecraft 어댑터에 직접 묶지 않는다.
- 외부 입력은 신뢰하지 않고 경계에서 검증·정규화한다.
- 실패를 숨기지 않고 구조화된 결과와 감사 로그로 남긴다.
- 확장 지점은 실제 요구가 있는 곳에만 만든다.

## 2. 코드 스타일과 구조

- Kotlin, JVM 21을 기준으로 한다.
- 패키지 루트는 com.rubeacon으로 통일하고 서비스별 최상위 모듈을 분리한다.
- 클래스·함수는 한 가지 책임을 갖고, 이름만 추상적인 Manager·Util·Helper를 남발하지 않는다.
- 불변 값과 명시적 결과 타입을 우선한다. nullable 값은 의미가 분명할 때만 사용한다.
- 도메인 계층은 포트 인터페이스를 정의하고, DB·Redis·Discord·Minecraft 구현은 어댑터 계층에 둔다.
- 설정값·타임아웃·권한·상태 문자열은 코드에 흩뿌리지 않고 타입화된 설정으로 관리한다.
- 시간은 저장 시 UTC를 기준으로 하고, 화면 표시 시간대는 Tenant 설정으로 변환한다.
- 공개 API와 도메인 규칙에는 KDoc 또는 동등한 설명을 남긴다.
- 로그 메시지에는 비밀번호·토큰·일회성 코드·전체 개인정보를 남기지 않는다.

## 3. 비동기와 이벤트 처리

- 서비스 생명주기에 속한 Coroutine과 Executor만 사용하고 raw thread를 직접 만들지 않는다.
- 이벤트 처리에는 명시적인 timeout, cancellation, backpressure 경계를 둔다.
- 이벤트 envelope와 command/result 계약은 [EVENT_CONTRACTS.md](EVENT_CONTRACTS.md)와 일치해야 한다.
- idempotency key와 event_id를 이용해 중복 처리 정책을 구현한다.
- 자동 재시도는 기본값으로 추가하지 않는다. 재시도가 필요하면 결정 로그와 이벤트 계약을 먼저 갱신한다.
- 순차 워크플로우는 실패 시 중단하고, Parallel 블록은 다른 branch를 계속 실행한다. 부분 성공을 롤백한다고 가정하지 않는다.
- Redis는 장기 원장이 아니며, 필요한 영속 상태와 감사 기록은 PostgreSQL에 기록한다.

## 4. Git, 브랜치, 커밋, PR

- main은 항상 배포 가능한 상태를 유지한다.
- 브랜치는 feature/, fix/, refactor/, docs/, test/, chore/ 접두사를 사용한다.
- 한 브랜치에는 하나의 목적만 담고, 문서 변경과 동작 변경을 가능한 한 분리한다.
- 커밋 제목은 Conventional Commits 형식의 type(scope): summary를 사용한다.
- 커밋은 하나의 논리적 변경 단위로 만들고, 포맷 전면 변경을 기능 커밋과 섞지 않는다.
- PR에는 문제, 변경 범위, 설계 결정, 테스트 결과, 운영 영향, 롤백 방법을 포함한다.
- API·이벤트·DB 스키마 변경은 호환성, 마이그레이션, 버전 전략을 PR에 명시한다.
- 비밀값이나 실제 운영 데이터가 포함된 파일은 커밋하지 않는다.

## 5. 테스트 작성 규칙

### 5.1 계층

- Unit test: 도메인 규칙, 권한, 변수 검증, 조건 분기, 멱등성
- Integration test: PostgreSQL·Redis·서비스 어댑터와의 계약
- Contract test: Discord·Minecraft Module·Worker 간 이벤트와 명령 형식
- End-to-end test: 주요 사용자 흐름과 공개 베타 배포 경로
- Failure test: 연결 끊김, timeout, 중복 이벤트, 부분 병렬 실패, 토큰 만료, 저장 실패

### 5.2 작성 기준

- Given-When-Then 구조와 행동 중심 이름을 사용한다.
- 성공 케이스만으로 완료 처리하지 않고 거부·경계값·재실행을 검증한다.
- 테스트는 시간·네트워크·실행 순서에 의존하지 않게 한다.
- 외부 서비스는 계약을 고정한 테스트 대역으로 격리하되, 실제 통합 테스트도 별도 유지한다.
- 테스트가 실패하면 원인을 숨기기 위해 flaky retry를 추가하지 않는다.
- 새 노드·이벤트·권한·상태를 추가할 때 최소 하나의 단위 테스트와 계약 테스트를 함께 추가한다.
- CI는 lint, compile, unit, integration, contract 테스트를 실행하며 배포 전 E2E를 실행한다.

## 6. 보안 개발 규칙

- 모든 요청에 Tenant와 actor scope를 확인한다. ID를 요청에 넣었다고 권한이 부여된 것으로 간주하지 않는다.
- SQL은 파라미터 바인딩을 사용하고 문자열 조합으로 만들지 않는다.
- 사용자 변수와 텍스트는 명령어·SQL·HTML·Markdown·멘션 경계를 구분해 출력 문맥별로 검증·정제한다.
- Minecraft 명령은 명령어별 인자 스키마를 검증하며 OS 셸이나 임의 명령 실행으로 확장하지 않는다.
- 원격 콘솔은 관리자 allowlist와 불변 hard deny를 함께 사용하고 실행 전 확인을 요구한다.
- 토큰·비밀번호·OAuth 자격 증명은 secret manager 또는 환경 주입을 사용하고 로그·예외·응답에 포함하지 않는다.
- instance별 토큰을 사용하고 TLS를 적용한다. 토큰은 최소 권한·만료·폐기 경로를 갖는다.
- 2FA는 지정 관리자 역할을 대상으로 하며 Monitor가 기본값이다. Enforce는 운영자가 동의해 켜는 설정이다.
- 인증 코드·보상·권한 변경·명령 실행은 1회성·만료·중복 방지·감사 기록을 적용한다.
- IP는 일반 로그에 기록하지 않는다. 보안 사건에 필요한 수집은 고지·접근 통제·보존기간을 함께 정의한다.
- 감사 로그는 관리자만 조회하며, Discord 사용자·Minecraft 사용자·워크플로우·결과·실패 사유를 포함한다.

## 7. LLM 협업 및 자율 주행(/goal) 규칙

- LLM이 생성한 변경도 동일한 PR·테스트·보안 검토를 통과해야 한다.
- 구현 전에 요구사항 ID, 영향 서비스, 이벤트·DB 변경을 기록한다.
- 한 번에 큰 자동 변경을 적용하지 말고 작은 변경 단위로 diff와 테스트를 확인한다.
- 근거가 없는 기술 선택이나 완료 주장은 문서에 쓰지 않는다.
- 보류한 단순화·확장 지점은 [DECISION_LOG.md](DECISION_LOG.md)에 남긴다.

### 7.1 자율 주행(/goal) 커밋 및 롤백 프로토콜
- **Green-State Commit**:
  - TDD / Slice 단위(도메인 모델 -> 테스트 -> 구현체)로 기능을 잘게 쪼개어 작업한다.
  - `./gradlew test` 또는 빌드가 통과하면 즉시 `git add . && git commit -m "<type>(<scope>): <요약>"`을 실행하여 정상 동작 체크포인트를 히스토리에 기록한다.
  - 커밋 메시지는 규칙 4의 Conventional Commits(`<type>(<scope>): <한글 요약>`)를 엄격히 준수한다.
- **3-Strike Rollback**:
  - 컴파일 오류나 테스트 실패 발생 시 파일을 덮어쓰며 무한 땜질(Thrashing Loop)을 하지 않는다.
  - 동일한 에러를 해결하기 위한 수정 시도가 3회 연속 실패할 경우, 즉시 `git reset --hard HEAD`를 실행하여 직전 정상 커밋으로 롤백한다.
  - 롤백 후 동일한 코드를 재반복하지 말고, 인터페이스나 아키텍처 접근법을 재설계하여 새로운 방식으로 구현한다.

