import { describe, it, expect } from "vitest";
import { fetchUserProfile } from "@/lib/api";

describe("api mock fallbacks", () => {
  it("기본 Mock 사용자 프로필은 슈퍼 어드민 권한을 갖지 않아야 한다", async () => {
    // 네트워크 실패 시 mockUserProfile로 fallback
    const profile = await fetchUserProfile();
    expect(profile?.isPlatformSuperAdmin).toBe(false);
  });
});
