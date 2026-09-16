# Ru-Beacon 알파 배포 및 실증 로드맵 (ALPHA_DEPLOYMENT_PLAN.md)

## 1. 개요
본 문서는 Ru-Beacon 알파 배포 단계에서 **클라우드 엔지니어링 역량 실증(극한 부하 테스트 데이터 축적)**과 **라이브 쇼케이스(디스코드 관제 + 마인크래프트 데모 서버 연동)**를 성공적으로 완결하기 위한 공식 인프라 및 운영 로드맵입니다.

---

## 2. 확정된 인프라 토폴로지 (Topology)

```
[Oracle Cloud Infrastructure (Always Free)]
  │
  ├── Node 1 (Master / Ingress / API 1): 2 OCPU, 12GB RAM
  │     ├─ K3s Server Control Plane (API Server, SQLite)
  │     ├─ Ingress-Nginx Controller (TLS 암복호화)
  │     ├─ Cert-Manager (Let's Encrypt 자동 갱신)
  │     └─ api-service Pod 1 (Ktor Ingress)
  │
  └── Node 2 (Worker / Heavy Workload / DB / API 2): 2 OCPU, 12GB RAM
        ├─ K3s Agent
        ├─ PostgreSQL 16 StatefulSet (Volume Mount)
        ├─ Redis 7.2 StatefulSet (Streams / PubSub / Presence TTL)
        ├─ workflow-worker Deployment (Replica 2, DAG 실행)
        ├─ bot-service Deployment (Kord Discord Bot)
        └─ api-service Pod 2 (PodAntiAffinity 분산 배치)

========================= [ 공용 인터넷 (Public WAN) 경유 ] =========================

[외부 독립 호스팅 (타사 저가 VPS 또는 독립 머신)]
  └── 마인크래프트 쇼케이스 서버: 2 vCPU, 2~4GB RAM
        ├─ PaperMC 1.20.4+ (모험 모드 쇼케이스 로비 맵 고정)
        ├─ RuBeacon Paper Plugin (Shadow Jar Relocation 적용본)
        └─ 통신: wss://api.rubeacon.com 단방향 아웃바운드 연결 (NAT/VPN 불필요)

[공식 디스코드 서버 (관제 & 쇼케이스 채널)]
  ├─ #인게임-실시간-로그 (접속/퇴장, 출석, 이벤트 브로드캐스트)
  ├─ #깃허브-알림 (GitHub Webhook 커밋 및 릴리스 알림)
  └─ #시스템-경보 (STALE 인스턴스 감지, 에러 알림)
```

---

## 3. 핵심 운영 및 실증 시나리오

### 3.1 인터랙티브 라이브 쇼케이스 (Live Demo)
1. **접속 트리거**:
   - 플레이어가 데모 서버에 접속 $\rightarrow$ WSS EVENT 수신 $\rightarrow$ 워커 DAG $\rightarrow$ 디스코드 채널에 실시간 웰컴 임베드 발송.
2. **출석 및 보상 트리거**:
   - `/출석` 명령어 실행 $\rightarrow$ DB 트랜잭션 기록 $\rightarrow$ 인게임 폭죽 이펙트 및 아이템 지급 + 디스코드 채널에 출석 현황 출력.
3. **인게임 발판 상호작용**:
   - 로비 발판을 밟으면 디스코드 특정 채널로 실시간 알림 전송.

### 3.2 k6 극한 부하 테스트 (포트폴리오 핵심 자산)
- **부하 생성기**: `deploy/load-test/k6-concurrency-benchmark.js` 구동.
- **격리 원칙**: 데모 서버(`tenant_showcase`)와 완전 분리된 `tenant_loadtest` 가상 테넌트로 부하 집중 인가.
- **수집 대상 메트릭 (Grafana Dashboard)**:
  - **RPS & Throughput**: 초당 3,000 ~ 5,000 이벤트 인입 시 시스템 처리량.
  - **Latency Percentiles**: p50, p95, p99 지연시간 (WSS 인그레스부터 Redis Streams 적재까지).
  - **Worker Backlog & Recovery**: 이벤트 스파이크 시 Redis Streams Pending 대기열 소진 속도(Drain Rate).
  - **Caffeine Cache Hit Ratio**: 99% 이상 유지 여부 (PostgreSQL 커넥션 보호 실증).
  - **Pod Auto-scaling & Resource**: HPA 반응 시간, CPU/Memory 사용률.

---

## 4. 단계별 실행 체크리스트 (Action Plan)

- [ ] **Step 1: OCI 2-Node 인프라 프로비저닝**
  - OCI Console 또는 Terraform(`deploy/oci/terraform`)으로 Ubuntu 22.04 ARM 인스턴스 2대(각 2 OCPU, 12GB) 생성.
  - VCN 보안 목록(Security List)에 80(HTTP), 443(HTTPS), 6443(K3s API) 포트 개방.
- [ ] **Step 2: K3s 멀티노드 클러스터링 및 Helm 배포**
  - Node 1에서 K3s Server 설치 및 Token 확인.
  - Node 2에서 K3s Agent로 Node 1에 조인 (`K3S_URL`, `K3S_TOKEN`).
  - Helm 차트(`deploy/helm/ru-beacon`) 배포 (PostgreSQL, Redis, API 2대, Worker 2대, Bot).
  - Cert-Manager를 통해 `api.rubeacon.com` Let's Encrypt SSL/TLS 인증서 발급.
- [ ] **Step 3: 외부 마인크래프트 데모 서버 준비 및 연동**
  - PaperMC 서버 구동 (게임모드 Adventure, 몹/PvP 비활성화).
  - `ru-beacon-plugin.jar` 탑재 및 `config.yml` 설정.
  - WSS 연결 수립 및 디스코드 채널로의 실시간 이벤트 송수신 스모크 테스트.
- [ ] **Step 4: k6 극한 부하 테스트 수행 및 리포트 작성**
  - 가상 부하 인가 (1,000 $\rightarrow$ 3,000 $\rightarrow$ 5,000 RPS).
  - Grafana 대시보드 캡처 및 `deploy/load-test/BENCHMARK_REPORT.md` 갱신.
- [ ] **Step 5: 베타 마일스톤 피처 개발 착수**
  - 실측된 병목 지표 기반 Rate Limiting 및 계획했던 추가 노드/기능 개발 시작.
