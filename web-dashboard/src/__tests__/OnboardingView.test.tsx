import { describe, it, expect, vi } from "vitest";
import React from "react";
import { render, screen, fireEvent, waitFor } from "@testing-library/react";
import { OnboardingView } from "../components/onboarding/OnboardingView";

vi.mock("@/lib/api", () => ({
  fetchTenantDetail: vi.fn().mockResolvedValue({
    tenantId: "test_tenant",
    name: "테스트 마인크래프트 서버",
    discordGuildId: "1234567890",
    policyMode: "MONITOR",
    instances: [],
  }),
  saveOnboarding: vi.fn().mockResolvedValue({ success: true }),
  issueInstanceToken: vi.fn().mockResolvedValue({
    instanceId: "inst_test_01",
    token: "mock_jwt_token_12345",
  }),
  fetchUserProfile: vi.fn().mockResolvedValue({
    discordUserId: "user_test",
    username: "TestUser",
    avatar: null,
    isPlatformSuperAdmin: false,
    tenants: [],
    ownedGuilds: [],
  }),
  IS_DEV_MOCK: true,
}));

describe("OnboardingView 3-Step Wizard", () => {
  it("Step 1 디스코드 서버 연결 폼과 봇 초대 링크를 정확히 렌더링해야 한다", async () => {
    render(<OnboardingView tenantId="test_tenant" />);

    expect(screen.getByText("Ru-Beacon 서버 관리 콘솔 온보딩")).toBeDefined();
    expect(screen.getAllByText("Step 1. 디스코드 서버 연결").length).toBeGreaterThan(0);
    expect(screen.getByText("디스코드 봇 내 서버로 초대하기")).toBeDefined();
    expect(screen.getByText("서버 고유 식별자(길드 ID)")).toBeDefined();
  });

  it("Step 1 완료 시 Step 2 인스턴스 토큰 발급 단계로 전환되어야 한다", async () => {
    render(<OnboardingView tenantId="test_tenant" />);

    await waitFor(() => {
      expect(screen.getByDisplayValue("1234567890")).toBeDefined();
    });

    const nextBtn = screen.getByRole("button", { name: /다음: 인스턴스 토큰 발급/ });
    fireEvent.click(nextBtn);

    await waitFor(() => {
      expect(screen.getAllByText("Step 2. 인스턴스 토큰 발급").length).toBeGreaterThan(0);
      expect(screen.getByRole("button", { name: /토큰 발급 및 config.yml 생성/ })).toBeDefined();
    });
  });

  it("Step 3 연결 상태 확인에서 핑 수신 시 완료 상태가 표시되고 콜백이 호출되어야 한다", async () => {
    const onCompleteMock = vi.fn();
    render(<OnboardingView tenantId="test_tenant" onComplete={onCompleteMock} />);

    await waitFor(() => {
      expect(screen.getByDisplayValue("1234567890")).toBeDefined();
    });

    // Step 1 -> Step 2
    fireEvent.click(screen.getByRole("button", { name: /다음: 인스턴스 토큰 발급/ }));

    await waitFor(() => {
      expect(screen.getByRole("button", { name: /다음: 연결 상태 확인/ })).toBeDefined();
    });

    // Step 2 -> Step 3
    fireEvent.click(screen.getByRole("button", { name: /다음: 연결 상태 확인/ }));

    await waitFor(() => {
      expect(screen.getAllByText("Step 3. 연결 상태 실시간 확인").length).toBeGreaterThan(0);
      expect(screen.getByText("하트비트 신호 대기 중")).toBeDefined();
    });

    // 테스트 핑 시뮬레이션 버튼 클릭
    fireEvent.click(screen.getByRole("button", { name: /테스트 핑 수신 시뮬레이션/ }));

    await waitFor(() => {
      expect(screen.getByText("서버 연결 완료")).toBeDefined();
      expect(screen.getByRole("button", { name: /대시보드로 이동하기/ })).toBeDefined();
    });

    fireEvent.click(screen.getByRole("button", { name: /대시보드로 이동하기/ }));
    expect(onCompleteMock).toHaveBeenCalled();
  });

  it("ownedGuilds가 전달되면 길드 선택 드롭다운이 표시되고 선택 시 자동 완성되어야 한다", async () => {
    const ownedGuilds = [
      { id: "guild_auto_111", name: "내 야생 서버" },
      { id: "guild_auto_222", name: "내 미니게임 서버" },
    ];

    render(<OnboardingView tenantId="test_tenant" ownedGuilds={ownedGuilds} />);

    expect(screen.getByText("소유한 Discord 서버 빠른 선택")).toBeDefined();
    const select = screen.getByLabelText("소유한 Discord 서버 선택");
    expect(select).toBeDefined();

    fireEvent.change(select, { target: { value: "guild_auto_111" } });

    await waitFor(() => {
      expect(screen.getByDisplayValue("guild_auto_111")).toBeDefined();
      expect(screen.getByDisplayValue("내 야생 서버")).toBeDefined();
    });

    expect(screen.getByPlaceholderText("Discord 관리자 역할 ID (선택)")).toBeDefined();
    expect(screen.getByLabelText("2FA 보안 정책 모드")).toBeDefined();
  });
});
