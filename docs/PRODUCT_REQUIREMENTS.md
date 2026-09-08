# Ru-Beacon 제품 요구사항

## 1. 문서 목적

Ru-Beacon이 사용자에게 제공해야 하는 기능과 완료 조건을 정의한다. 구현 구조와 운영 절차는 각각 [REENGINEERING_ARCHITECTURE_BLUEPRINT.md](./REENGINEERING_ARCHITECTURE_BLUEPRINT.md), [OPERATIONS_RUNBOOK.md](./OPERATIONS_RUNBOOK.md)를 따른다.

## 2. 제품 정의

Ru-Beacon은 Minecraft 서버 운영자가 Discord 커뮤니티를 구축하고 운영하는 데 필요한 기능을 하나의 플랫폼에서 설정·관리하도록 돕는 서버 커뮤니티 운영 플랫폼이다.

운영자는 여러 Discord 봇과 수작업 설정을 조합하는 대신, Core 기능과 Sandbox Workflow를 통해 보안·운영·자동화를 구성한다.

## 3. 사용자와 운영 범위

### 3.1 Tenant/Workspace

Ru-Beacon 서비스는 여러 Tenant/Workspace를 관리한다. 하나의 Tenant/Workspace는 다음을 묶는다.

- Discord Community 1개
- Minecraft Network 1개
- 해당 커뮤니티를 관리하는 관리자 팀

Minecraft Network는 Proxy Instance와 여러 Backend Instance로 구성될 수 있다.

### 3.2 주요 사용자

- 서버 소유자: 초기 설정, 보안 정책, 관리자 권한 관리
- 운영 관리자: 승인, 티켓, 워크플로우 실행·감시
- 일반 사용자: 계정 연동, 버튼·모달·출석 등 기능 사용
- 개발 운영자: 배포, 장애 대응, 로그·지표 확인

## 4. Core 기능

### 4.1 계정 후보 등록과 정식 연동

#### 후보 등록

1. 사용자가 Discord 인증 메시지의 연동 버튼을 누른다.
2. 모달에서 Minecraft 닉네임을 입력한다.
3. Ru-Beacon이 정품 계정 API로 닉네임과 UUID를 확인한다.
4. 서버 접속 이력은 이 단계에서 확인하지 않는다.
5. 연결은 `pending` 상태로 저장된다.

#### 정식 인증

다음 두 경로를 모두 지원한다.

- Discord에서 코드를 발급하고 Minecraft에서 `/ru-beacon verify <code>` 입력
- Minecraft에서 코드를 발급하고 Discord 인증 모달에 코드 입력

정식 인증 조건:

- 코드 유효 시간 5분
- 인증 실패 최대 5회
- 성공 즉시 `active` 승격
- 코드 재사용 불가
- 운영 단위·Discord 사용자·Minecraft UUID에 코드 귀속

계정 정책:

- 정품 계정만 지원
- Offline-mode는 숨겨진 테스트 옵션
- Discord 계정당 기본 Minecraft 계정 2개
- 관리자가 제한을 변경할 수 있으며 신규 연결부터 적용
- 하나의 Minecraft 계정은 하나의 Discord 계정에만 연결
- 해제는 기본 관리자만 가능
- Discord Community 탈퇴 시 해당 Tenant의 연결 자동 해제

### 4.2 관리자 2FA

2FA 대상은 Minecraft OP가 아니라 Discord 역할과 사용자 예외 설정으로 결정한다.

- 기본 권한 기준: 관리자 역할
- 사용자별 allow/deny 예외
- 승인 제한 시간 60초
- 승인·거부·타임아웃 시 결과 기록
- Enforce 모드에서 미승인·거부 시 Minecraft 강제 퇴장

정책 모드:

- `Disabled`: 2FA 요청 없이 접속 허용
- `Monitor`: 승인 요청·경고·감사 로그만 수행하고 접속 허용
- `Enforce`: 승인 실패·타임아웃 시 접속 차단

기본값은 `Monitor` 또는 차단 정책 `OFF`이며, 운영자가 명시적으로 Enforce를 활성화해야 한다.

전달 방식:

- 초기 설정에서 인증 채널을 선택하거나 자동 생성
- 요청은 대상 관리자만 접근하는 비공개 스레드에서 처리
- DM은 보조 안내용으로만 사용
- 처리 후 인증 메시지와 스레드 삭제
- 요청·승인·거부·삭제는 감사 로그 기록

### 4.3 권한

- `SERVER_MANAGE`: 서버·모듈·역할·채널 설정
- `CONSOLE_EXECUTE`: 제한된 원격 콘솔 실행
- `RULES_READ`: 워크플로우와 테스트 결과 조회
- `RULES_WRITE`: 워크플로우 생성·수정·삭제
- `RULES_PUBLISH`: 활성화·비활성화·배포·롤백
- `USERS_MANAGE`: 계정 연동·해제와 사용자 관리
- `TICKETS_MANAGE`: 티켓 관리
- `METRICS_VIEW`: 운영 지표 조회
- `AUDIT_LOG_VIEW`: 감사 로그 조회

### 4.4 양방향 채팅 릴레이

- Minecraft 전체 채팅을 허용된 Discord 채널로 전달
- 허용 Discord 채널의 메시지를 Minecraft로 전달
- 사용자명·역할·프로필 표시 형식 커스터마이즈
- Discord→Minecraft 입력 채널은 운영자가 선택
- 자체 채팅 채널 분할은 MVP에 포함하지 않음
- 외부 채팅 플러그인 호환성은 후속 검토
- 출력 대상의 문법과 보안 문제가 없는 한 포맷·특수문자를 유지

### 4.5 제한된 원격 콘솔

- 한 요청당 한 명령어
- 관리자 설정 가능한 allowlist
- 삭제할 수 없는 하드 차단 명령어 목록
- 명령어와 인자 별도 검증
- 실행 전 확인 버튼
- 결과는 호출자에게 Ephemeral 응답
- 요청·명령어·결과·실패 사유 감사 로그 기록
- 여러 명령은 Sandbox의 명령 실행 노드를 순차 연결
- 운영체제 셸 실행은 제공하지 않음

### 4.6 기본 모니터링

MVP는 다음을 수집한다.

- Minecraft TPS/MSPT
- 플레이어 수
- JVM 메모리
- Proxy·Backend 연결 상태
- Redis·PostgreSQL 연결 상태
- Discord API 오류·429·지연
- 이벤트 처리 지연·처리량
- 워크플로우 실패·부분 실패
- 출석 보상 성공·실패·소진

## 5. Sandbox Workflow

Sandbox는 고정 템플릿 목록이 아니라 사용자가 노드·블록을 연결해 기능을 만드는 확장 계층이다.

### 5.1 표준 노드 범주

- Trigger: Minecraft 이벤트, Discord 상호작용, 시스템 이벤트
- Condition: 역할·레벨·사용자·값 확인
- Variable: 기본 변수·사용자 변수·노드 간 값 전달
- Control: 조건 분기·Parallel·종료·실행 제한
- Discord Action: 메시지·버튼·모달·DM·역할·투표·채널
- Minecraft Action: 플레이어 메시지·명령어·네트워크 공지

### 5.2 기본 이벤트

- Minecraft: 레벨업, 업적 달성, Ru-Beacon 명령어
- Discord: 버튼 클릭, 모달 제출, 슬래시 명령어, 메시지·투표 상호작용
- System: 출석 초기화, 워크플로우 활성화, 외부 이벤트, 연결 상태 변경

### 5.3 실행 규칙

- 연결된 직선 흐름: 순차 실행
- `Parallel` 블록: 병렬 분기 실행
- Condition: 명시적 분기
- 병렬 실패 시 이미 완료된 외부 부작용은 롤백하지 않음
- 다른 병렬 분기는 계속 실행
- 전체 결과는 `PARTIAL_FAILURE`로 기록
- 자동 재시도 없음
- 실패 노드·입력·결과·사유 기록

### 5.4 워크플로우 상태

`DRAFT → TESTING → ACTIVE → INACTIVE → ARCHIVED`

- Draft: 실제 동작 없음
- Testing: Dry Run
- Active: 실제 이벤트 수신
- 수정본: Publish 후 활성화
- 활성 버전: 워크플로우당 하나
- 이전 버전 Rollback 지원

### 5.5 변수와 검증

기본 변수 예시:

`{User_Nickname}`, `{Minecraft_UUID}`, `{Discord_User}`, `{Discord_User_ID}`, `{Server_Name}`, `{Event_Name}`, `{Event_Value}`, `{Reward_Name}`, `{Remaining_Quantity}`, `{Current_Time}`

- 변수 선택 UI와 자동완성 제공
- 사용자 정의 변수 지원
- 존재하지 않는 변수는 저장·활성화 오류
- 메시지·버튼·모달 길이와 값 형식 자동 검증
- Minecraft 명령어는 구조화된 명령·인자와 allowlist 적용
- DB는 파라미터 바인딩만 사용
- 운영체제 셸 실행 금지

## 6. 대표 템플릿

### 6.1 Minecraft 시작형 보상 수령

레벨업·업적·Ru-Beacon 명령어 중 하나가 트리거가 된다.

```text
Minecraft 이벤트
→ Discord 채널 메시지
→ 보상 수령 버튼
→ 계정·조건 확인
→ Minecraft 명령어 실행
→ Discord DM 또는 Ephemeral 결과
```

### 6.2 Discord 시작형 일일 출석 보상

```text
매일 09:00(설정된 IANA 시간대)
→ Discord 출석 버튼
→ Minecraft 계정 active·온라인 확인
→ 당일 중복 출석 확인
→ 남은 보상 원자적 예약
→ Minecraft 명령어 실행
→ 성공 확정 또는 실패 시 수량 복구
```

- 기본 보상 수량 100개, 관리자 변경 가능
- 동일 보상·랜덤박스·역할별 보상 지원
- 운영 단위별 quota
- 소진 시 버튼 disabled 업데이트
- 클릭 시 서버에서 잔여 수량 재확인
- 결과는 커스터마이즈 가능한 Ephemeral Message

## 7. 기능형 웹 대시보드

- Discord 서버·역할·채널 드롭다운 초기 설정
- 봇 권한 검사
- 모듈·워크플로우 조회
- 노드 추가·삭제·연결
- 순차·Parallel 블록 편집
- 메시지·버튼·모달·변수 설정
- 저장·테스트·활성화·비활성화
- 버전 Publish·Rollback
- 실행 로그·실패 원인 조회
- 감사 로그와 운영 지표 조회

시각적 디자인보다 기능 검증을 우선한다.

## 8. MVP와 Phase 1

MVP 포함:

- Core 전체
- 양방향 Sandbox 실행 기반
- 대표 템플릿 2개
- 기능형 웹 대시보드
- 최소 모니터링
- Docker Compose 기반 서비스
- 테스트·CI
- 외부 확장을 고려한 노드 계약

Phase 1 기반:

- PostgreSQL·Redis
- API·Bot·Worker
- 전용 Minecraft Plugin·Proxy Adapter
- 통합 테스트
- 장애 대응·감사·보안 통제

후순위:

- Microsoft OAuth
- 외부 개발자 SDK
- 템플릿 마켓플레이스
- 모든 Discord API 노드
- 고급 Kubernetes·멀티클라우드
- 고급 디자인·협업 UX

## 9. 완료 조건 원칙

각 기능은 다음을 모두 만족해야 한다.

- 정상 흐름 테스트
- 실패 흐름 테스트
- 권한 검증
- 중복·동시성 검증
- 감사 로그 확인
- 관측성 지표 확인
- 장애 시 정책 확인
- 문서화된 완료 기준 충족
