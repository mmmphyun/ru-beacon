# Ru-Beacon 알파 배포 결과 및 베타 전환 로드맵 (ALPHA_DEPLOYMENT_PLAN.md)

## 1. 개요
본 문서는 Ru-Beacon 알파 배포 완료 결과를 정리하고, 실인프라 검증 과정에서 식별된 보안 결함을 보강하여 **안전한 실유저 오픈 베타(v0.2.0-beta)**로 전환하기 위한 공식 로드맵이다.

---

## 2. 배포 인프라 토폴로지 (Topology)

```
[Oracle Cloud Infrastructure (Always Free 2-Node)]
  │
  ├── Node 1 (Master / Ingress / Data Store): 2 OCPU, 12GB RAM, Ubuntu 24.04 ARM64
  │     ├─ K3s Control Plane Server (API Server, SQLite)
  │     ├─ Traefik Ingress Controller (포트 80 수신)
  │     ├─ PostgreSQL 16 (10Gi 로컬 볼륨 고정 핀팅)
  │     ├─ Redis 7 (로컬 볼륨 고정 핀팅, Streams / 2-Tier Admission)
  │     ├─ api-service 파드 1대 (Ktor Ingress, WSS 수신)
  │     └─ web-dashboard 파드 1대 (Next.js 14 대시보드)
  │
  └── Node 2 (Worker / Processing / Bot): 2 OCPU, 12GB RAM, Ubuntu 24.04 ARM64
        ├─ K3s Agent Worker
        ├─ workflow-worker 파드 2대 (KEDA 오토스케일링 적용, 최대 6대)
        └─ bot-service 파드 1대 (Kord Discord Bot)

========================= [ 공용 인터넷 (Public WAN) 경유 ] =========================

[외부 독립 호스팅 (PaperMC 데모 서버)]
  └── PaperMC 1.20.4+ 데모 서버
        ├─ RuBeacon Paper Plugin (Shadow JAR Relocation 적용)
        └─ 통신: ws://api.<NODE_IP>.sslip.io 단방향 아웃바운드 WSS 연결

[공식 디스코드 서버 (관제 채널)]
  ├─ #인게임-실시간-로그 (접속/퇴장, 출석, 이벤트 브로드캐스트)
  ├─ #깃허브-알림 (GitHub Webhook 커밋 및 릴리스 알림)
  └─ #시스템-경보 (STALE 인스턴스 감지, 에러 알림)
```

---

## 3. 알파 배포 실측 검증 완료 내역

1. **2단계 진입 제어 1,000 RPS 극한 부하 테스트 실측 완결**:
   - 총 39,084건 요청 중 패킷 유실 0건(실패율 0.00%).
   - Redis 인메모리 Lua 1차 관문 차단 37,544건(96.05%) 달성.
   - 단일 행 락 경합 및 커넥션 풀 고갈 0건 방어 (HikariCP 최대 10개 유지).
   - 서버 내부 연산 지연(TTFB) P95 3.02ms 유지.
   - 한국-오사카 물리 왕복 지연(약 35ms) 포함 WAN P95 42.88ms 안정 수렴.
2. **FinOps 0원 방어선 구축**:
   - Terraform OCI Quota 정책으로 A1 코어 4개, 메모리 24GB, 스토리지 200GB 상한 하드 차단.
   - $1 초과 시 즉시 경보가 발송되는 예산 감시 룰 적용 완료.
3. **멀티아키텍처 CI/CD 파이프라인 가동**:
   - QEMU 에뮬레이터를 통해 linux/amd64 및 linux/arm64 듀얼 아키텍처 컨테이너 이미지 자동 빌드 및 GHCR 배포 완료.

---

## 4. 단계별 실행 현황 (Action Plan)

- [x] **Step 1: OCI 2-Node 인프라 프로비저닝**
  - OCI Console 및 Terraform으로 Ubuntu 24.04 ARM 인스턴스 2대(각 2 OCPU, 12GB RAM) 생성 완료.
  - VCN 사설망 대역(10.0.0.0/16) 및 Flannel VXLAN(UDP 8472) 사설 통신망 구성 완료.
- [x] **Step 2: K3s 멀티노드 클러스터링 및 Helm 배포**
  - Node 1 Control-Plane 및 Node 2 Agent 조인 완료.
  - PostgreSQL 16, Redis 7, API, Worker, Bot, Web 대시보드 Helm 릴리스 배포 완료.
  - Stateful 볼륨 유실 방지를 위해 데이터베이스 파드를 Node 1에 영구 고정(nodeSelector) 조치 완료.
- [ ] **Step 3: 외부 마인크래프트 데모 서버 연동 라이브 쇼케이스**
  - PaperMC 1.20.4+ 데모 서버 구동 (어드벤처 모드, 로비 맵).
  - 플러그인 WSS 연결 및 디스코드 관제 채널 실시간 이벤트 송수신 스모크 테스트.
- [x] **Step 4: k6 극한 부하 테스트 수행 및 벤치마크 리포트 작성**
  - 공용망 경유 1,000 RPS 피크 버스트 주입 완료.
  - 실측 결과 문서(`deploy/load-test/BENCHMARK_REPORT.md`) 커밋 완료.
- [ ] **Step 5: 베타 전환 4대 보안 및 인프라 과제 수행**

---

## 5. 베타 전환 4대 보안 및 인프라 강화 과제 (Beta Hardening)

알파 실측 과정에서 노출된 취약점을 해결하고 실유저를 안정적으로 수용하기 위해 오픈 베타 이전에 다음 4대 과제를 필수로 완결한다.

### 5.1 Origin IP 은닉 및 Cloudflare 프록시 WAF 연동
- **문제**: sslip.io 기반 임시 도메인 운용 시 노드 원본 공용 IP가 외부에 노출되어 L4/L7 DDoS 표적이 됨.
- **해결 방안**:
  - 도메인(`api.rubeacon.com`, `dashboard.rubeacon.com`)을 Cloudflare 네임서버에 등록하고 프록시(오렌지 구름) 활성화.
  - Cloudflare 에지 서버 뒤로 OCI 원본 IP를 완전 은닉하고 WAF 룰을 통한 악성 봇 트래픽 사전 차단.

### 5.2 종단간(End-to-End) TLS 암호화 체계화
- **문제**: 현재 포트 80(HTTP/WS) 통신으로 운영되어 브라우저 자동 HTTPS 승격 시 접속 오류가 발생하고 패킷 스니핑에 취약함.
- **해결 방안**:
  - K3s Cert-Manager를 연동하고 Let's Encrypt를 통해 공식 와일드카드 인증서 발급 자동화.
  - 외부 통신을 전면 `https://` 및 `wss://`로 강제 전환.

### 5.3 CI/CD 시크릿 및 공용 IP 누출 방지 하드 가드 표준화 [x] (완료)
- **문제**: Gitleaks 기본 룰셋이 엔트로피 기반 Credential만 탐지하여 공용 IPv4 누출을 감지하지 못함.
- **해결 방안 및 조치 결과**:
  - `scripts/check-public-ip.sh`: 사설망(RFC 1918)·루프백(127.x)·메타데이터(169.254.x) 제외 공용 IPv4 감지 스크립트 작성 완료.
  - `.githooks/pre-commit`: 로컬 스테이징 diff 자동 검증을 `scripts/check-public-ip.sh`로 일원화 완료.
  - `.github/workflows/ci.yml`: `security-check` 잡을 추가하여 PR 및 Push 시 공용 IP 검사 및 Gitleaks v2 연동 완료.
  - `.gitleaks.toml`: Gitleaks v8 커스텀 룰셋(`public-ipv4-leak`) 및 사설 대역 allowlist 등록 완료.

### 5.4 실유저 보호를 위한 테넌트별 동적 Rate Limiting [x] (완료)
- **문제**: 다수 게임 서버 및 사용자가 유입될 때 특정 테넌트의 트래픽 폭주가 전체 클러스터 장애로 전이될 수 있음.
- **해결 방안 및 조치 결과**:
  - `TokenBucketRateLimiter`: Redis Lua 스크립트 기반 원자적 토큰 버킷 알고리즘 구현 완료 ($O(1)$ 공간/시간 복잡도, 경계면 2배 버스트 및 저트래픽 위양성 차단).
  - `Application.kt`: Ktor 요청 파이프라인 인터셉터에 테넌트(`X-Tenant-Id`)·유저(`discordUserId`)·비인증 IP(`X-Forwarded-For`/`remoteHost`) 3단계 동적 가드 연동 완료.
  - 한도 초과 시 `HTTP 429 Too Many Requests` 및 표준 헤더(`Retry-After`, `X-RateLimit-Limit`, `X-RateLimit-Remaining`) 반환 및 Fail-Open 고가용성 보장.
  - `RateLimitingIntegrationTest`: Testcontainers Redis 7 환경에서 단위 및 E2E 통합 테스트 100% 통과 확인.
