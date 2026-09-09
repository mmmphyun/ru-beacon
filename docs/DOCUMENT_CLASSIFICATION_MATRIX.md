# Ru-Beacon 문서 분류 및 교차검증표

이 문서는 기존 문서의 내용을 최종 문서 체계로 재배치하기 위한 작업표다.
최종 요구사항과 아키텍처는 이 표가 아니라 각각의 권위 문서에 기록한다.

## 문서 역할

| 문서 | 권위 범위 | 핵심 질문 |
|---|---|---|
| `PROJECT_ANALYSIS_REPORT.md` | 문제·배경·재설계 목표 | 왜 만드는가? |
| `PRODUCT_REQUIREMENTS.md` | 사용자·기능·완료 조건·MVP | 무엇을 제공해야 하는가? |
| `REENGINEERING_ARCHITECTURE_BLUEPRINT.md` | 구조·책임·데이터 흐름·기술·비기능 요구사항 | 어떻게 구현하는가? |
| `EVENT_CONTRACTS.md` | 이벤트 봉투·payload·전달 보장 | 서비스가 무엇을 주고받는가? |
| `OPERATIONS_RUNBOOK.md` | 배포·장애·복구·운영 절차 | 운영 중 무엇을 하는가? |
| `DEVELOPMENT_CONVENTIONS.md` | 코드·Git·테스트·보안·LLM 개발 규칙 | 어떻게 개발하고 검증하는가? |
| `PORTFOLIO_EVIDENCE_PLAN.md` | 클라우드 역량·실험·공개 증거 | 무엇으로 역량을 증명하는가? |
| `DECISION_LOG.md` | 결정의 배경·변경 이력 | 왜 이 결정을 했는가? |
| `DATABASE_SCHEMA.sql` | Flyway V1 RDBMS DDL 및 인덱스 | 데이터베이스 구조는 무엇인가? |
| `TRANSPORT_PROTOCOL_SPEC.md` | WSS 및 Redis Streams 통신 상세 | 전송 계층은 어떻게 동작하는가? |
| `WORKFLOW_ENGINE_SPEC.md` | 워크플로우 AST 및 실행 인터페이스 | 워크플로우 엔진은 어떻게 도는가? |
| `TESTING_STRATEGY.md` | MockBukkit/Testcontainers 하네스 및 커밋 규칙 | 에이전트는 어떻게 자율 검증하는가? |
| `MILESTONES.md` | 자율 주행 마일스톤 상세 체크리스트 | 무엇을 어떤 순서로 개발하는가? |
| `developer_guide.md` | 레거시 참고자료 | 과거 구현은 어떻게 동작했는가? |



## 내용 이동표

| 기존 내용 | 출처 | 최종 위치 | 처리 | 교차검증 포인트 |
|---|---|---|---|---|
| Minecraft 운영자의 Discord 구성 불편 | `PROJECT_ANALYSIS_REPORT` §1 | `PROJECT_ANALYSIS_REPORT` | 유지·정제 | Ru-Beacon 포지셔닝과 일치 |
| 기존 프로젝트의 테스트·결합도·동시성 문제 | `PROJECT_ANALYSIS_REPORT` §3 | `PROJECT_ANALYSIS_REPORT` | 과거 분석으로 유지 | 새 아키텍처가 원인을 해결하는지 확인 |
| 라이선스·난독화 제거 배경 | `PROJECT_ANALYSIS_REPORT` §4 | `PROJECT_ANALYSIS_REPORT` | 역사적 배경으로 축약 | 현재 무료·오픈소스 방향과 일치 |
| Core와 Sandbox 구분 | `REENGINEERING_ARCHITECTURE_BLUEPRINT` §6, `DECISION_LOG` DEC-002·006·007 | `PRODUCT_REQUIREMENTS` | 기능 기준으로 재작성 | Core는 필수 보안·운영, Sandbox는 조립 가능 기능 |
| 대표 Sandbox 템플릿 2개 | `DECISION_LOG` DEC-012 | `PRODUCT_REQUIREMENTS` | 요구사항·완료 조건으로 이동 | 양방향 흐름 확인 |
| 계정 pending·active 인증 | `DECISION_LOG` DEC-023·028·030·034·036 | `PRODUCT_REQUIREMENTS` | 사용자 시나리오로 이동 | 정품 조회와 소유권 인증 구분 |
| 관리자 2FA·Monitor·Enforce | `DECISION_LOG` DEC-024·029·031·033 | `PRODUCT_REQUIREMENTS`·`OPERATIONS_RUNBOOK` | 기능과 운영 절차로 분리 | 기본 OFF, 장애 시 동작 일치 |
| RBAC Scope | `REENGINEERING_ARCHITECTURE_BLUEPRINT` §10, `DECISION_LOG` DEC-032·040 | `PRODUCT_REQUIREMENTS`·`REENGINEERING_ARCHITECTURE_BLUEPRINT` | 사용자 권한과 구현 구조로 분리 | Scope 이름·권한 경계 일치 |
| 출석 보상·시간대·수량·복구 | `DECISION_LOG` DEC-014~018 | `PRODUCT_REQUIREMENTS`·`EVENT_CONTRACTS` | 기능 요구사항과 이벤트로 분리 | 선착순·멱등성·실패 복구 일치 |
| Sandbox 노드·변수·조건·병렬 블록 | `DECISION_LOG` DEC-037~044 | `PRODUCT_REQUIREMENTS`·`REENGINEERING_ARCHITECTURE_BLUEPRINT` | UX와 실행 구조로 분리 | Scratch 수준 UX와 엔진 정책 일치 |
| 표준 이벤트 목록·이벤트 봉투 | `DECISION_LOG` DEC-045~047 | `EVENT_CONTRACTS` | 정식 계약으로 이동 | 필드·버전·전달 보장 일치 |
| 서비스 경계·Worker 분리 | `DECISION_LOG` DEC-048~050·061 | `REENGINEERING_ARCHITECTURE_BLUEPRINT` | 최종 구조로 재작성 | API가 Worker를 실행하지 않음 |
| Proxy·Backend·Network 모델 | `DECISION_LOG` DEC-050·051·052·053 | `REENGINEERING_ARCHITECTURE_BLUEPRINT`·`EVENT_CONTRACTS` | 구조·식별자·이벤트로 분리 | tenant/network/instance 구분 |
| 장애 시 기능별 동작 | `DECISION_LOG` DEC-054 | `OPERATIONS_RUNBOOK`·`REENGINEERING_ARCHITECTURE_BLUEPRINT` | 절차와 설계로 분리 | Sandbox 이벤트 유실, 일반 플레이 유지 |
| SLO·심각도·알림·백업 | `DECISION_LOG` DEC-055~060 | `OPERATIONS_RUNBOOK`·`REENGINEERING_ARCHITECTURE_BLUEPRINT` | 운영 기준과 비기능 요구사항으로 분리 | RPO/RTO와 실제 절차 일치 |
| 보안 통신·토큰·IP 처리 | `DECISION_LOG` DEC-057·058 | `OPERATIONS_RUNBOOK`·`REENGINEERING_ARCHITECTURE_BLUEPRINT` | 보안 설계와 운영 절차로 분리 | token·TLS·개인정보 보존 일치 |
| Kotlin·Git·테스트·KDoc 규칙 | `DEVELOPMENT_CONVENTIONS` | `DEVELOPMENT_CONVENTIONS` | Ru-Beacon 명칭으로 갱신 | 모듈 구조·실제 결정과 일치 |
| 포트폴리오·공개 베타·클라우드 증거 | `DECISION_LOG` DEC-064·065 | `PORTFOLIO_EVIDENCE_PLAN` | 증거 매트릭스로 확장 | 보안 캡스톤과 클라우드 역량 분리 |
| 과거 명령어·직접 DB 조회·라이선스 사용법 | `developer_guide.md` | 이동하지 않음 | 레거시 참고자료로 격리 | 현재 설계의 권위 자료로 사용 금지 |

## 충돌·정리 대상

| 항목 | 기존 표현 | 최종 기준 |
|---|---|---|
| 프로젝트명 | CraftAssembler, SyncPy 혼용 | Ru-Beacon |
| 운영 단위 | 서비스 전체 또는 개인 관리자처럼 혼용 | Discord 커뮤니티 1개 + Minecraft Network 1개인 Tenant/Workspace |
| Minecraft 서버 | 서버와 네트워크가 혼용 | Network → Proxy Instance + Backend Instance |
| Sandbox | 고정 룰 엔진·템플릿 중심 | 노드·블록·워크플로우를 조립하는 양방향 확장 시스템 |
| API와 실행 | API가 실행할 수 있는 표현 | API는 관리, Workflow Worker는 실행 |
| DB 접근 | Plugin 직접 JDBC 가능성 | Plugin은 DB 직접 접근 금지, Redis 경유 |
| 이벤트 복구 | 재시도·복구 가능성 혼재 | 자동 재시도 없음, Sandbox 이벤트 장애 시 유실 |
| 2FA 장애 | 무조건 Fail-Closed처럼 표현 | 기본 OFF/Monitor, 운영자가 Enforce를 명시한 경우만 차단 |
| 성능 표현 | 완벽·무오버헤드·검증되지 않은 수치 | SLO와 실험 결과로 표현 |

## 교차검증 게이트

- 모든 문서의 프로젝트명은 `Ru-Beacon`인가?
- Core·Sandbox·템플릿의 정의가 일치하는가?
- MVP와 Phase 1 제외 범위가 일치하는가?
- 이벤트 봉투 필드가 아키텍처·요구사항·운영 절차에서 동일한가?
- Tenant·Network·Proxy·Backend 식별자가 혼동되지 않는가?
- API·Bot·Worker·Plugin의 책임이 겹치지 않는가?
- 장애 시 일반 플레이·관리자 2FA·Sandbox·출석 보상 정책이 일치하는가?
- SLO·RPO·RTO의 목표와 측정 방법이 존재하는가?
- 보안 기능을 과장된 보장으로 표현하지 않았는가?
- 공개 베타 증거가 개인정보와 비밀정보를 노출하지 않는가?
