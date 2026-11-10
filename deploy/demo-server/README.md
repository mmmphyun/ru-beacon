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
```yaml
server-url: "wss://api.rubeacon.com/ws/minecraft/v1"
tenant-id: "tenant_demo"
network-id: "net_demo_01"
instance-id: "paper_demo_01"
instance-token: "YOUR_SHA256_INSTANCE_TOKEN"
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
   - **광석 채굴**: 로비 광산의 다이아몬드/철 원석 1회 채굴 -> 디스코드 `#인게임-실시간-로그` 알림 발생 -> 벽면 지도 카드가 실제 디스코드 메시지 ID 및 실측 RTT(ms)로 즉시 갱신.
   - **몬스터 사냥**: 로비 투기장의 몬스터 1마리 처치 -> 디스코드 축하 메시지 발생 -> 지도 카드 갱신.
   - **출석/보상 수령**: `/attend` 명령어 실행 -> 2-Tier Admission Control 통과 및 디스코드 확정 피드백.
4. **인프라 무결성 진단**:
   - `/rubeacon status` 실행 -> 메인 스레드 틱 영향도 0ms 및 WSS 소켓 정상 상태 확인.
