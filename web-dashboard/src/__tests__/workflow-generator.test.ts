import { describe, it, expect } from "vitest";
import {
  generateWorkflowAst,
  validateWorkflowState,
  detectCycleInAst,
  WORKFLOW_PRESETS,
} from "../lib/workflow-generator";
import { WizardFormState } from "../types/workflow";

describe("Workflow Generator & AST Spec", () => {
  it("조건이 활성화된 폼 상태로부터 유효한 3단계 DAG AST를 생성해야 한다", () => {
    const form: WizardFormState = {
      workflowName: "레벨업 축하",
      description: "레벨 30 도달 시",
      triggerType: "MINECRAFT_LEVEL_UP",
      triggerParams: {},
      enableCondition: true,
      conditionField: "new_level",
      conditionOperator: "GREATER_OR_EQUAL",
      conditionValue: "30",
      actionType: "DISCORD_SEND_MESSAGE",
      actionParams: {
        channelId: "999888777",
        message: "축하합니다! {User_Nickname}님이 {Level}레벨 달성!",
      },
    };

    const ast = generateWorkflowAst(form, 1);

    expect(ast.version).toBe(1);
    expect(ast.trigger.event_type).toBe("MINECRAFT_LEVEL_UP");
    expect(ast.trigger.next_node_ids).toEqual(["condition_01"]);

    expect(ast.nodes.length).toBe(2);
    const condNode = ast.nodes.find((n) => n.id === "condition_01");
    expect(condNode?.node_type).toBe("CONDITION_BRANCH");
    expect(condNode?.branches?.on_true).toEqual(["action_01"]);

    const actNode = ast.nodes.find((n) => n.id === "action_01");
    expect(actNode?.node_type).toBe("DISCORD_SEND_MESSAGE");
    expect(actNode?.inputs?.channel_id).toBe("999888777");
  });

  it("조건이 비활성화된 경우 트리거가 액션 노드를 직접 가리켜야 한다", () => {
    const form: WizardFormState = {
      workflowName: "출석체크",
      description: "버튼 클릭 시",
      triggerType: "DISCORD_BUTTON_CLICK",
      triggerParams: { customId: "btn_attend" },
      enableCondition: false,
      conditionField: "",
      conditionOperator: "EQUALS",
      conditionValue: "",
      actionType: "ATTENDANCE_RESERVATION",
      actionParams: { totalLimit: 50 },
    };

    const ast = generateWorkflowAst(form, 2);

    expect(ast.version).toBe(2);
    expect(ast.trigger.next_node_ids).toEqual(["action_01"]);
    expect(ast.nodes.length).toBe(1);
    expect(ast.nodes[0].id).toBe("action_01");
    expect(ast.nodes[0].inputs?.total_limit).toBe(50);
  });

  it("허용되지 않은 템플릿 변수가 포함된 경우 유효성 검증에서 거부해야 한다", () => {
    const invalidForm: WizardFormState = {
      workflowName: "잘못된 변수 테스트",
      description: "미정의 변수 사용",
      triggerType: "MINECRAFT_LEVEL_UP",
      triggerParams: {},
      enableCondition: false,
      conditionField: "",
      conditionOperator: "EQUALS",
      conditionValue: "",
      actionType: "DISCORD_SEND_MESSAGE",
      actionParams: {
        channelId: "123",
        message: "알림: {Unknown_Secret_Variable} 발견",
      },
    };

    const result = validateWorkflowState(invalidForm);
    expect(result.valid).toBe(false);
    expect(result.errors.some((e) => e.includes("Unknown_Secret_Variable"))).toBe(true);
  });

  it("필수 입력 항목(워크플로우 이름 등)이 비어있으면 오류를 보고해야 한다", () => {
    const emptyForm: WizardFormState = {
      ...WORKFLOW_PRESETS[0].state,
      workflowName: "",
    };

    const result = validateWorkflowState(emptyForm);
    expect(result.valid).toBe(false);
    expect(result.errors).toContain("워크플로우 이름을 입력해야 합니다.");
  });

  it("DAG AST에 사이클이 없으면 detectCycleInAst가 null을 반환해야 한다", () => {
    const ast = generateWorkflowAst(WORKFLOW_PRESETS[0].state);
    const cycle = detectCycleInAst(ast);
    expect(cycle).toBeNull();
  });

  it("노드 간 순환 참조가 존재하면 detectCycleInAst가 사이클 노드 경로를 반환해야 한다", () => {
    const cyclicAst = {
      version: 1,
      trigger: { id: "t1", event_type: "EVENT", next_node_ids: ["n1"] },
      nodes: [
        { id: "n1", node_type: "TEST", next_node_ids: ["n2"] },
        { id: "n2", node_type: "TEST", next_node_ids: ["n1"] },
      ],
    };

    const cycle = detectCycleInAst(cyclicAst);
    expect(cycle).not.toBeNull();
    expect(cycle).toContain("n1");
    expect(cycle).toContain("n2");
  });

  it("선착순 정원이 0 이하인 경우 유효성 검증에서 거부해야 한다", () => {
    const form: WizardFormState = {
      ...WORKFLOW_PRESETS[1].state,
      actionParams: { totalLimit: 0 },
    };
    const result = validateWorkflowState(form);
    expect(result.valid).toBe(false);
    expect(result.errors).toContain("선착순 정원은 1명 이상이어야 합니다.");
  });

  it("조건이 활성화되었으나 조건 필드가 비어있으면 오류를 보고해야 한다", () => {
    const form: WizardFormState = {
      ...WORKFLOW_PRESETS[0].state,
      conditionField: "   ",
    };
    const result = validateWorkflowState(form);
    expect(result.valid).toBe(false);
    expect(result.errors).toContain("조건 검사 필드를 입력해야 합니다.");
  });

  it("마인크래프트 명령어 액션에서 빈 명령어가 들어오면 오류를 보고해야 한다", () => {
    const form: WizardFormState = {
      workflowName: "명령어 실행",
      description: "테스트",
      triggerType: "MINECRAFT_LEVEL_UP",
      triggerParams: {},
      enableCondition: false,
      conditionField: "",
      conditionOperator: "EQUALS",
      conditionValue: "",
      actionType: "MINECRAFT_DISPATCH_COMMAND",
      actionParams: { command: "" },
    };
    const result = validateWorkflowState(form);
    expect(result.valid).toBe(false);
    expect(result.errors).toContain("실행할 Minecraft 명령어를 입력해야 합니다.");
  });
});
