import { describe, it, expect, vi } from "vitest";
import React from "react";
import { render, screen, fireEvent, act } from "@testing-library/react";
import { InteractiveSimulator } from "../components/landing/InteractiveSimulator";

describe("InteractiveSimulator Telemetry Showcase", () => {
  it("루비-샌드 3대 텔레메트리 노드와 지연 시간 수치를 정확히 렌더링해야 한다", () => {
    render(<InteractiveSimulator />);

    expect(screen.getByText("루비 신호기 (비콘)")).toBeDefined();
    expect(screen.getByText("LMAX Disruptor 링버퍼")).toBeDefined();
    expect(screen.getByText("디스코드 게이트웨이")).toBeDefined();

    expect(screen.getByText("0.08ms")).toBeDefined();
    expect(screen.getByText("0.00ms (완전 비동기)")).toBeDefined();
    expect(screen.getByText("평균 45ms 도착")).toBeDefined();
  });

  it("격발 버튼 클릭 시 파이프라인 통과 및 완료 토스트를 출력해야 한다", async () => {
    vi.useFakeTimers();
    render(<InteractiveSimulator />);

    const triggerBtn = screen.getByRole("button", { name: /루비 신호기 이벤트 격발 테스트/ });
    fireEvent.click(triggerBtn);

    act(() => {
      vi.advanceTimersByTime(400);
    });

    expect(
      screen.getByText("메인 틱 점유 0.00ms, 평균 45ms 만에 디스코드 임베드가 발송되었습니다.")
    ).toBeDefined();

    vi.useRealTimers();
  });
});
