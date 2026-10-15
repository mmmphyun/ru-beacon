import { describe, it, expect, beforeEach } from "vitest";
import {
  GeminiDecisionAdapter,
  TRIGGER_REGISTRY,
  ACTION_REGISTRY,
} from "../lib/decision-engine";
import { SlidingWindowRateLimiter } from "../lib/rate-limiter";

describe("Decision Engine & Registry", () => {
  const adapter = new GeminiDecisionAdapter();

  it("트리거 및 액션 레지스트리가 필수 항목을 포함해야 한다", () => {
    const triggerTypes = TRIGGER_REGISTRY.map((t) => t.type);
    expect(triggerTypes).toContain("MINECRAFT_LEVEL_UP");
    expect(triggerTypes).toContain("MINECRAFT_BLOCK_BREAK");
    expect(triggerTypes).toContain("MINECRAFT_PLAYER_DEATH");
    expect(triggerTypes).toContain("MINECRAFT_MONSTER_KILL");

    const actionTypes = ACTION_REGISTRY.map((a) => a.type);
    expect(actionTypes).toContain("DISCORD_SEND_MESSAGE");
    expect(actionTypes).toContain("MINECRAFT_DISPATCH_COMMAND");
    expect(actionTypes).toContain("ATTENDANCE_RESERVATION");
  });

  it("명확한 일상어 지시 시 확신도 0.7 이상으로 트리거 및 액션을 매핑해야 한다", async () => {
    const result = await adapter.decide(
      "유저가 레벨 30에 달성하면 디스코드에 메시지를 보내줘. 박스 형태로"
    );

    expect(result.confidence).toBeGreaterThanOrEqual(0.7);
    expect(result.triggerType).toBe("MINECRAFT_LEVEL_UP");
    expect(result.actionType).toBe("DISCORD_SEND_MESSAGE");
    expect(result.supported).toBe(true);
  });

  it("의도가 모호한 요청 시 확신도 0.0 초과 0.7 미만으로 제안을 반환해야 한다", async () => {
    const result = await adapter.decide("레벨 성장 관련해서 뭔가 해줘");

    expect(result.confidence).toBeGreaterThan(0.0);
    expect(result.confidence).toBeLessThan(0.7);
    expect(result.suggestedTrigger).toBe("MINECRAFT_LEVEL_UP");
    expect(result.reasoning).toContain("[레벨 달성] 조건과 유사합니다");
  });

  it("도메인 무관 요청 시 확신도 0.0과 미지원 상태를 반환해야 한다", async () => {
    const result = await adapter.decide("오늘 서울 날씨 알려주고 주식 가격 추천해줘");

    expect(result.confidence).toBe(0.0);
    expect(result.supported).toBe(false);
    expect(result.reasoning).toContain("지원하지 않는 조건 또는 동작이 포함되어 있습니다");
  });
});

describe("SlidingWindowRateLimiter Quotas", () => {
  let limiter: SlidingWindowRateLimiter;

  beforeEach(() => {
    limiter = new SlidingWindowRateLimiter();
  });

  it("무료 티어 분당 2회 상한을 엄격히 집행해야 한다", () => {
    const key = "test_ip_1";
    const baseTime = 1000000;

    // 1회차 허용
    expect(limiter.check(key, "FREE", baseTime).allowed).toBe(true);
    limiter.record(key, baseTime);

    // 2회차 허용
    expect(limiter.check(key, "FREE", baseTime + 10000).allowed).toBe(true);
    limiter.record(key, baseTime + 10000);

    // 3회차 차단 (분당 한도 초과)
    const check3 = limiter.check(key, "FREE", baseTime + 20000);
    expect(check3.allowed).toBe(false);
    expect(check3.reason).toBe("MINUTE_LIMIT");
    expect(check3.errorMessage).toContain("단시간 내 요청 한도(분당 2회)를 초과했습니다");

    // 1분 경과 후 다시 허용
    expect(limiter.check(key, "FREE", baseTime + 65000).allowed).toBe(true);
  });

  it("무료 티어 일일 5회 상한을 엄격히 집행해야 한다", () => {
    const key = "test_ip_2";
    let now = 1000000;

    // 5회 요청 기록 (분당 한도 걸리지 않게 1분 이상 간격)
    for (let i = 0; i < 5; i++) {
      expect(limiter.check(key, "FREE", now).allowed).toBe(true);
      limiter.record(key, now);
      now += 70000;
    }

    // 6회차 차단 (일일 한도 초과)
    const check6 = limiter.check(key, "FREE", now);
    expect(check6.allowed).toBe(false);
    expect(check6.reason).toBe("DAILY_LIMIT");
    expect(check6.errorMessage).toContain("무료 티어 일일 생성 한도(5회)를 모두 사용했습니다");
  });
});
