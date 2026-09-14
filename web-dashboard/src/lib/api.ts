import {
  WorkflowSummary,
  WorkflowDetail,
  TenantConfig,
  InstanceItem,
  UserProfile,
  AuditLogPage,
  AuditLogItem,
} from "@/types/workflow";

const API_BASE = process.env.NEXT_PUBLIC_API_URL || "http://localhost:8080";

/**
 * credentials: 'include'를 기본 적용하는 중앙 집중식 fetch 함수
 */
async function fetchApi<T>(
  endpoint: string,
  options: RequestInit = {}
): Promise<{ ok: boolean; status: number; data?: T; error?: string }> {
  try {
    const res = await fetch(`${API_BASE}${endpoint}`, {
      ...options,
      credentials: "include",
      headers: {
        "Content-Type": "application/json",
        ...(options.headers || {}),
      },
    });

    if (!res.ok) {
      const errText = await res.text().catch(() => "");
      return { ok: false, status: res.status, error: errText || res.statusText };
    }

    const data = (await res.json().catch(() => ({}))) as T;
    return { ok: true, status: res.status, data };
  } catch (err: unknown) {
    return { ok: false, status: 0, error: err instanceof Error ? err.message : "Network error" };
  }
}

// 인메모리 Mock 저장소 (백엔드 오프라인 시 자동 Fallback)
const mockUserProfile: UserProfile = {
  discordUserId: "dev_user_999",
  username: "RuBeaconOwner",
  avatar: null,
  isPlatformSuperAdmin: true,
  tenants: [
    {
      tenantId: "tenant_default",
      guildName: "루비콘 공식 마인크래프트 서버",
      role: "OWNER",
    },
  ],
};

const mockAuditLogs: AuditLogItem[] = [
  {
    id: "audit_01",
    tenantId: "tenant_default",
    correlationId: "corr_abc123",
    actorType: "DISCORD_USER",
    actorId: "dev_user_999",
    action: "INSTANCE_TOKEN_ISSUED",
    targetType: "INSTANCE",
    targetId: "inst_main_backend",
    status: "SUCCESS",
    details: "서버 인스턴스 토큰 발급 완료 (생존 1서버)",
    ipAddress: "127.0.***.***",
    createdAt: new Date(Date.now() - 1000 * 60 * 5).toISOString(),
  },
  {
    id: "audit_02",
    tenantId: "tenant_default",
    correlationId: "corr_xyz789",
    actorType: "DISCORD_USER",
    actorId: "dev_user_999",
    action: "WORKFLOW_DEPLOYED",
    targetType: "WORKFLOW",
    targetId: "wf_demo_01",
    status: "SUCCESS",
    details: "버전 1 활성화 배포 완료",
    ipAddress: "127.0.***.***",
    createdAt: new Date(Date.now() - 1000 * 60 * 30).toISOString(),
  },
];

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
  {
    id: "wf_demo_02",
    tenantId: "tenant_default",
    name: "디스코드 출석체크 다이아 지급",
    description: "디스코드 #출석 채널 버튼 클릭 시 인게임 플레이어에게 다이아몬드 3개 지급",
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
              inputs: { channel_id: "1234567890", message: "축하합니다! {player_name}님이 {new_level}레벨에 도달했습니다!" },
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

/**
 * 세션 및 사용자 프로필 조회 (GET /api/v1/auth/me)
 */
export async function fetchUserProfile(): Promise<UserProfile | null> {
  const res = await fetchApi<UserProfile>("/api/v1/auth/me");
  if (res.ok && res.data) {
    return res.data;
  }
  return mockUserProfile;
}

/**
 * 개발용 목 세션 획득 (POST /api/v1/auth/dev-mock-login)
 */
export async function devMockLogin(
  discordUserId: string = "dev_user_999",
  username: string = "RuBeaconOwner",
  role: "OWNER" | "ADMIN" = "OWNER"
): Promise<boolean> {
  const res = await fetchApi<{ status: string }>("/api/v1/auth/dev-mock-login", {
    method: "POST",
    body: JSON.stringify({ discordUserId, username }),
  });

  if (res.ok) return true;

  // 오프라인 fallback
  mockUserProfile.discordUserId = discordUserId;
  mockUserProfile.username = username;
  mockUserProfile.tenants[0].role = role;
  return true;
}

/**
 * 비즈니스 감사 로그 조회 (GET /api/v1/audit-logs)
 */
export async function fetchAuditLogs(params: {
  tenantId?: string;
  cursor?: string;
  limit?: number;
  adminSecret?: string;
}): Promise<AuditLogPage> {
  const query = new URLSearchParams();
  if (params.tenantId) query.set("tenantId", params.tenantId);
  if (params.cursor) query.set("cursor", params.cursor);
  if (params.limit) query.set("limit", params.limit.toString());

  const headers: Record<string, string> = {};
  if (params.adminSecret) {
    headers["X-Platform-Admin-Secret"] = params.adminSecret;
  }

  const res = await fetchApi<AuditLogPage>(`/api/v1/audit-logs?${query.toString()}`, {
    headers,
  });

  if (res.ok && res.data) {
    return res.data;
  }

  // 오프라인 fallback
  const filtered = params.tenantId
    ? mockAuditLogs.filter((l) => l.tenantId === params.tenantId)
    : mockAuditLogs;

  return {
    items: filtered,
    nextCursor: null,
  };
}

/**
 * 테넌트 워크플로우 목록 조회
 */
export async function fetchWorkflows(tenantId: string): Promise<WorkflowSummary[]> {
  const res = await fetchApi<WorkflowSummary[]>(`/api/v1/workflows?tenantId=${encodeURIComponent(tenantId)}`);
  if (res.ok && res.data) return res.data;
  return mockWorkflows.filter((w) => w.tenantId === tenantId);
}

/**
 * 워크플로우 상세 조회
 */
export async function fetchWorkflowDetail(id: string): Promise<WorkflowDetail | null> {
  const res = await fetchApi<WorkflowDetail>(`/api/v1/workflows/${id}`);
  if (res.ok && res.data) return res.data;
  return mockWorkflowDetails[id] || null;
}

/**
 * 신규 워크플로우 생성
 */
export async function createWorkflow(data: {
  tenantId: string;
  name: string;
  description?: string;
  definition: string;
}): Promise<{ workflowId: string; version: number; status: string }> {
  const res = await fetchApi<{ workflowId: string; version: number; status: string }>("/api/v1/workflows", {
    method: "POST",
    body: JSON.stringify(data),
  });

  if (res.ok && res.data) return res.data;

  // offline fallback
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

/**
 * 워크플로우 새 버전 생성
 */
export async function createWorkflowVersion(
  workflowId: string,
  definition: string
): Promise<{ workflowId: string; version: number; status: string }> {
  const res = await fetchApi<{ workflowId: string; version: number; status: string }>(
    `/api/v1/workflows/${workflowId}/versions`,
    {
      method: "POST",
      body: JSON.stringify({ definition }),
    }
  );

  if (res.ok && res.data) return res.data;

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

/**
 * 워크플로우 활성화 배포
 */
export async function deployWorkflow(
  workflowId: string,
  version?: number
): Promise<{ workflowId: string; activeVersion: number; status: string }> {
  const res = await fetchApi<{ workflowId: string; activeVersion: number; status: string }>(
    `/api/v1/workflows/${workflowId}/deploy`,
    {
      method: "POST",
      body: JSON.stringify({ version }),
    }
  );

  if (res.ok && res.data) return res.data;

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

/**
 * 워크플로우 롤백
 */
export async function rollbackWorkflow(
  workflowId: string,
  targetVersion: number
): Promise<{ workflowId: string; activeVersion: number; status: string }> {
  const res = await fetchApi<{ workflowId: string; activeVersion: number; status: string }>(
    `/api/v1/workflows/${workflowId}/rollback`,
    {
      method: "POST",
      body: JSON.stringify({ targetVersion }),
    }
  );

  if (res.ok && res.data) return res.data;

  return deployWorkflow(workflowId, targetVersion);
}

/**
 * 온보딩 설정 저장
 */
export async function saveOnboarding(data: TenantConfig): Promise<{ tenantId: string; status: string }> {
  const res = await fetchApi<{ tenantId: string; status: string }>("/api/v1/tenants/onboarding", {
    method: "POST",
    body: JSON.stringify(data),
  });

  if (res.ok && res.data) return res.data;

  mockTenant = { ...mockTenant, ...data };
  return { tenantId: data.tenantId, status: "CONFIGURED" };
}

/**
 * 테넌트 설정 및 인스턴스 목록 조회
 */
export async function fetchTenantDetail(
  tenantId: string
): Promise<(TenantConfig & { instances: InstanceItem[] }) | null> {
  const res = await fetchApi<TenantConfig & { instances: InstanceItem[] }>(`/api/v1/tenants/${tenantId}`);
  if (res.ok && res.data) return res.data;
  return mockTenant;
}

/**
 * 인스턴스 인증 토큰 발급 (OWNER 전용)
 */
export async function issueInstanceToken(data: {
  tenantId: string;
  instanceId: string;
  name: string;
  instanceType: "PROXY" | "BACKEND";
}): Promise<{ instanceId: string; token: string; status: string }> {
  const res = await fetchApi<{ instanceId: string; token: string; status: string }>("/api/v1/instances/token", {
    method: "POST",
    body: JSON.stringify(data),
  });

  if (res.ok && res.data) return res.data;

  const generatedToken =
    "rb_tok_" + Math.random().toString(36).substring(2, 15) + Math.random().toString(36).substring(2, 15);
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

