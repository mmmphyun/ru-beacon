# Ru-Beacon

> **마인크래프트 서버와 디스코드 커뮤니티를 실시간 연동하는 이벤트 구동 워크플로우 엔진 (로컬 k3d 프로토타입)**

[![CI Pipeline](https://github.com/mmmphyun/ru-beacon/actions/workflows/ci.yml/badge.svg)](https://github.com/mmmphyun/ru-beacon/actions/workflows/ci.yml)
[![Kotlin](https://img.shields.io/badge/Kotlin-1.9.24-7F52FF.svg?logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![Java](https://img.shields.io/badge/Java-21-ED8B00.svg?logo=openjdk&logoColor=white)](https://openjdk.org)
[![Next.js](https://img.shields.io/badge/Next.js-14.2-black.svg?logo=next.js&logoColor=white)](https://nextjs.org)
[![K3s](https://img.shields.io/badge/K3s-Kubernetes-FFC61C.svg?logo=kubernetes&logoColor=white)](https://k3s.io)
[![License: AGPL-3.0](https://img.shields.io/badge/License-AGPL_3.0-blue.svg)](LICENSE)

---

## 0. 프로젝트 성격 및 아키텍처 포지셔닝

본 프로젝트는 **향후 월 0-20달러 수준의 저비용 또는 무료 클라우드(Oracle Cloud Always Free) 배포를 목표로 설계한 1인 개발 프로토타입**입니다.
현재 상용 클라우드 인프라에는 배포하지 않은 상태이며, **로컬 개발 머신의 가상 쿠버네티스(k3d) 환경**에서 컴포넌트 간 통신과 1,000 RPS 동시성 제어 로직을 실측·검증했습니다.

* **목표 인프라 및 설계 제약**:
  * **게스트 게임 서버 틱 보호**: 마인크래프트 서버의 20 TPS(50ms 틱 루프)에 I/O 블로킹 간섭 0ms 보장.
  * **RDBMS 커넥션 통제**: 단일 노드 소형 컨테이너 환경을 전제로 HikariCP 커넥션 풀을 최대 10개로 묶고 풀 고갈 방어.
  * **인프라 비용 목표**: Kafka/RabbitMQ 같은 고비용 브로커(수 GB 메모리, 월 70-150달러 이상 소요)를 배제하고, 단일 Redis 7.2 인스턴스로 메시지 버스와 캐시를 통합해 최소 사양 인프라에 맞춤.
* **현재 검증 범위**:
  * 로컬 개발 머신(Windows 11, Docker Desktop, k3d)에서 NGINX Ingress, PostgreSQL 16, Redis 7.2, 백엔드 서비스 3종, Next.js 대시보드를 프로비저닝.
  * 로컬에 구동한 Paper 1.20.4 서버와의 WSS 연동 및 k6 1,000 RPS 동시성 부하 테스트 통과.
  * 실제 퍼블릭 클라우드(OCI) 배포와 외부 라이브 데모 서버 연동은 인프라 비용 및 리소스 문제로 보류 중인 상태.

---

## 1. 아키텍처 개요

Ru-Beacon은 **이벤트 구동 헥사고날 구조와 2단계 진입 제어 아키텍처**를 채택하여, 게스트 마인크래프트 서버의 틱 지연을 배제하고 인메모리 관문에서 대규모 트래픽 스파이크를 1차 차단합니다.

<picture>
  <source media="(prefers-color-scheme: dark)" srcset="docs/rubeacon-architecture-dark.svg">
  <img alt="Ru-Beacon Architecture Blueprint" src="docs/rubeacon-architecture-light.svg" width="100%">
</picture>

* **데이터 흐름 및 인터페이스 규약**:
  * **① WSS 틱 분리**: 게스트 Paper 서버가 양방향 웹소켓으로 이벤트를 전달하여 50ms 메인 틱 루프 간섭 0ms 보장.
  * **② 게이트웨이**: Discord 봇 클라이언트가 커뮤니티 상호작용 이벤트를 수신하여 `bot-service`로 인계.
  * **③ XADD 스트림 인제스트**: 인그레스 서비스(`api-service`, `bot-service`)가 이벤트를 Redis Streams(`stream:events`)에 인메모리 버퍼링.
  * **④ XREAD 비동기 소비**: KEDA 오토스케일링이 적용된 `workflow-worker`가 컨슈머 그룹을 통해 스트림 이벤트를 인출.
  * **⑤ 1차 Lua 검증**: 트래픽 쇄도 시 워커가 Redis 원자적 Lua 스크립트로 중복 및 초과 요청을 DB 질의 없이 선제 차단.
  * **⑥ 2차 원자적 쓰기**: 1차 관문을 통과한 유효 트랜잭션만 PostgreSQL 16에 조건부 UPDATE 및 INSERT 실행.
  * **⑦ 인게임 명령 디스패치**: 워크플로우 완료 후 인게임 반영 명령을 `api-service`를 경유해 Minecraft 서버로 역방향 디스패치.

---

## 2. 핵심 엔지니어링 의사결정 및 기술적 득실

### 1) 2단계 Redis Lua 진입 제어로 대규모 트래픽 쇄도 방어
* **문제 상황**: 일일 출석과 보상 개시 시점에 수천 명의 동시 클릭 스파이크가 인입될 때, 모든 요청이 RDBMS로 직격하면 단일 행 락 경합과 HikariCP 풀(최대 10개) 고갈로 전체 API 서버가 다운됨.
* **대안 검토 및 기각 사유**:
  * *단순 Redis INCR 단독 카운터*: Redis에서 수량을 차감한 후 후속 DB INSERT 시점에 타임아웃이나 풀 고갈이 발생하면, Redis 수량만 깎이고 DB에는 저장되지 않는 이중 쓰기 정합성 불일치가 발생하여 기각.
  * *단순 PostgreSQL 조건부 UPDATE*: HikariCP 커넥션 풀 10개 한계로 인해 1,000 RPS 경합 시 대기 큐 타임아웃 및 500 내부 서버 오류가 연쇄 발생하여 기각.
* **채택 아키텍처 ([`EventSimulationRoute.kt`](file:///c:/work/ru-beacon/api-service/src/main/kotlin/com/rubeacon/api/routes/EventSimulationRoute.kt#L26-L40), [`NodeExecutors.kt`](file:///c:/work/ru-beacon/workflow-worker/src/main/kotlin/com/rubeacon/worker/engine/NodeExecutors.kt#L187-L201))**:
  * **1차 관문 (Redis Lua Script)**: 단일 Set 키(`quota:{tenantId}:{date}:players`)를 대상으로 `SISMEMBER`와 `SCARD`를 $O(1)$ 원자적 연산으로 수행. 초과 및 중복 요청은 DB 쿼리 호출 0회로 HTTP 429 Too Many Requests / Conflict 조기 차단 (평균 1.17ms, P95 2.00ms).
  * **2차 관문 (PostgreSQL 트랜잭션)**: 관문을 통과한 요청만 `AttendanceQuotas` 조건부 UPDATE 및 `RewardReservations` INSERT 실행.
  * **보상 롤백 및 Fallback**: 2차 DB 영속화 실패 시 `rollbackRedis`(`srem`)로 Redis 선점 수량을 원복. Redis 다운 시 Fail-Open(DB 조건부 UPDATE)으로 자동 전환하여 비즈니스 연속성 보장.

### 2) KEDA 기반 Redis Streams 오토스케일링 & 코루틴 메트릭 맹점 극복
* **문제 상황**: Kotlin 코루틴 기반 워커는 논블로킹 비동기 I/O로 동작하여, 이벤트 백로그 큐에 수만 건이 적체되어도 워커 프로세스의 CPU 사용률이 30%를 넘지 않음.
* **대안 검토 및 기각 사유**:
  * *Kubernetes 표준 CPU HPA (CPU 70%)*: 큐가 지연되어도 CPU 부하가 낮아 스케일아웃이 격발되지 않는 메트릭 맹점이 발생하여 실시간 처리 SLA(p95 10초 이내)를 위반하므로 기각.
* **채택 아키텍처 ([`scaledobject-worker.yaml`](file:///c:/work/ru-beacon/deploy/helm/ru-beacon/templates/scaledobject-worker.yaml))**:
  * KEDA `ScaledObject`를 도입하여 Redis Streams 컨슈머 랙(`lagThreshold: 100`, `pollingInterval: 15s`)을 직접 감지, 큐 적체 발생 즉시 파드를 기본 2대에서 최대 10대까지 동적 증설.
  * 파드 축소 시 진행 중인 작업을 마칠 수 있도록 `terminationGracePeriodSeconds: 30` 및 Graceful Drain 코루틴 셧다운 훅 적용.

### 3) 게스트 마인크래프트 서버 무영향 원칙
* **문제 상황**: 외부 마인크래프트 서버에 설치되는 플러그인은 고객 게임 환경에 부하를 주어서는 안 되며, TPS 20(50ms 틱)을 엄격히 보존해야 함.
* **채택 아키텍처 ([`RuBeaconPlugin.kt`](file:///c:/work/ru-beacon/minecraft-plugin/src/main/kotlin/com/rubeacon/plugin/RuBeaconPlugin.kt), [`RuBeaconWebSocketClient.kt`](file:///c:/work/ru-beacon/minecraft-plugin/src/main/kotlin/com/rubeacon/plugin/client/RuBeaconWebSocketClient.kt))**:
  * **네트워크 I/O 완전 격리**: WSS 소켓 통신 및 PING/PONG 하트비트는 `SupervisorJob() + Dispatchers.IO` 전용 코루틴 스코프에서 구동하여 서버 틱 간섭 0ms 보장.
  * **메모리 바운더리 통제**: 로컬 디스크 WAL 도입을 배제하고 최대 50개의 슬라이딩 링 버퍼(`MAX_OUTBOUND_HISTORY`, Drop-oldest)를 적용하여 장기 단절 시에도 힙 점유율을 100KB 미만으로 고정.
  * **메인 틱 동기화 브릿징**: 인바운드 명령(`COMMAND_REQ`)은 `suspendCancellableCoroutine` + `BukkitScheduler.runTask`로 메인 틱 루프에 동기화 디스패치하여 `IllegalStateException: Asynchronous entity track` 크래시 방어.

### 4) 비용 효율 중심의 메시지 버스 및 백엔드 프레임워크 선정
* **Spring Boot 3 + JPA 기각**: 3개 서비스(API, Bot, Worker)에 각각 Spring을 띄울 경우 컨테이너당 최소 1-2GB의 힙 메모리를 소비하여 소형 단일 VM에서 OOM 발생.
* **100% Kotlin Coroutine 스택 (Ktor + Exposed + Kord)**: 순수 비동기 경량 프레임워크를 적용하여 컨테이너당 메모리 점유를 100-200MB 미만으로 통제.
* **Redis Streams 선정 ([`DECISION_LOG.md` DEC-068](file:///c:/work/ru-beacon/docs/DECISION_LOG.md#L896-L904))**:
  * Kafka 브로커 클러스터(최소 수 GB 메모리, 월 70-150달러 이상)를 배제하고, 이미 캐시 및 세션 라우팅 용도로 구동 중인 Redis 7.2 Streams(`XREADGROUP`, `XAUTOCLAIM`, `XPENDING`)를 메시지 버스로 통합 활용하여 메시지 인프라 추가 비용 0원 유지.
  * 모든 스트림 `XADD`에 `MAXLEN \~ 10000` 근사 트리밍을 강제하여 느린 컨슈머 상황에서도 인메모리 OOM을 원천 차단.

---

## 3. 모듈별 책임 및 구조

| 모듈명 | 기술 스택 | 책임 및 핵심 역할 |
|---|---|---|
| [`common`](common/) | Kotlin, `kotlinx.serialization` | 공통 이벤트 봉투([`EventEnvelope`](common/src/main/kotlin/com/rubeacon/common/event/EventEnvelope.kt)), WSS 프레임 계약. 타 모듈 의존성 0. |
| [`minecraft-plugin`](minecraft-plugin/) | Paper API 1.20.4, Java 21 | 가상 틱 하네스(MockBukkit), 지수 백오프 WSS 클라이언트, 메인 틱 동기 명령어 디스패처. |
| [`api-service`](api-service/) | Ktor, Exposed, Netty, Flyway | 외부 WSS 수용 Ingress, 일회성 토큰 인증, 워크플로우 CRUD/배포/롤백, 2단계 진입 제어 조기 차단. |
| [`bot-service`](bot-service/) | Kord, Coroutines, Jedis | Discord Gateway 인터랙션 수신, 공통 이벤트 정규화 및 Ephemeral 피드백 렌더러. |
| [`workflow-worker`](workflow-worker/) | Ktor, Coroutines, Exposed | Redis Streams 컨슈머, Tarjan 사이클 검증기, 인메모리 DAG 워크플로우 실행 및 감사 로그 영속화. |
| [`web-dashboard`](web-dashboard/) | Next.js 14, Tailwind CSS, Shadcn UI | 커뮤니티 관리자 온보딩, 카드형 위저드 워크플로우 폼 빌더, 버전 배포/롤백 관리 UI. |
| [`deploy`](deploy/) | Helm 3, K3s, KEDA, k6, Prometheus | K3s 배포 매니페스트, 비루트 컨테이너 하드닝, k6 1,000 RPS 분산 부하 테스트 스크립트. |

---

## 4. 로컬 k3d 환경 E2E 부하 테스트 실측 지표 (k6)

클라우드 배포 규격과 동일한 NGINX Ingress, PostgreSQL 16, Redis 7.2, 백엔드 3종, Next.js 대시보드를 로컬 K3s(k3d)에 프로비저닝하고, 실제 Paper 1.20.4 서버 WSS 연동 및 k6 1,000 RPS 분산 동시성 부하 테스트를 수행한 계측 결과입니다.

### 4.1 1,000 RPS 동시성 부하 테스트 실측 요약 ([`BENCHMARK_REPORT.md`](file:///c:/work/ru-beacon/deploy/load-test/BENCHMARK_REPORT.md))
* **테스트 환경**: 로컬 개발 머신 (Windows 11, Docker Desktop, k3d v5.9.0 루프백 네트워크)
* **테스트 시나리오**: `ramping-arrival-rate` (200 RPS Warm-up $\rightarrow$ 1,000 RPS 버스트 주입, 총 65초)
* **총 요청 수**: 39,249건 (평균 603.8 RPS, 피크 1,000 RPS)
* **검증 결과 지표**:
  | 평가 항목 | 실측 지표 | 비고 및 판정 |
  |---|---|---|
  | **Checks 통과율** | **99.89%** (39,207 / 39,249) | 지정 임계치 `checks>95%` 충족 |
  | **1차 인메모리 조기 차단** | **38,999건 (99.36%)** | Redis Lua Script로 RDBMS 유입 원천 차단 |
  | **2차 RDBMS 트랜잭션 확정** | **250건** | 정원 초과 지급 Zero (Overselling 0건) |
  | **조기 차단 응답 속도** | **평균 1.17ms / 중앙값 1.00ms / P95 2.00ms** | 목표치 `<15ms` 대비 초고속 거절 |
  | **전체 HTTP 응답 속도** | **평균 1.13ms / P95 2.07ms / P99 3.16ms** | NGINX Ingress 프록시 레이턴시 포함 (루프백) |
  | **DB 커넥션 풀 상태** | **최대 10개 유지 (고갈 0건)** | 1차 관문 차단으로 HikariCP 풀 고갈 방어 |

> **클라우드 WAN 환경 분석**: 상기 1.17ms 수치는 로컬 루프백(`k3d`) 네트워크 환경의 계측치입니다. 향후 OCI 퍼블릭 클라우드 분산 배포 시 공용 인터넷 물리 RTT(20-40ms)와 NGINX Ingress TLS 암복호화 오버헤드가 더해져 클라이언트 체감 P95 레이턴시는 25-45ms 수준으로 수렴할 것으로 분석됩니다.

### 4.2 Paper 1.20.4 플러그인 실서버 WSS 연동 실측 ([`LOCAL_K3S_E2E_SESSION.md`](file:///c:/work/ru-beacon/docs/LOCAL_K3S_E2E_SESSION.md))
* **서버 런타임**: Paper 1.20.4-499 (Java 21)
* **연동 엔드포인트**: `ws://api.127.0.0.1.nip.io/ws/minecraft/v1` (NGINX Ingress 경유)
* **검증 완료**: SHA-256 토큰 인증 통과, 인스턴스 `ONLINE` 상태 전이 및 30초 주기 PING/PONG 하트비트 세션 정상 유지.

---

## 5. 알파 배포 계획 (ALPHA_DEPLOYMENT_PLAN.md 연계)

현재는 로컬 k3d 환경에서 기능과 동시성 검증을 마친 프로토타입 단계이며, 실제 퍼블릭 클라우드 이전과 라이브 쇼케이스 운영은 다음 단계로 계획되어 있습니다([`ALPHA_DEPLOYMENT_PLAN.md`](file:///c:/work/ru-beacon/docs/ALPHA_DEPLOYMENT_PLAN.md)).
클라우드 인프라가 확보되면 **Oracle Cloud Always Free 환경(월 인프라 비용 0원)**의 무료 리소스(총 4 OCPU, 24GB RAM)를 활용하여 다음 5단계로 배포를 추진할 예정입니다.

### 5.1 계획된 인프라 토폴로지
```
[Oracle Cloud Infrastructure (Always Free)] - 월 인프라 비용 0원 목표
  │
  ├── Node 1 (Master / Ingress / API 1): 2 OCPU, 12GB RAM
  │     ├─ K3s Server Control Plane
  │     ├─ Ingress-Nginx Controller (TLS 암복호화)
  │     ├─ Cert-Manager (Let's Encrypt 자동 갱신: api.rubeacon.com)
  │     └─ api-service Pod 1 (Ktor Ingress)
  │
  └── Node 2 (Worker / Heavy Workload / DB / API 2): 2 OCPU, 12GB RAM
        ├─ K3s Agent
        ├─ PostgreSQL 16 StatefulSet (Volume Mount)
        ├─ Redis 7.2 StatefulSet (Streams / PubSub / Presence TTL)
        ├─ workflow-worker Deployment (Replica 2, DAG 실행)
        ├─ bot-service Deployment (Kord Discord Bot)
        └─ api-service Pod 2 (PodAntiAffinity 분산 배치)

========================= [ 공용 인터넷 (Public WAN) ] =========================

[외부 독립 호스팅 (PaperMC 데모 서버)]        [공식 디스코드 서버 (관제 & 쇼케이스)]
  └── PaperMC 1.20.4+ 데모 서버                  ├─ #인게임-실시간-로그 (접속/출석/이벤트)
        ├─ RuBeacon Paper Plugin 탑재            ├─ #깃허브-알림 (커밋/릴리스 웹훅)
        └─ wss://api.rubeacon.com 아웃바운드      └─ #시스템-경보 (STALE 인스턴스, 에러)
```

### 5.2 5단계 실행 계획
* **Step 1: OCI Always Free 2-Node 프로비저닝**: ARM 인스턴스 2대(각 2 OCPU, 12GB RAM) 생성 및 VCN 보안 목록 포트 개방(80, 443, 6443).
* **Step 2: K3s 멀티노드 클러스터링 및 Helm 배포**: Node 1 마스터 및 Node 2 에이전트 조인, Cert-Manager 기반 `api.rubeacon.com` Let's Encrypt SSL/TLS 인증서 자동 발급.
* **Step 3: 외부 마인크래프트 데모 서버 및 디스코드 라이브 쇼케이스**: 외부 Paper 서버 접속 시 실시간 디스코드 환영 임베드 발송, `/출석` 시 인게임 폭죽 이펙트와 아이템 지급, 로비 발판 상호작용의 0ms 반응성 실시간 검증.
* **Step 4: WAN 환경 k6 극한 부하 테스트 및 관측성 축적**: 공용 인터넷 경유 1,000 $\rightarrow$ 3,000 $\rightarrow$ 5,000 RPS 주입, Grafana 대시보드로 처리량, p95/p99 지연시간, Streams 랙 소진율 실측 리포트 도출.
* **Step 5: 실측 병목 지표 기반 Rate Limiting 및 베타 피처 개발**: WAN 실환경 병목 데이터를 기반으로 테넌트별 토큰 버킷 레이트 리미트 고도화 및 후속 비즈니스 기능 개발 착수.

---

## 6. 공식 기술 문서 목록

프로젝트 아키텍처와 엔지니어링 의사결정 내역은 [`docs/`](docs/) 디렉토리에 전수 기록되어 있습니다.

* **아키텍처 스펙 및 의사결정 기록**:
  * [ALPHA_DEPLOYMENT_PLAN.md](docs/ALPHA_DEPLOYMENT_PLAN.md): OCI Always Free 2-Node 배포 및 라이브 쇼케이스 공식 로드맵
  * [DECISION_LOG.md](docs/DECISION_LOG.md): 10대 아키텍처 의사결정(ADR DEC-066-078) 원천 기록
  * [ENGINEERING_LOG.md](docs/ENGINEERING_LOG.md): 마일스톤 0-7 아키텍처 트레이드오프 및 10대 결함 리팩터링 기록
  * [LOCAL_K3S_E2E_SESSION.md](docs/LOCAL_K3S_E2E_SESSION.md): 로컬 K3s 풀스택 배포 및 실환경 E2E 부하 테스트 실측 일지
  * [BENCHMARK_REPORT.md](deploy/load-test/BENCHMARK_REPORT.md): k6 1,000 RPS 분산 동시성 벤치마크 상세 리포트
* **저수준 프로토콜 및 DDL 명세**:
  * [DATABASE_SCHEMA.sql](docs/DATABASE_SCHEMA.sql): PostgreSQL 16 Flyway V1 공식 DDL 명세
  * [TRANSPORT_PROTOCOL_SPEC.md](docs/TRANSPORT_PROTOCOL_SPEC.md): WSS 인바운드/아웃바운드 프레임 규격 및 Redis Streams 네임스페이스
  * [WORKFLOW_ENGINE_SPEC.md](docs/WORKFLOW_ENGINE_SPEC.md): 워크플로우 DAG AST 파서, Tarjan 사이클 감지 엔진 명세

---

## 7. 로컬 빌드 및 빠른 시작

### 요구사항
* **JDK 21** (Eclipse Temurin 권장)
* **Node.js 20+** 및 **pnpm 12+**
* **Docker Desktop** (통합 테스트 시 Testcontainers 실행용)

### 백엔드 전체 멀티모듈 빌드 및 테스트
```powershell
# 백엔드 멀티모듈 전수 테스트 실행 (Testcontainers 자동 기동)
.\gradlew.bat test

# 특정 모듈 빌드 산출물 생성 (installDist)
.\gradlew.bat :api-service:installDist
.\gradlew.bat :workflow-worker:installDist
```

### 프론트엔드 대시보드 빌드 및 테스트
```powershell
cd web-dashboard
pnpm test
pnpm lint
pnpm build
```

### 로컬 K3s 클러스터 프로비저닝 및 Helm 배포
```powershell
# k3d 클러스터 생성 (Traefik 비활성화, 80/443/8080 노출)
k3d cluster create ru-beacon-local --port "80:80@loadbalancer" --port "443:443@loadbalancer" --port "8080:8080@loadbalancer" --k3s-arg "--disable=traefik@server:0"

# Ingress-NGINX 및 로컬 DB 배포
helm repo add ingress-nginx https://kubernetes.github.io/ingress-nginx; helm repo update
helm install ingress-nginx ingress-nginx/ingress-nginx -n ingress-nginx --create-namespace
kubectl apply -f deploy/k8s-local/database.yaml

# Ru-Beacon Helm 릴리스 배포
helm install ru-beacon deploy/helm/ru-beacon -f deploy/helm/ru-beacon/values-local.yaml -n ru-beacon --create-namespace
```

---

## 8. 라이선스

이 프로젝트는 **GNU Affero General Public License v3.0 ([AGPL-3.0](LICENSE))** 하에 배포됩니다.
SaaS 형태로 서비스를 운영·배포할 경우에도 수정된 전체 소스코드를 공개해야 하며, 무단 상업적 재판매 및 폐쇄 소스화가 엄격히 제한됩니다.
