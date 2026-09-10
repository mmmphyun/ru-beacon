import React from "react";
import { render, screen, fireEvent } from "@testing-library/react";
import { describe, it, expect } from "vitest";
import { WorkflowWizard } from "../components/workflow/WorkflowWizard";

describe("WorkflowWizard Component", () => {
  it("초기 렌더링 시 1단계 트리거 선택 화면이 정상 노출되어야 한다", () => {
    render(<WorkflowWizard tenantId="tenant_test" />);

    expect(screen.getByText("1단계: 시작 트리거 이벤트 선택")).toBeDefined();
    expect(screen.getByText("플레이어 레벨업")).toBeDefined();
    expect(screen.getByText("발전과제 달성")).toBeDefined();
    expect(screen.getByText("Workflow AST (JSONB)")).toBeDefined();
  });

  it("다음/이전 버튼 클릭 시 단계(Step)가 전환되어야 한다", () => {
    render(<WorkflowWizard tenantId="tenant_test" />);

    // 1단계 -> 2단계
    const nextButton = screen.getByText("다음");
    fireEvent.click(nextButton);

    expect(screen.getByText("2단계: 조건부 필터 설정 (선택)")).toBeDefined();
    expect(screen.getByText("조건부 분기 노드 (CONDITION_BRANCH) 활성화")).toBeDefined();

    // 2단계 -> 3단계
    fireEvent.click(screen.getByText("다음"));
    expect(screen.getByText("3단계: 실행할 액션 지정")).toBeDefined();
    expect(screen.getByText("Discord 메시지 전송")).toBeDefined();

    // 이전 버튼
    const prevButton = screen.getByText("이전");
    fireEvent.click(prevButton);
    expect(screen.getByText("2단계: 조건부 필터 설정 (선택)")).toBeDefined();
  });

  it("빠른 템플릿 프리셋 클릭 시 폼 및 AST가 즉시 갱신되어야 한다", () => {
    render(<WorkflowWizard tenantId="tenant_test" />);

    const presetBtn = screen.getByText("Discord 버튼 클릭 100명 한정 선착순 출석체크");
    fireEvent.click(presetBtn);

    expect(screen.getByDisplayValue("일일 한정 출석체크")).toBeDefined();
  });
});
