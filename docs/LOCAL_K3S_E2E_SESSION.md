# 로컬 K3s 클러스터 풀스택 배포 및 실환경 E2E 검증 세션 일지

## 1. 개요 및 목적
- 클라우드(OCI) 환경과 100% 동일한 NGINX Ingress, PostgreSQL, Redis, 백엔드 3종(API, Bot, Worker), 웹 대시보드 구성을 로컬 K3s(k3d)에 구축.
- 빌드된 Paper 1.20.4 마인크래프트 플러그인의 WSS Ingress 연결 및 인게임 이벤트(블록 설치, 사망 등) 수신 E2E 실측.
- k6 1,000 RPS 부하 테스트를 통한 2-Tier Admission Control 및 Prometheus/Grafana 지표 검증.

## 2. 환경 정보
- 호스트 OS: Windows 11 (PowerShell)
- 클러스터 런타임: k3d v5.9.0 (K3s v1.35.5-k3s1)
- 도구 버전: Helm v4.3.0, k6 v2.2.0, Docker Desktop, Java JDK 21

## 3. 진행 단계 (Milestone Checklist)
- [x] 0. 호스트 CLI 환경 및 의존 도구 점검 (k3d, helm, k6 확인 완료)
- [x] 1. k3d 클러스터 (`ru-beacon-local`) 프로비저닝 (Traefik 비활성화, 포트 80/443/8080 노출)
- [x] 2. NGINX Ingress Controller Helm 배포
- [x] 3. 인프라 의존성 배포 (PostgreSQL, Redis in `database` namespace)
- [x] 4. 로컬 도커 이미지 빌드 및 k3d 클러스터 임포트 (`api`, `bot`, `worker`, `web`)
- [x] 5. Ru-Beacon Helm 차트 로컬 배포 (`values-local.yaml`) 및 파드 기동 검증
- [x] 6. 로컬 Paper 1.20.4 서버 기동 및 `ru-beacon-plugin.jar` WSS 연동 E2E 실측
- [x] 7. k6 1,000 RPS 분산 부하 테스트 실행 및 지표 수집
- [x] 8. 웹 대시보드 랜딩 및 플러그인 다운로드 UI 검증/보강

## 4. 실행 기록 및 로그
- 2026-09-11 14:44:34 - 호스트 도구 검증 완료 (k3d 5.9.0, helm 4.3.0, k6 2.2.0)
- 2026-09-11 14:52:15 - k3d 클러스터(ru-beacon-local) 생성 완료 (Traefik 비활성화, 80/443/8080 포트 노출)
- 2026-09-11 14:52:15 - NGINX Ingress Controller Helm 배포 완료 (Ready: 1/1)
- 2026-09-11 14:52:15 - PostgreSQL 16 & Redis 7.2 배포 완료 (database 네임스페이스 Ready)
- 2026-09-11 14:52:15 - 도커 이미지 4종 로컬 빌드 및 k3d 임포트 완료 (api, bot, worker, web)
- 2026-09-11 14:52:15 - Paper 플러그인 Fat JAR 빌드 완료 (ru-beacon-plugin.jar, 7.4MB)
- 2026-09-11 14:52:15 - Ru-Beacon Helm 릴리스 배포 완료 (values-local.yaml, 파드 4종 1/1 Running)
- 2026-09-11 14:52:15 - Ingress 라우팅 검증 완료:
  - http://api.127.0.0.1.nip.io/healthz -> HTTP 200 OK
  - http://web.127.0.0.1.nip.io/ -> HTTP 200 OK (Next.js Dashboard)

## 5. k6 1,000 RPS 분산 동시성 부하 테스트 실측 결과
- 일시: 2026-09-11 14:56:16
- 부하 패턴: ramping-arrival-rate (Warm-up 200 RPS -> Spike 1,000 RPS -> Cooldown, 총 65초)
- 총 요청 수: 39,249건 (평균 603.8 RPS, 피크 1,000 RPS)
- 검증 통계:
  - checks_succeeded: 99.89% (39,207 / 39,249)
  - 1차 인메모리 Fast-Fail 차단 (429/409): 38,999건 (99.36%)
  - 2차 RDBMS 트랜잭션 통과 (200/202): 250건
  - Fast-Fail P95 레이턴시: 2.0ms (평균 1.17ms, 중앙값 1.0ms, P90 2.0ms)
  - HTTP 요청 전체 P95 레이턴시: 2.07ms (P99 3.16ms)
  - 임계치(Thresholds): checks(>95%) [PASS], fast_fail_duration_ms(<15ms) [PASS], http_req_duration(p95<50, p99<100) [PASS]

## 6. 마인크래프트 Paper 1.20.4 플러그인 WSS 인그레스 E2E 실측 결과
- 서버 버전: Paper 1.20.4-499 (Java 21)
- 플러그인: ru-beacon-plugin.jar (Fat JAR 번들링 완료)
- 인그레스 주소: ws://api.127.0.0.1.nip.io/ws/minecraft/v1
- 테넌트: tenant_local_01 / 인스턴스: paper_local_01
- WSS 연결 상태: ONLINE (동기화 완료, 주기적 PING/PONG 하트비트 정상 유지)
