import { WizardFormState, WorkflowDefinition, WorkflowNode } from "@/types/workflow";

export const ALLOWED_VARIABLES = [
  "User_Nickname",
  "Minecraft_UUID",
  "Discord_User",
  "Discord_User_ID",
  "Server_Name",
  "Current_Time",
  "Level",
  "Advancement",
];

export interface ValidationResult {
  valid: boolean;
  errors: string[];
}

/**
 * 위저드 폼 상태로부터 백엔드 표준 DAG WorkflowDefinition JSON AST를 생성함.
 * docs/WORKFLOW_ENGINE_SPEC.md 규격 준수.
 */
export function generateWorkflowAst(form: WizardFormState, version: number = 1): WorkflowDefinition {
  const triggerNodeId = "trigger_01";
  const conditionNodeId = "condition_01";
  const actionNodeId = "action_01";

  const nodes: WorkflowNode[] = [];

  if (form.enableCondition) {
    // 트리거 -> 조건 분기 -> 액션
    nodes.push({
      id: conditionNodeId,
      node_type: "CONDITION_BRANCH",
      inputs: {
        field: form.conditionField,
        operator: form.conditionOperator,
        expected_value: form.conditionValue,
      },
      branches: {
        on_true: [actionNodeId],
        on_false: [],
      },
      next_node_ids: [],
    });
  }

  // 액션 노드 구성
  const actionInputs: Record<string, unknown> = {};
  switch (form.actionType) {
    case "DISCORD_SEND_MESSAGE":
      actionInputs["channel_id"] = form.actionParams.channelId || "";
      actionInputs["message"] = form.actionParams.message || "";
      break;
    case "DISCORD_ADD_ROLE":
      actionInputs["role_id"] = form.actionParams.roleId || "";
      break;
    case "MINECRAFT_DISPATCH_COMMAND":
      actionInputs["command"] = form.actionParams.command || "";
      break;
    case "ATTENDANCE_RESERVATION":
      actionInputs["total_limit"] = form.actionParams.totalLimit ?? 100;
      break;
  }

  nodes.push({
    id: actionNodeId,
    node_type: form.actionType,
    inputs: actionInputs,
    next_node_ids: [],
  });

  return {
    version,
    variables: {
      Server_Name: "Ru-Beacon Main",
    },
    trigger: {
      id: triggerNodeId,
      event_type: form.triggerType,
      next_node_ids: [form.enableCondition ? conditionNodeId : actionNodeId],
    },
    nodes,
  };
}

/**
 * 폼 입력값 및 템플릿 변수 문법을 검증함.
 */
export function validateWorkflowState(form: WizardFormState): ValidationResult {
  const errors: string[] = [];

  if (!form.workflowName.trim()) {
    errors.push("워크플로우 이름을 입력해야 합니다.");
  }

  if (form.enableCondition) {
    if (!form.conditionField.trim()) {
      errors.push("조건 검사 필드를 입력해야 합니다.");
    }
    if (!form.conditionValue.trim()) {
      errors.push("조건 비교 기준값을 입력해야 합니다.");
    }
  }

  // 액션 필드 검증
  if (form.actionType === "DISCORD_SEND_MESSAGE") {
    if (!form.actionParams.channelId?.trim()) {
      errors.push("메시지를 전송할 Discord 채널 ID가 필요합니다.");
    }
    if (!form.actionParams.message?.trim()) {
      errors.push("전송할 메시지 본문을 입력해야 합니다.");
    } else {
      // 변수 플레이스홀더 검증: {Unknown_Var} 차단
      const varMatches = form.actionParams.message.match(/\{([a-zA-Z0-9_]+)\}/g);
      if (varMatches) {
        for (const match of varMatches) {
          const varName = match.slice(1, -1);
          if (!ALLOWED_VARIABLES.includes(varName)) {
            errors.push(`허용되지 않은 템플릿 변수: {${varName}} (허용 목록: ${ALLOWED_VARIABLES.join(", ")})`);
          }
        }
      }
    }
  } else if (form.actionType === "DISCORD_ADD_ROLE") {
    if (!form.actionParams.roleId?.trim()) {
      errors.push("부여할 Discord 역할 ID가 필요합니다.");
    }
  } else if (form.actionType === "MINECRAFT_DISPATCH_COMMAND") {
    if (!form.actionParams.command?.trim()) {
      errors.push("실행할 Minecraft 명령어를 입력해야 합니다.");
    }
  } else if (form.actionType === "ATTENDANCE_RESERVATION") {
    if ((form.actionParams.totalLimit ?? 0) <= 0) {
      errors.push("선착순 정원은 1명 이상이어야 합니다.");
    }
  }

  return {
    valid: errors.length === 0,
    errors,
  };
}

/**
 * AST 내 순환 참조(Cycle)를 사전 검증함.
 */
export function detectCycleInAst(definition: WorkflowDefinition): string[] | null {
  const adjacency = new Map<string, string[]>();

  for (const node of definition.nodes) {
    const targets: string[] = [...(node.next_node_ids || [])];
    if (node.branches) {
      Object.values(node.branches).forEach((branch) => {
        if (Array.isArray(branch)) targets.push(...branch);
      });
    }
    adjacency.set(node.id, targets);
  }

  const visited = new Map<string, number>(); // 0: unvisited, 1: visiting, 2: visited
  const path: string[] = [];

  function dfs(curr: string): string[] | null {
    visited.set(curr, 1);
    path.push(curr);

    for (const next of adjacency.get(curr) || []) {
      const state = visited.get(next) ?? 0;
      if (state === 1) {
        const cycleStart = path.indexOf(next);
        return [...path.slice(cycleStart), next];
      }
      if (state === 0) {
        const res = dfs(next);
        if (res) return res;
      }
    }

    path.pop();
    visited.set(curr, 2);
    return null;
  }

  for (const nodeId of adjacency.keys()) {
    if ((visited.get(nodeId) ?? 0) === 0) {
      const cycle = dfs(nodeId);
      if (cycle) return cycle;
    }
  }

  return null;
}

// 기본 템플릿 프리셋
export const WORKFLOW_PRESETS: { label: string; state: WizardFormState }[] = [
  {
    label: "플레이어 레벨 30 달성 디스코드 축하 알림",
    state: {
      workflowName: "레벨 30 달성 축하 공지",
      description: "플레이어가 30레벨에 도달하면 Discord #축하 채널에 알림을 발송합니다.",
      triggerType: "MINECRAFT_LEVEL_UP",
      triggerParams: {},
      enableCondition: true,
      conditionField: "new_level",
      conditionOperator: "GREATER_OR_EQUAL",
      conditionValue: "30",
      actionType: "DISCORD_SEND_MESSAGE",
      actionParams: {
        channelId: "123456789012345678",
        message: "축하합니다! {User_Nickname}님이 {Level}레벨에 도달했습니다!",
      },
    },
  },
  {
    label: "Discord 버튼 클릭 100명 한정 선착순 출석체크",
    state: {
      workflowName: "일일 한정 출석체크",
      description: "Discord 출석 버튼 클릭 시 Redis 2-Tier 쿼터를 통해 선착순 100명에게 보상을 예약합니다.",
      triggerType: "DISCORD_BUTTON_CLICK",
      triggerParams: {
        customId: "btn_daily_attendance",
      },
      enableCondition: false,
      conditionField: "",
      conditionOperator: "EQUALS",
      conditionValue: "",
      actionType: "ATTENDANCE_RESERVATION",
      actionParams: {
        totalLimit: 100,
      },
    },
  },
];
