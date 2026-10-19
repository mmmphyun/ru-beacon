import { describe, it, expect, vi, afterEach } from "vitest";
import { fetchUserProfile, getDiscordLoginUrl, fetchTenantDetail } from "@/lib/api";

describe("api mock fallbacks and normalization", () => {
  afterEach(() => {
    vi.restoreAllMocks();
  });

  it("기본 Mock 사용자 프로필은 슈퍼 어드민 권한을 갖지 않아야 한다", async () => {
    const profile = await fetchUserProfile();
    expect(profile?.isPlatformSuperAdmin).toBe(false);
  });

  it("getDiscordLoginUrl은 올바른 OAuth2 로그인 엔드포인트를 반환해야 한다", () => {
    const url = getDiscordLoginUrl();
    expect(url).toContain("/api/v1/auth/discord/login");
  });

  it("백엔드 UserProfileDto 응답을 프론트엔드 모델 규격으로 정상 정규화해야 한다", async () => {
    const mockBackendResponse = {
      discordUserId: "user_12345",
      username: "TestPlayer",
      avatar: "avatar_hash",
      isPlatformSuperAdmin: false,
      tenants: [
        {
          id: "tenant_alpha",
          name: "알파 마인크래프트 서버",
          discordGuildId: "guild_999",
          isOwner: true,
          hasAdminRole: false,
        },
        {
          id: "tenant_beta",
          name: "베타 마인크래프트 서버",
          discordGuildId: "guild_888",
          isOwner: false,
          hasAdminRole: true,
        },
      ],
      ownedGuilds: [
        { id: "guild_999", name: "알파 마인크래프트 서버", isOwner: true },
      ],
    };

    vi.spyOn(global, "fetch").mockResolvedValueOnce({
      ok: true,
      status: 200,
      json: async () => mockBackendResponse,
    } as Response);

    const profile = await fetchUserProfile();
    expect(profile).not.toBeNull();
    expect(profile?.discordUserId).toBe("user_12345");
    expect(profile?.tenants).toHaveLength(2);
    expect(profile?.tenants[0]).toEqual({
      tenantId: "tenant_alpha",
      guildName: "알파 마인크래프트 서버",
      role: "OWNER",
    });
    expect(profile?.tenants[1]).toEqual({
      tenantId: "tenant_beta",
      guildName: "베타 마인크래프트 서버",
      role: "ADMIN",
    });
    expect(profile?.ownedGuilds).toHaveLength(1);
    expect(profile?.ownedGuilds?.[0].id).toBe("guild_999");
  });

  it("fetchTenantDetail 호출 시 백엔드의 id를 tenantId로 정규화 매핑해야 한다", async () => {
    vi.spyOn(global, "fetch").mockResolvedValueOnce({
      ok: true,
      status: 200,
      json: async () => ({
        id: "tenant_backend_id",
        name: "서버 이름",
        discordGuildId: "guild_111",
        instances: [],
      }),
    } as Response);

    const detail = await fetchTenantDetail("tenant_backend_id");
    expect(detail).not.toBeNull();
    expect(detail?.tenantId).toBe("tenant_backend_id");
  });
});
