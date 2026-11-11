# Ru-Beacon 마인크래프트 데모 서버 번들 (deploy/demo-server)

## 1. 개요
본 디렉터리는 Ru-Beacon 백엔드 클러스터와 분리된 독립 호스팅 환경(저가 VPS, Pterodactyl 웹 패널, 로컬 머신 등)에서 **도커 없이 즉시 구동 가능한 순수 Paper 1.20.4 쇼케이스 서버 번들**입니다.

- **서버 엔진**: PaperMC 1.20.4 (Build 499, Java 21)
- **클라이언트 접속 허용**: 1.20.4 - 1.21.x (ViaVersion 탑재로 최신 클라이언트 접속 100% 보장)
- **네트워크 통신**: OCI 클러스터로 단방향 아웃바운드 WSS 연결 (`wss://api.rubeacon.com/ws/minecraft/v1`)
- **인과적 디스코드 렌더러**: 디스코드 실제 전송 완료(ACK) 데이터를 Graphics2D로 인게임 지도 액자에 128x128 픽셀로 실시간 투영

---

## 2. 빠른 시작 (Quick Start)

### 2.1 원클릭 셋업 스크립트 실행
서버 구동에 필요한 Paper 1.20.4 코어 및 ViaVersion 플러그인을 자동으로 다운로드하고 빌드된 `ru-beacon-plugin.jar`를 복사합니다.

- **Windows**:
  ```powershell
  .\setup.bat
  ```
- **Linux / macOS**:
  ```bash
  chmod +x setup.sh run.sh
  ./setup.sh
  ```

### 2.2 플러그인 설정 (`plugins/RuBeaconPlugin/config.yml`)
> **알림**: OCI 운영 백엔드와 연결되는 인증 설정이 이미 주입되어 있습니다. 별도 수정 없이 구동 가능합니다.

```yaml
server-url: "wss://api.rubeacon.com/ws/minecraft/v1"
tenant-id: "tenant_demo"
network-id: "net_demo_01"
instance-id: "paper_demo_01"
instance-token: "demo-secret-token-2026"
ping-interval-ms: 10000
reconnect-initial-delay-ms: 1000
reconnect-max-delay-ms: 10000
enabled: true
```

### 2.3 서버 구동
Aikar의 최적화 JVM 플래그가 적용된 스크립트로 구동합니다:
- **Windows**: `.\run.bat`
- **Linux**: `./run.sh`

---

## 3. 라이브 쇼케이스 인터랙션 시나리오

1. **다중 버전 접속 검증**:
   - 마인크래프트 바닐라 1.20.4 클라이언트 및 최신 1.21.x 클라이언트로 각각 접속하여 접속 성공 확인.
2. **디스코드 라이브 피드 지도 획득**:
   - 관리자 명령 `/rubeacon map` 실행 -> `디스코드 라이브 피드 지도` 아이템 획득.
   - 벽면 아이템 액자(Item Frame)에 지도를 장착 -> "Discord Live Feed" 대기 카드 확인.
3. **자유 탐색형 이벤트 격발**:
   - **광석 채굴**: 다이아몬드/원석 1회 채굴 -> 디스코드 알림 채널에 실시간 메시지 발생 -> 벽면 지도 카드가 실제 디스코드 메시지 ID 및 실측 RTT(ms)로 즉시 갱신.
   - **몬스터 사냥**: 몬스터 1마리 처치 -> 디스코드 알림 발생 -> 지도 카드 갱신.
   - **출석/보상 수령**: `/attend` 명령어 실행 -> 2-Tier Admission Control 통과 및 디스코드 확정 피드백.
4. **인프라 무결성 진단**:
   - `/rubeacon status` 실행 -> 메인 스레드 틱 영향도 0ms 및 WSS 소켓 정상 상태 확인.

---

## 4. 타 머신 실행 인수인계 가이드 (Remote / Secondary Machine Handover)

현재 개발 머신이 아닌 다른 PC 또는 외부 호스팅 머신에서 데모 서버를 단독 구동할 때의 작업 절차입니다.

### 4.1 백엔드 인프라 준비 상태 (현재 완료됨)
* **K3s 클러스터 게이트웨이**: `wss://api.rubeacon.com/ws/minecraft/v1` (인바운드 준비 완료)
* **디스코드 봇**: `RuBea#9564` (정상 가동 중, 슬래시 커맨드 `/verify`, `/attend` 등록 완료)
* **PostgreSQL DB 테넌트/규칙**:
  * `tenants`: `tenant_demo`
  * `minecraft_instances`: `paper_demo_01` (인증 토큰: `demo-secret-token-2026`)
  * `workflows`: `wf_block_break` (블록 파괴 알림), `wf_entity_kill` (몹 처치 알림) 활성화 완료

### 4.2 타 머신 이동 방법 (택 1)

#### 방법 A. 번들 디렉토리 압축 이동 (권장 - Gradle 불필요)
1. 현재 작업 머신의 `deploy/demo-server` 폴더와 `minecraft-plugin/build/libs/ru-beacon-plugin.jar` 파일을 함께 복사합니다.
   * `deploy/demo-server/plugins/ru-beacon-plugin.jar` 경로에 배치합니다.
2. `deploy/demo-server` 폴더 전체를 압축(`zip`)하여 타 머신으로 전송 및 압축 해제합니다.
3. 타 머신에는 **Java 21**만 설치되어 있으면 빌드 도구 없이 즉시 구동 가능합니다.

#### 방법 B. Git Clone 후 원클릭 빌드
1. 타 머신에서 저장소 클론:
   ```bash
   git clone https://github.com/mmmphyun/ru-beacon.git
   cd ru-beacon
   ```
2. 플러그인 빌드:
   ```powershell
   .\gradlew.bat :minecraft-plugin:shadowJar
   # Linux: ./gradlew :minecraft-plugin:shadowJar
   ```
3. 데모 서버 이동 및 셋업:
   ```powershell
   cd deploy/demo-server
   .\setup.bat
   # Linux: ./setup.sh
   ```

### 4.3 타 머신 실행 및 테스트 절차
1. **셋업 스크립트 실행**:
   * Windows: `.\setup.bat`
   * Linux: `./setup.sh`
   * (Paper 1.20.4-499 및 ViaVersion 자동 다운로드, eula 동의 자동 완료)
2. **서버 시작**:
   * Windows: `.\run.bat`
   * Linux: `./run.sh`
3. **정상 연결 확인**:
   * 콘솔 로그에 `[RuBeaconPlugin] WebSocket connection opened to wss://api.rubeacon.com/ws/minecraft/v1` 출력 확인.
4. **인게임 접속 및 검증**:
   * 마인크래프트 클라이언트로 서버 IP(또는 `localhost:25565`) 접속.
   * `/rubeacon map` -> 액자에 걸기 -> 블록 파괴/몹 처치 -> 디스코드 알림 및 지도 화면 실시간 갱신 확인.

