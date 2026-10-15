import React from "react";
import { render, screen, fireEvent, waitFor, act } from "@testing-library/react";
import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import { WorkflowWizard } from "../components/workflow/WorkflowWizard";

describe("WorkflowWizard Component", () => {
  beforeEach(() => {
    vi.restoreAllMocks();
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  it("초기 렌더링 시 1단계 트리거 선택 화면과 에이전트 커맨드 바가 정상 노출되어야 한다", () => {
    render(<WorkflowWizard tenantId="tenant_test" />);

    expect(screen.getByText("Ru-Beacon 에이전트")).toBeDefined();
    expect(screen.getByText("자연어 자동 생성")).toBeDefined();
    expect(screen.getByPlaceholderText(/플레이어가 50레벨을 달성하면/)).toBeDefined();
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

  it("확신도 >= 0.7 자연어 의도 입력 시 폼이 자동 설정되고 Step 2로 연계되어야 한다", async () => {
    vi.useFakeTimers();

    const mockResponse = {
      formState: {
        workflowName: "50레벨 달성 축하",
        description: "50레벨 도달 시 축하 메시지 전송",
        triggerType: "MINECRAFT_LEVEL_UP",
        hasCondition: true,
        conditionType: "LEVEL_GREATER_THAN",
        conditionValue: "50",
        actionType: "DISCORD_EMBED",
        discordChannelId: "1234567890",
        discordMessageTemplate: "축하합니다! 50레벨 달성!",
        color: "0xFEE75C",
      },
      confidence: 0.88,
      explanation: "플레이어 레벨업 트리거 및 50레벨 조건, 임베드 메시지 액션을 구성했습니다.",
    };

    global.fetch = vi.fn().mockResolvedValue({
      ok: true,
      status: 200,
      json: async () => mockResponse,
    } as Response);

    render(<WorkflowWizard tenantId="tenant_test" />);

    const input = screen.getByPlaceholderText(/플레이어가 50레벨을 달성하면/);
    fireEvent.change(input, { target: { value: "50레벨 달성하면 디스코드 임베드 보내줘" } });

    const generateBtn = screen.getByText("생성");
    fireEvent.click(generateBtn);

    await act(async () => {
      await Promise.resolve();
    });

    // 성공 배너 및 폼 상태 반영 확인
    expect(screen.getByText(/50레벨 조건, 임베드 메시지 액션을 구성했습니다/)).toBeDefined();
    expect(screen.getByDisplayValue("50레벨 달성 축하")).toBeDefined();

    // 500ms 경과 후 Step 2 이동 확인
    act(() => {
      vi.advanceTimersByTime(500);
    });

    expect(screen.getByText("2단계: 조건부 필터 설정 (선택)")).toBeDefined();

    vi.useRealTimers();
  });

  it("0.0 < 확신도 < 0.7일 때 모호성 배너가 노출되고 추천 적용 시 Step 2로 이동해야 한다", async () => {
    vi.useFakeTimers();

    const mockResponse = {
      formState: {
        workflowName: "추천 워크플로우",
        description: "모호한 요청 추론 결과",
        triggerType: "MINECRAFT_LEVEL_UP",
        hasCondition: false,
        conditionType: "LEVEL_GREATER_THAN",
        conditionValue: "10",
        actionType: "DISCORD_MESSAGE",
        discordChannelId: "",
        discordMessageTemplate: "알림",
        color: "0x5865F2",
      },
      confidence: 0.55,
      explanation: "플레이어 레벨업 기본 알림",
    };

    global.fetch = vi.fn().mockResolvedValue({
      ok: true,
      status: 200,
      json: async () => mockResponse,
    } as Response);

    render(<WorkflowWizard tenantId="tenant_test" />);

    const input = screen.getByPlaceholderText(/플레이어가 50레벨을 달성하면/);
    fireEvent.change(input, { target: { value: "알림 하나 줘" } });

    fireEvent.click(screen.getByText("생성"));

    await act(async () => {
      await Promise.resolve();
    });

    // 모호성 배너 및 적용 버튼 확인
    expect(screen.getByText(/의도가 모호합니다: 혹시 '플레이어 레벨업 기본 알림'을\(를\) 원하셨습니까\?/)).toBeDefined();
    const applyBtn = screen.getByText("추천 설정 적용하기");
    expect(applyBtn).toBeDefined();

    // 적용 버튼 클릭
    fireEvent.click(applyBtn);
    expect(screen.getByDisplayValue("추천 워크플로우")).toBeDefined();

    // 500ms 후 Step 2로 이동
    act(() => {
      vi.advanceTimersByTime(500);
    });

    expect(screen.getByText("2단계: 조건부 필터 설정 (선택)")).toBeDefined();

    vi.useRealTimers();
  });

  it("미지원 요청(400) 시 에러 가이드 배너가 노출되어야 한다", async () => {
    global.fetch = vi.fn().mockResolvedValue({
      ok: false,
      status: 400,
      json: async () => ({
        error: "BAD_REQUEST",
        message: "지원하지 않는 워크플로우 요구사항입니다. 마인크래프트 레벨업, 발전과제, 또는 Discord 버튼 이벤트만 지원됩니다.",
        confidence: 0.0,
      }),
    } as Response);

    render(<WorkflowWizard tenantId="tenant_test" />);

    const input = screen.getByPlaceholderText(/플레이어가 50레벨을 달성하면/);
    fireEvent.change(input, { target: { value: "주식 시장 분석해줘" } });

    fireEvent.click(screen.getByText("생성"));

    await waitFor(() => {
      expect(
        screen.getByText("지원하지 않는 워크플로우 요구사항입니다. 마인크래프트 레벨업, 발전과제, 또는 Discord 버튼 이벤트만 지원됩니다.")
      ).toBeDefined();
    });
  });

  it("레이트 리밋 초과(429) 시 쿼터 제한 경고 배너가 노출되어야 한다", async () => {
    global.fetch = vi.fn().mockResolvedValue({
      ok: false,
      status: 429,
      json: async () => ({
        error: "RATE_LIMIT_EXCEEDED",
        message: "요청 한도를 초과했습니다 (분당 2회, 일일 5회 제한). 잠시 후 다시 시도해주세요.",
      }),
    } as Response);

    render(<WorkflowWizard tenantId="tenant_test" />);

    const input = screen.getByPlaceholderText(/플레이어가 50레벨을 달성하면/);
    fireEvent.change(input, { target: { value: "빠른 반복 요청" } });

    fireEvent.click(screen.getByText("생성"));

    await waitFor(() => {
      expect(
        screen.getByText("요청 한도를 초과했습니다 (분당 2회, 일일 5회 제한). 잠시 후 다시 시도해주세요.")
      ).toBeDefined();
    });
  });
});
