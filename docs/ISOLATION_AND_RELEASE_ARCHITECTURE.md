# Ru-Beacon 격리 메커니즘 및 릴리스 아키텍처 (ISOLATION_AND_RELEASE_ARCHITECTURE.md)

이 문서는 Ru-Beacon 프로젝트의 **컨테이너 런타임 격리**, **CI/CD 파이프라인 화이트리스트 거버넌스**, **시맨틱 버전 태그 기반 릴리스 아키텍처**를 공식 정의한 기술 엔지니어링 명세서입니다.

---

## 1. 컨테이너 런타임과 하네스/문서의 완전 격리 메커니즘

운영 환경(Kubernetes/K3s)에 배포되는 컨테이너 이미지는 보안 공격 표면(Attack Surface)을 최소화하고 이미지 크기를 극단적으로 억제하기 위해 **3중 격리 레이어**를 강제합니다.

### 1.1 Multi-Stage Docker Build를 통한 런타임 격리
- **빌더 스테이지 (Stage 1: Build)**:
  - `eclipse-temurin:21-jdk-jammy` 환경에서 소스 컴파일 및 `./gradlew installDist` 실행.
- **런타임 스테이지 (Stage 2: Runtime)**:
  - `eclipse-temurin:21-jre-jammy` 기반의 경량 런타임.
  - 빌더 스테이지에서 생성된 최종 바이너리(`/build/install/<service>`)만 복사.
  - **격리 결과**: `AGENTS.md`, `.githooks/`, `docs/`, `*.md`, 소스코드 및 빌드 도구는 런타임 이미지 내에 **단 1바이트도 포함되지 않음**.
  - 비루트 계정(`appuser:appgroup`, UID 10001)으로 실행되어 CIS Kubernetes Benchmark 및 최소 권한 원칙 준수.

### 1.2 `.dockerignore`를 통한 빌드 컨텍스트 전송 원천 차단
- Docker CLI가 빌드 데몬에 전송하는 빌드 컨텍스트에서 하네스, 문서, 깃 메타데이터를 원천 배제:
  - `docs/`, `AGENTS.md`, `.githooks/`, `.github/`, `*.md`, `LICENSE`, `.git/`
- **효과**:
  - 도커 데몬으로의 불필요한 I/O 전송 제거로 빌드 속도 향상.
  - 거버넌스 파일이나 마크다운 문서 수정으로 인한 Docker 빌드 캐시(Build Cache Invalidation) 무효화 참사 원천 방지.

---

## 2. CI 파이프라인 실 운영 코드 화이트리스트 (`paths`) 전략

### 2.1 블랙리스트(`paths-ignore`)의 한계와 결함
- `paths-ignore`는 새로운 문서나 하네스 파일이 추가될 때마다 목록을 수동 갱신해야 하는 관리 부채가 발생함.
- 누락 시 사소한 마크다운 수정에도 6분 분량의 무거운 Gradle 통합 테스트 및 Docker 멀티스테이지 빌드가 헛도는 자원 낭비 초래.

### 2.2 화이트리스트(`paths`) 전면 도입
- 실제 런타임 바이너리와 Helm 배포 스펙에 영향을 미치는 디렉토리만 명시:
  - 백엔드 서비스: `api-service/**`, `bot-service/**`, `workflow-worker/**`, `minecraft-plugin/**`, `common/**`
  - 프론트엔드 대시보드: `web-dashboard/**`
  - 배포 및 빌드 설정: `deploy/**`, `build.gradle.kts`, `settings.gradle.kts`, `gradle/**`, `.github/workflows/ci.yml`
- **결과**: `AGENTS.md`, `docs/**`, `.githooks/**` 수정 시 CI가 100% 자동 스킵되어 GitHub Actions 무료 크레딧을 철저히 보호.

---

## 3. Semantic Version Tag 기반 CD 릴리스 아키텍처

### 3.1 1인 개발 트렁크 기반 운용과 프로덕션 배포의 양립
- `main` 브랜치에 직접 커밋을 쌓는 빠른 개발 속도를 유지하되, **실서버 배포(CD)는 `v*.*.*` 시맨틱 버전 태그 푸시로만 격리 격발**.
- 사소한 버그 픽스 커밋마다 서버가 롤링 업데이트되는 **배포 플래핑(Flapping) 위험을 원천 차단**.

### 3.2 OCI 레지스트리: GHCR(GitHub Container Registry) 채택 이유
- **Docker Hub Rate Limit 장애 방어**:
  - Docker Hub 무료 플랜은 IP당 6시간 100회 풀 제한으로 인해, K8s 파드 스케일 아웃 시 `429 Too Many Requests` 및 `ImagePullBackOff` 장애를 유발함.
  - GHCR은 GitHub Actions 내장 토큰(`GITHUB_TOKEN`)으로 무제한에 가까운 푸시/풀을 지원하여 고가용성 보장.
- **저장소-패키지 1:1 결합**:
  - 릴리스 태그(`v1.0.0-beta.1`)와 GHCR 이미지 태그, Helm 차트 릴리스 버전이 완전히 동기화되어 추적성 극대화.

---

## 4. Zero-Context 비동기 관측성 (Discord Webhook vs Terminal Polling)

### 4.1 에이전트 컨텍스트 윈도우 보존
- 에이전트가 `gh run watch`로 수천 줄의 스트리밍 로그를 폴링하면 세션 컨텍스트 토큰이 급속 고갈됨.
- 에이전트는 푸시 후 즉시 턴을 종료하고, **GitHub Actions가 디스코드 웹후크로 성공/실패 카드를 비동기 푸시**하여 인간 엔지니어에게 상태를 보고.
- 실패 발생 시에만 `gh run view <ID> --log-failed` 단일 명령으로 에러 지점만 정밀 발췌(Pinpoint Extraction)하여 컨텍스트 낭비를 차단.
