export type SubscriptionTier = "FREE" | "PRO" | "ENTERPRISE";

export interface TierQuota {
  perMinute: number;
  perDay: number;
}

export const TIER_QUOTAS: Record<SubscriptionTier, TierQuota> = {
  FREE: { perMinute: 2, perDay: 5 },
  PRO: { perMinute: 10, perDay: 100 },
  ENTERPRISE: { perMinute: 60, perDay: 1000 },
};

export interface RateLimitCheckResult {
  allowed: boolean;
  reason?: "MINUTE_LIMIT" | "DAILY_LIMIT";
  errorMessage?: string;
  remainingMinute: number;
  remainingDaily: number;
}

/**
 * 타임스탬프 슬라이딩 윈도우 로그 (Sliding Window Log) 레이트 리미터
 * 분 단위 및 일 단위 경계면 급증(Boundary Burst)을 차단함.
 */
export class SlidingWindowRateLimiter {
  private logMap = new Map<string, number[]>();

  check(
    key: string,
    tier: SubscriptionTier = "FREE",
    now: number = Date.now()
  ): RateLimitCheckResult {
    const quota = TIER_QUOTAS[tier] || TIER_QUOTAS.FREE;
    const timestamps = (this.logMap.get(key) || []).filter(
      (ts) => now - ts < 86400000 // 24시간 이내 로그만 보존
    );

    // 1분 이내 타임스탬프 계산
    const minuteCount = timestamps.filter((ts) => now - ts < 60000).length;
    // 24시간 이내 타임스탬프 계산
    const dailyCount = timestamps.length;

    if (minuteCount >= quota.perMinute) {
      return {
        allowed: false,
        reason: "MINUTE_LIMIT",
        errorMessage: "단시간 내 요청 한도(분당 2회)를 초과했습니다. 잠시 후 다시 시도하십시오.",
        remainingMinute: 0,
        remainingDaily: Math.max(0, quota.perDay - dailyCount),
      };
    }

    if (dailyCount >= quota.perDay) {
      return {
        allowed: false,
        reason: "DAILY_LIMIT",
        errorMessage: "무료 티어 일일 생성 한도(5회)를 모두 사용했습니다. 내일 자정에 초기화됩니다.",
        remainingMinute: Math.max(0, quota.perMinute - minuteCount),
        remainingDaily: 0,
      };
    }

    return {
      allowed: true,
      remainingMinute: quota.perMinute - minuteCount,
      remainingDaily: quota.perDay - dailyCount,
    };
  }

  record(key: string, now: number = Date.now()): void {
    const timestamps = (this.logMap.get(key) || []).filter(
      (ts) => now - ts < 86400000
    );
    timestamps.push(now);
    this.logMap.set(key, timestamps);
  }

  reset(): void {
    this.logMap.clear();
  }
}

export const globalRateLimiter = new SlidingWindowRateLimiter();
