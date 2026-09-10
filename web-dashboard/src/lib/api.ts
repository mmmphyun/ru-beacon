import {
  WorkflowSummary,
  WorkflowDetail,
  TenantConfig,
  InstanceItem,
} from "@/types/workflow";

const API_BASE = process.env.NEXT_PUBLIC_API_URL || "http://localhost:8080";

// 인메모리 Mock 저장소 (백엔드 오프라인 시 자동 Fallback)
const mockWorkflows: WorkflowSummary[] = [
  {
    id: "wf_demo_01",
    tenantId: "tenant_default",
    name: "레벨 30 달성 축하 공지",
    description: "플레이어가 30레벨에 도달하면 Discord 채널에 축하 메시지 전송",
    activeVersion: 1,
    createdAt: new Date().toISOString(),
    updatedAt: new Date().toISOString(),
  },
];

const mockWorkflowDetails: Record<string, WorkflowDetail> = {
  wf_demo_01: {
    id: "wf_demo_01",
    tenantId: "tenant_default",
    name: "레벨 30 달성 축하 공지",
    description: "플레이어가 30레벨에 도달하면 Discord 채널에 축하 메시지 전송",
    activeVersion: 1,
    versions: [
      {
        id: "wfv_01",
        version: 1,
        status: "ACTIVE",
        definition: JSON.stringify({
          version: 1,
          trigger: { id: "trigger_01", event_type: "MINECRAFT_LEVEL_UP", next_node_ids: ["condition_01"] },
          nodes: [
            {
              id: "condition_01",
              node_type: "CONDITION_BRANCH",
              inputs: { field: "new_level", operator: "GREATER_OR_EQUAL", expected_value: "30" },
              branches: { on_true: ["action_01"], on_false: [] },
              next_node_ids: [],
            },
            {
              id: "action_01",
              node_type: "DISCORD_SEND_MESSAGE",
              inputs: { channel_id: "1234567890", message: "축하합니다! {User_Nickname}님이 {Level}레벨에 도달했습니다!" },
              next_node_ids: [],
            },
          ],
        }),
        publishedAt: new Date().toISOString(),
        createdAt: new Date().toISOString(),
      },
    ],
  },
};

let mockTenant: TenantConfig & { instances: InstanceItem[] } = {
  tenantId: "tenant_default",
  name: "루비콘 마인크래프트 커뮤니티",
  discordGuildId: "987654321098765432",
  policyMode: "MONITOR",
  authChannelId: "112233445566778899",
  instances: [
    {
      id: "inst_main_backend",
      name: "생존 1서버",
      instanceType: "BACKEND",
      status: "ONLINE",
      lastHeartbeatAt: new Date().toISOString(),
    },
  ],
};

export async function fetchWorkflows(tenantId: string): Promise<WorkflowSummary[]> {
  try {
    const res = await fetch(`${API_BASE}/api/v1/workflows?tenantId=${encodeURIComponent(tenantId)}`);
    if (res.ok) return await res.json();
  } catch (_: unknown) {
    // offline fallback
  }
  return mockWorkflows.filter((w) => w.tenantId === tenantId);
}

export async function fetchWorkflowDetail(id: string): Promise<WorkflowDetail | null> {
  try {
    const res = await fetch(`${API_BASE}/api/v1/workflows/${id}`);
    if (res.ok) return await res.json();
  } catch (_: unknown) {
    // offline fallback
  }
  return mockWorkflowDetails[id] || null;
}

export async function createWorkflow(data: {
  tenantId: string;
  name: string;
  description?: string;
  definition: string;
}): Promise<{ workflowId: string; version: number; status: string }> {
  try {
    const res = await fetch(`${API_BASE}/api/v1/workflows`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(data),
    });
    if (res.ok) return await res.json();
  } catch (_: unknown) {
    // offline fallback
  }

  const id = "wf_" + Math.random().toString(36).substring(2, 10);
  const newSummary: WorkflowSummary = {
    id,
    tenantId: data.tenantId,
    name: data.name,
    description: data.description || null,
    activeVersion: null,
    createdAt: new Date().toISOString(),
    updatedAt: new Date().toISOString(),
  };
  mockWorkflows.unshift(newSummary);
  mockWorkflowDetails[id] = {
    ...newSummary,
    versions: [
      {
        id: "wfv_1",
        version: 1,
        status: "DRAFT",
        definition: data.definition,
        publishedAt: null,
        createdAt: new Date().toISOString(),
      },
    ],
  };

  return { workflowId: id, version: 1, status: "DRAFT" };
}

export async function createWorkflowVersion(
  workflowId: string,
  definition: string
): Promise<{ workflowId: string; version: number; status: string }> {
  try {
    const res = await fetch(`${API_BASE}/api/v1/workflows/${workflowId}/versions`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ definition }),
    });
    if (res.ok) return await res.json();
  } catch (_: unknown) {
    // offline fallback
  }

  const detail = mockWorkflowDetails[workflowId];
  const nextVer = (detail?.versions.length ?? 0) + 1;
  if (detail) {
    detail.versions.unshift({
      id: `wfv_${nextVer}`,
      version: nextVer,
      status: "DRAFT",
      definition,
      publishedAt: null,
      createdAt: new Date().toISOString(),
    });
  }

  return { workflowId, version: nextVer, status: "DRAFT" };
}

export async function deployWorkflow(
  workflowId: string,
  version?: number
): Promise<{ workflowId: string; activeVersion: number; status: string }> {
  const res = await fetch(`${API_BASE}/api/v1/workflows/${workflowId}/deploy`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ version }),
  }).catch(() => null);

  if (res && res.ok) {
    return await res.json();
  }

  // offline fallback
  const detail = mockWorkflowDetails[workflowId];
  const targetVer = version ?? detail?.versions[0]?.version ?? 1;
  if (detail) {
    detail.activeVersion = targetVer;
    detail.versions.forEach((v) => {
      if (v.version === targetVer) {
        v.status = "ACTIVE";
        v.publishedAt = new Date().toISOString();
      } else if (v.status === "ACTIVE") {
        v.status = "INACTIVE";
      }
    });
  }
  const summary = mockWorkflows.find((w) => w.id === workflowId);
  if (summary) summary.activeVersion = targetVer;

  return { workflowId, activeVersion: targetVer, status: "ACTIVE" };
}

export async function rollbackWorkflow(
  workflowId: string,
  targetVersion: number
): Promise<{ workflowId: string; activeVersion: number; status: string }> {
  const res = await fetch(`${API_BASE}/api/v1/workflows/${workflowId}/rollback`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ targetVersion }),
  }).catch(() => null);

  if (res && res.ok) {
    return await res.json();
  }

  // offline fallback
  return deployWorkflow(workflowId, targetVersion);
}

export async function saveOnboarding(data: TenantConfig): Promise<{ tenantId: string; status: string }> {
  try {
    const res = await fetch(`${API_BASE}/api/v1/tenants/onboarding`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(data),
    });
    if (res.ok) return await res.json();
  } catch (_: unknown) {
    // offline fallback
  }

  mockTenant = {
    ...mockTenant,
    ...data,
  };
  return { tenantId: data.tenantId, status: "CONFIGURED" };
}

export async function fetchTenantDetail(
  tenantId: string
): Promise<(TenantConfig & { instances: InstanceItem[] }) | null> {
  try {
    const res = await fetch(`${API_BASE}/api/v1/tenants/${tenantId}`);
    if (res.ok) return await res.json();
  } catch (_: unknown) {
    // offline fallback
  }
  return mockTenant;
}

export async function issueInstanceToken(data: {
  tenantId: string;
  instanceId: string;
  name: string;
  instanceType: "PROXY" | "BACKEND";
}): Promise<{ instanceId: string; token: string; status: string }> {
  try {
    const res = await fetch(`${API_BASE}/api/v1/instances/token`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(data),
    });
    if (res.ok) return await res.json();
  } catch (_: unknown) {
    // offline fallback
  }

  const generatedToken = "rb_tok_" + Math.random().toString(36).substring(2, 15) + Math.random().toString(36).substring(2, 15);
  mockTenant.instances.push({
    id: data.instanceId,
    name: data.name,
    instanceType: data.instanceType,
    status: "OFFLINE",
    lastHeartbeatAt: null,
  });

  return {
    instanceId: data.instanceId,
    token: generatedToken,
    status: "OFFLINE",
  };
}
