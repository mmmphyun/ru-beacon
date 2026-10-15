import { describe, it, expect, beforeEach } from "vitest";
import { NextRequest } from "next/server";
import { POST } from "../app/api/agent/generate/route";
import { globalRateLimiter } from "../lib/rate-limiter";

function createMockRequest(body: Record<string, unknown>, ip: string = "127.0.0.1"): NextRequest {
  return new NextRequest("http://localhost:3000/api/agent/generate", {
    method: "POST",
    headers: {
      "content-type": "application/json",
      "x-forwarded-for": ip,
    },
    body: JSON.stringify(body),
  });
}

describe("POST /api/agent/generate API Route", () => {
  beforeEach(() => {
    globalRateLimiter.reset();
  });

  it("유효한 자연어 요청에 대해 200과 워크플로우 매핑 결과를 반환해야 한다", async () => {
    const req = createMockRequest({
      prompt: "유저가 레벨 30에 달성하면 디스코드에 메시지를 보내줘. 박스 형태로",
      tenantId: "tenant_api_test",
    });

    const res = await POST(req);
    expect(res.status).toBe(200);

    const data = await res.json();
    expect(data.success).toBe(true);
    expect(data.result.triggerType).toBe("MINECRAFT_LEVEL_UP");
    expect(data.result.actionType).toBe("DISCORD_SEND_MESSAGE");
    expect(data.result.confidence).toBeGreaterThanOrEqual(0.7);
  });

  it("도메인 무관 요청에 대해 400과 지원 가능 목록 가이드를 반환해야 한다", async () => {
    const req = createMockRequest({
      prompt: "오늘 서울 날씨 알려주고 주식 추천해줘",
      tenantId: "tenant_api_test",
    });

    const res = await POST(req);
    expect(res.status).toBe(400);

    const data = await res.json();
    expect(data.error).toBe("UNSUPPORTED_REQUIREMENT");
    expect(data.title).toBe("지원하지 않는 워크플로우 요구사항");
    expect(data.supportedTriggers).toBeDefined();
    expect(data.supportedActions).toBeDefined();
  });

  it("분당 2회 초과 요청 시 429 Too Many Requests를 반환해야 한다", async () => {
    const tenantId = "rate_limit_tenant";
    const ip = "192.168.1.100";

    const req1 = createMockRequest({ prompt: "다이아 채굴 시 디스코드 알림", tenantId }, ip);
    const res1 = await POST(req1);
    expect(res1.status).toBe(200);

    const req2 = createMockRequest({ prompt: "보스 처치 시 디스코드 알림", tenantId }, ip);
    const res2 = await POST(req2);
    expect(res2.status).toBe(200);

    // 3회차: 분당 한도 초과로 차단
    const req3 = createMockRequest({ prompt: "플레이어 사망 시 디스코드 알림", tenantId }, ip);
    const res3 = await POST(req3);
    expect(res3.status).toBe(429);

    const data = await res3.json();
    expect(data.error).toBe("RATE_LIMIT_EXCEEDED");
    expect(data.message).toContain("단시간 내 요청 한도(분당 2회)를 초과했습니다");
  });
});
