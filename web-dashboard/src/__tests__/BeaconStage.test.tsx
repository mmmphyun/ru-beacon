import { describe, it, expect, vi, beforeEach } from "vitest";
import React from "react";
import { render, screen, waitFor } from "@testing-library/react";
import { BeaconStage, useLatestRelease } from "../components/landing/BeaconStage";

function ReleaseTestView() {
  const release = useLatestRelease();
  return (
    <div>
      <span data-testid="tag">{release.tagName}</span>
      <a data-testid="url" href={release.downloadUrl}>
        Download
      </a>
    </div>
  );
}

describe("BeaconStage & useLatestRelease", () => {
  beforeEach(() => {
    Object.defineProperty(window, "matchMedia", {
      writable: true,
      value: vi.fn().mockImplementation((query) => ({
        matches: true,
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

  it("7x7, 5x5, 3x3 정육면체 피라미드 비콘 SVG, Ru-by-Beacon 헤더 및 미니멀 Scroll 버튼을 렌더링해야 한다", () => {
    render(<BeaconStage />);

    expect(screen.getByRole("img", { name: "7x7, 5x5, 3x3 루비 비콘 피라미드" })).toBeDefined();
    expect(screen.getByLabelText("Ru-Beacon Brand Headline")).toBeDefined();
    expect(screen.getByText("Ru")).toBeDefined();
    expect(screen.getByText("by")).toBeDefined();
    expect(screen.getByText("Beacon")).toBeDefined();
    expect(screen.getByText("마인크래프트 · 디스코드 실시간 연동 서비스")).toBeDefined();
    expect(screen.getByText("0.00ms Tick Occupancy")).toBeDefined();
    expect(screen.getByText("Scroll")).toBeDefined();
    expect(screen.getByRole("button", { name: "아래로 스크롤" })).toBeDefined();
  });

  it("GitHub Releases API 응답에서 최신 태그와 JAR 다운로드 링크를 추출해야 한다", async () => {
    const fetchSpy = vi.spyOn(global, "fetch").mockResolvedValueOnce({
      ok: true,
      json: async () => ({
        tag_name: "v1.2.0",
        html_url: "https://github.com/mmmphyun/ru-beacon/releases/tag/v1.2.0",
        assets: [
          {
            name: "ru-beacon-plugin-1.2.0.jar",
            browser_download_url:
              "https://github.com/mmmphyun/ru-beacon/releases/download/v1.2.0/ru-beacon-plugin-1.2.0.jar",
          },
        ],
      }),
    } as Response);

    render(<ReleaseTestView />);

    await waitFor(() => {
      expect(screen.getByTestId("tag").textContent).toBe("v1.2.0");
    });
    expect(screen.getByTestId("url").getAttribute("href")).toBe(
      "https://github.com/mmmphyun/ru-beacon/releases/download/v1.2.0/ru-beacon-plugin-1.2.0.jar"
    );

    fetchSpy.mockRestore();
  });
});
