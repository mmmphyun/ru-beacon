import { describe, it, expect, vi, beforeAll } from "vitest";
import React from "react";
import { render, screen } from "@testing-library/react";
import LandingPage from "../app/page";

vi.mock("@/lib/api", () => ({
  fetchUserProfile: vi.fn().mockResolvedValue(null),
}));

beforeAll(() => {
  Object.defineProperty(window, "matchMedia", {
    writable: true,
    value: vi.fn().mockImplementation((query) => ({
      matches: false,
      media: query,
      onchange: null,
      addListener: vi.fn(),
      removeListener: vi.fn(),
      addEventListener: vi.fn(),
      removeEventListener: vi.fn(),
      dispatchEvent: vi.fn(),
    })),
  });
});

describe("LandingPage UI & Copywriting", () => {
  it("SSOT 카피라이팅에 정의된 메인 헤드라인 및 카테고리 뱃지를 렌더링해야 한다", () => {
    render(<LandingPage />);

    expect(screen.getByText("초저지연 마인크래프트-디스코드 브릿지")).toBeDefined();
    expect(
      screen.getByText("서버 TPS 저하 없는 마인크래프트-디스코드 실시간 연동")
    ).toBeDefined();
    expect(
      screen.getByText(
        "서버에 이벤트가 몰려도 렉이 걸리지 않습니다. 게임 속 사건을 디스코드 알림과 역할에 곧바로 반영합니다."
      )
    ).toBeDefined();
  });

  it("핵심 인프라 3대 명세 카드가 정상적으로 노출되어야 한다", () => {
    render(<LandingPage />);

    expect(screen.getByText("WSS 단일 계층 게이트웨이")).toBeDefined();
    expect(screen.getByText("LMAX Disruptor 인메모리 링버퍼")).toBeDefined();
    expect(screen.getByText("디스코드 권한 실시간 동기화")).toBeDefined();
  });

  it("온보딩 3단계 요약 카드가 정상적으로 노출되어야 한다", () => {
    render(<LandingPage />);

    expect(screen.getByText("디스코드 봇 초대")).toBeDefined();
    expect(screen.getByText("인스턴스 토큰 발급")).toBeDefined();
    expect(screen.getByText("실시간 하트비트 확인")).toBeDefined();
  });
});
