# Ru-Beacon 10대 핵심 결함 종합 리팩터링 마스터 계획 (Master Plan)

## 개요
코드베이스 전수 검증을 통해 확인된 10대 결함(파이프라인 단절, 분산 세션 라우팅 부재, N+1 역직렬화 병목, 메시지 유실, 클래스로더 충돌, 피드백 루프 누락, 멀티테넌시 하드코딩, 하트비트 DB 부하, DAG 다이아몬드 중복 실행, Ingress-내부 메시지 불일치)을 체계적으로 해결하기 위한 종합 엔지니어링 계획입니다.

영향 범위(Blast Radius) 통제 및 Git 롤백/그린 스테이트 원칙을 준수하기 위해 **4개의 독립 배치(Batch)**로 분할 실행합니다.

---

## 핵심 아키텍처 결정 사항

1. **멀티 파드 세션 라우팅 아키텍처**:
   - `workflow-worker`는 기존대로 `stream:commands:request` 스트림에 명령을 발행.
   - `api-service` 인스턴스 중 하나(Consumer Group: `api-ingress-group`)가 스트림에서 명령을 읽음.
   - 해당 Pod가 대상 `instance_id`의 로컬 웹소켓 세션을 보유하지 않은 경우 전역 Redis Pub/Sub 채널(`pubsub:commands:broadcast`)로 브로드캐스트.
   - 각 `api-service` Pod는 Pub/Sub을 구독하고 있다가 자신의 로컬 `SessionRegistry`에 `instance_id`가 존재할 때만 웹소켓으로 `COMMAND_REQ` 전송.
2. **minecraft-plugin 클래스로더 격리**:
   - `com.github.johnrengelman.shadow` 플러그인을 도입하여 `io.ktor`, `kotlinx.coroutines`, `kotlinx.serialization`을 `com.rubeacon.shadow.*`로 패키지 리로케이션.
   - Paper 서버 런타임의 `NoSuchMethodError` 및 클래스로더 충돌 원천 차단.

---

## Batch별 세부 실행 계획

### [Batch 1] 통신 파이프라인 복구 & 분산 세션 라우팅 (결함 1, 2, 6) (완료)
- **NEW**: `api-service/src/main/kotlin/com/rubeacon/api/redis/RedisCommandConsumer.kt`
  - `stream:commands:request` 스트림을 Consumer Group(`api-ingress-group`)으로 폴링.
  - 로컬 `SessionRegistry`에 세션이 있으면 즉시 전송, 없으면 `pubsub:commands:broadcast` 채널로 브로드캐스트.
- **MODIFY**: `api-service/src/main/kotlin/com/rubeacon/api/service/SessionRegistry.kt`
  - Redis Pub/Sub 브로드캐스트 리스너 연동.
- **MODIFY**: `api-service/src/main/kotlin/com/rubeacon/api/routes/MinecraftWebSocketRoute.kt`
  - `Opcode.COMMAND_RES` 프레임 수신 시 `RedisNamespaces.STREAM_COMMANDS_RESULT`(`stream:commands:result`) 스트림으로 발행.
- **MODIFY**: `api-service/src/main/kotlin/com/rubeacon/api/redis/RedisEventPublisher.kt`
  - `publishCommandResult(...)` 메서드 추가.
- **MODIFY**: `api-service/src/main/kotlin/com/rubeacon/api/Application.kt`
  - `RedisCommandConsumer` 라이프사이클 등록 및 코루틴 실행.

### [Batch 2] 워커 안정성 & 병목 해소 (결함 3, 4, 7) (완료)
- **MODIFY**: `workflow-worker/build.gradle.kts`
  - `com.github.ben-manes.caffeine:caffeine:3.1.8` 의존성 추가.
- **MODIFY**: `workflow-worker/src/main/kotlin/com/rubeacon/worker/WorkerMain.kt`
  - `workflowLookup`에 Caffeine 로컬 캐시 적용 (`maximumSize(10_000)`, `expireAfterWrite(5m)`).
  - `stream:events:default` 하드코딩 제거 및 다중 테넌트 지원(통합 인그레스 스트림 또는 멀티 스트림 폴링).
- **MODIFY**: `workflow-worker/src/main/kotlin/com/rubeacon/worker/consumer/RedisStreamsConsumer.kt`
  - `processEntry`의 무조건적 `xack` 제거. 성공 시에만 ACK, 장애 시 Pending 유지, Poison Pill만 DLQ(`stream:events:dlq`) 처리.

### [Batch 3] 플러그인 충돌 방지 & DAG 정합성 (결함 5, 9)
- **MODIFY**: `minecraft-plugin/build.gradle.kts`
  - Shadow Jar 플러그인 적용 및 Ktor, Coroutines 리로케이션.
- **MODIFY**: `workflow-worker/src/main/kotlin/com/rubeacon/worker/engine/DagWorkflowDispatcher.kt`
  - DAG 실행 엔진에 In-Degree Barrier 알고리즘 도입하여 다이아몬드 합류 노드 1회 실행 보장.

### [Batch 4] 인프라 부하 최적화 & K8s 배포 정합성 (결함 8, 10)
- **MODIFY**: `api-service/src/main/kotlin/com/rubeacon/api/routes/MinecraftWebSocketRoute.kt` & `InstanceAuthService.kt`
  - PING 수신 시 PostgreSQL `UPDATE` 제거 -> Redis TTL 갱신(60s)으로 전환.
- **MODIFY**: `deploy/helm/ru-beacon/values.yaml`
  - `apiService.replicaCount: 2` 확대 지원 및 주석 정정.

---

## 검증 계획

- **Batch 1**: `.\gradlew.bat :api-service:test` (E2E 커맨드 전송 및 결과 수신 테스트)
- **Batch 2**: `.\gradlew.bat :workflow-worker:test` (Caffeine 캐시 룩업 및 XACK 실패 복구 테스트)
- **Batch 3**: `.\gradlew.bat :minecraft-plugin:test`, `:minecraft-plugin:shadowJar`, `DagWorkflowDispatcherTest`
- **Batch 4**: `.\gradlew.bat :api-service:test` (PING 부하 분산 및 Redis TTL 확인)
