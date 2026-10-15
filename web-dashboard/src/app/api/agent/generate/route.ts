import { NextRequest, NextResponse } from "next/server";
import {
  GeminiDecisionAdapter,
  TRIGGER_REGISTRY,
  ACTION_REGISTRY,
} from "@/lib/decision-engine";
import { globalRateLimiter, SubscriptionTier } from "@/lib/rate-limiter";

const decisionAdapter = new GeminiDecisionAdapter();

export async function POST(req: NextRequest) {
  try {
    const body = await req.json();
    const prompt = typeof body.prompt === "string" ? body.prompt.trim() : "";
    const tier: SubscriptionTier = body.tier || "FREE";
    const tenantId = body.tenantId || "anonymous";

    if (!prompt) {
      return NextResponse.json(
        { error: "INVALID_REQUEST", message: "자연어 프롬프트가 누락되었습니다." },
        { status: 400 }
      );
    }

    // IP 또는 테넌트 식별자 기반 키 생성
    const clientIp =
      req.headers.get("x-forwarded-for")?.split(",")[0]?.trim() ||
      req.headers.get("x-real-ip") ||
      "127.0.0.1";
    const rateLimitKey = `${tenantId}:${clientIp}`;

    // 1. 슬라이딩 윈도우 레이트 리미트 검증
    const rateLimitCheck = globalRateLimiter.check(rateLimitKey, tier);
    if (!rateLimitCheck.allowed) {
      return NextResponse.json(
        {
          error: "RATE_LIMIT_EXCEEDED",
          message: rateLimitCheck.errorMessage,
          reason: rateLimitCheck.reason,
        },
        { status: 429 }
      );
    }

    // 2. 결정 엔진 어댑터 추론 수행
    const result = await decisionAdapter.decide(prompt);

    // 3. 환각 방어 및 지원 불가 도메인 요청 차단 (HTTP 400)
    if (result.confidence === 0.0 || !result.supported) {
      return NextResponse.json(
        {
          error: "UNSUPPORTED_REQUIREMENT",
          title: "지원하지 않는 워크플로우 요구사항",
          message:
            "입력하신 내용 중 지원하지 않는 조건 또는 동작이 포함되어 있습니다. Ru-Beacon은 현재 아래 항목만 지원합니다.",
          supportedTriggers: TRIGGER_REGISTRY.map((t) => t.label),
          supportedActions: ACTION_REGISTRY.map((a) => a.label),
          result,
        },
        { status: 400 }
      );
    }

    // 정상 요청인 경우 레이트 리미터에 타임스탬프 기록
    globalRateLimiter.record(rateLimitKey);

    return NextResponse.json({
      success: true,
      result,
    });
  } catch (error: unknown) {
    return NextResponse.json(
      {
        error: "INTERNAL_SERVER_ERROR",
        message: "에이전트 판단 중 예기치 않은 오류가 발생했습니다.",
      },
      { status: 500 }
    );
  }
}
