# Ru-Beacon 테스트 전략 및 에이전트 자율 주행 가이드 (Testing Strategy)

## 문서 역할
코딩 에이전트가 `/goal` 모드로 주행할 때 실제 마인크래프트 서버나 디스코드 접속 없이 **100% 인코드 시뮬레이션으로 자가 치유(Self-healing)할 수 있는 테스트 하네스 구축 규격**과 **Git 커밋/롤백 프로토콜**을 정의한다.

---

## 1. 계층별 테스트 하네스 아키텍처

```text
┌─────────────────────────────────────────────────────────────┐
│ Gradle Test Suite (./gradlew test)                          │
├──────────────────────────────┬──────────────────────────────┤
│ Minecraft Plugin Module      │ Backend Services (API/Worker)│
│  - MockBukkit                │  - Testcontainers            │
│    (In-Memory Server/World)  │    (Real Postgres + Redis)   │
│  - Virtual Player Event Mock │  - Ktor Test Application     │
│  - Command Execution Check   │  - Fake Discord Gateway Mock │
└──────────────────────────────┴──────────────────────────────┘
```

---

## 2. 모듈별 테스트 구현 규격

### 2.1 Minecraft Plugin: MockBukkit 하네스
실제 마인크래프트 프로세스를 띄우지 않고, JUnit 수명주기 내에서 가상 서버 환경을 인메모리로 구동한다.

```kotlin
class RuBeaconPluginTest {
    private lateinit var server: ServerMock
    private lateinit var plugin: RuBeaconPlugin

    @BeforeEach
    fun setUp() {
        server = MockBukkit.mock()
        plugin = MockBukkit.load(RuBeaconPlugin::class.java)
    }

    @AfterEach
    fun tearDown() {
        MockBukkit.unmock()
    }

    @Test
    fun `플레이어 레벨업 이벤트 수신 시 올바른 WSS 이벤트를 전송해야 한다`() {
        val player = server.addPlayerMock("Steve")
        player.level = 30 // LevelUpEvent 발생 시뮬레이션

        // WSS 송신 큐에 이벤트가 적재되었는지 검증
        val outboundQueue = plugin.getOutboundQueue()
        assertEquals(1, outboundQueue.size)
        assertEquals("minecraft.player.level_up", outboundQueue.first().eventType)
    }

    @Test
    fun `서버로부터 수신한 명령어는 Bukkit 메인 스레드에서 실행되어야 한다`() {
        val player = server.addPlayerMock("Steve")
        
        // 가상 COMMAND_REQUEST 수신
        plugin.handleIncomingCommand(CommandRequest(requestId = "req_1", command = "give Steve diamond 1"))
        server.scheduler.performOneTick() // 1틱 진행하여 runTask 실행

        // 플레이어 인벤토리에 다이아몬드가 들어왔는지 검증
        assertTrue(player.inventory.contains(Material.DIAMOND))
    }
}
```

### 2.2 백엔드 통합 테스트: Testcontainers 싱글톤 패턴
실제 프로덕션과 동일한 PostgreSQL 16 및 Redis 7 컨테이너를 도커 데몬을 통해 동적으로 프로비저닝한다.

```kotlin
abstract class BaseIntegrationTest {
    companion object {
        @Container
        val postgres = PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("rubeacon_test")
            .withUsername("test")
            .withPassword("test")

        @Container
        val redis = GenericContainer("redis:7-alpine")
            .withExposedPorts(6379)

        @BeforeAll
        @JvmStatic
        fun startContainers() {
            postgres.start()
            redis.start()
            // Flyway 마이그레이션 자동 실행
            Flyway.configure()
                .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
                .load()
                .migrate()
        }
    }
}
```

### 2.3 Discord Bot: Fake Gateway 모킹
Discord Gateway의 복잡한 웹소켓 핸드셰이크를 배제하고, `discord.button.clicked`, `discord.modal.submitted` 이벤트를 Ktor 테스트 큐에 직접 주입하여 Ephemeral 응답 결과를 검증한다.

---

## 3. 에이전트 자율 주행(/goal) 커밋 및 롤백 프로토콜

에이전트가 `/goal` 모드로 주행할 때 코드를 무한 땜질(Thrashing Loop)하지 않도록 다음 절차를 강제한다.

### 3.1 Step-wise Green-State Commit (성공 시 즉시 커밋)
- 슬라이스 작업 단위:
  1. 도메인 엔티티 및 스키마 작성
  2. MockBukkit / Testcontainers 기반 실패하는 테스트(Red) 작성
  3. 최소 구현체 작성 및 테스트 통과(Green)
- **커밋 강제**:
  테스트가 100% 통과(`BUILD SUCCESSFUL`)하면 에이전트는 즉시 터미널 명령으로 커밋을 실행한다.
  ```powershell
  git add .
  git commit -m "feat(worker): 인메모리 DAG 디스패처 및 병렬 실행 엔진 구현"
  ```

### 3.2 3-Strike Rollback (실패 지속 시 강제 롤백)
- 동일한 컴파일 에러 또는 테스트 실패를 해결하기 위해 2회 수정을 시도했으나 3회째에도 실패할 경우:
  - **절대 기존 코드를 계속 덮어쓰며 누더기로 만들지 않는다.**
  - 직전 정상 상태로 강제 롤백을 실행한다:
    ```powershell
    git reset --hard HEAD
    ```
  - 롤백 후, 실패 원인이 라이브러리 비호환성인지 설계 결함인지 분석하고, 완전히 새로운 구조(또는 더 단순한 대안)로 재시도한다.
