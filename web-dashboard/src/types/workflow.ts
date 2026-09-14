export interface WorkflowTrigger {
  id: string;
  event_type: string;
  next_node_ids: string[];
}

export interface WorkflowNode {
  id: string;
  node_type: string;
  inputs?: Record<string, unknown>;
  branches?: {
    on_true?: string[];
    on_false?: string[];
  };
  next_node_ids: string[];
}

export interface WorkflowDefinition {
  version: number;
  variables?: Record<string, string>;
  trigger: WorkflowTrigger;
  nodes: WorkflowNode[];
}

export interface WorkflowSummary {
  id: string;
  tenantId: string;
  name: string;
  description: string | null;
  activeVersion: number | null;
  createdAt: string;
  updatedAt: string;
}

export interface WorkflowVersionItem {
  id: string;
  version: number;
  status: "DRAFT" | "TESTING" | "ACTIVE" | "INACTIVE" | "ARCHIVED";
  definition: string;
  publishedAt: string | null;
  createdAt: string;
}

export interface WorkflowDetail {
  id: string;
  tenantId: string;
  name: string;
  description: string | null;
  activeVersion: number | null;
  versions: WorkflowVersionItem[];
}

// 3단계 위저드 폼 상태 모델
export interface WizardFormState {
  workflowName: string;
  description: string;
  // Step 1: Trigger
  triggerType: "MINECRAFT_LEVEL_UP" | "MINECRAFT_ADVANCEMENT_DONE" | "DISCORD_BUTTON_CLICK" | "DISCORD_SLASH_COMMAND";
  triggerParams: {
    customId?: string;
    advancementKey?: string;
  };
  // Step 2: Condition
  enableCondition: boolean;
  conditionField: string;
  conditionOperator: "GREATER_OR_EQUAL" | "EQUALS" | "CONTAINS";
  conditionValue: string;
  // Step 3: Action
  actionType: "DISCORD_SEND_MESSAGE" | "DISCORD_ADD_ROLE" | "MINECRAFT_DISPATCH_COMMAND" | "ATTENDANCE_RESERVATION";
  actionParams: {
    channelId?: string;
    message?: string;
    roleId?: string;
    command?: string;
    totalLimit?: number;
  };
}

export interface TenantConfig {
  tenantId: string;
  name: string;
  discordGuildId: string;
  policyMode: "DISABLED" | "MONITOR" | "ENFORCE";
  authChannelId?: string;
}

export interface InstanceItem {
  id: string;
  name: string;
  instanceType: "PROXY" | "BACKEND";
  status: "ONLINE" | "OFFLINE" | "STALE";
  lastHeartbeatAt: string | null;
}

export interface TenantMemberItem {
  tenantId: string;
  guildName: string;
  role: "OWNER" | "ADMIN";
}

export interface UserProfile {
  discordUserId: string;
  username: string;
  avatar: string | null;
  isPlatformSuperAdmin: boolean;
  tenants: TenantMemberItem[];
}

export interface AuditLogItem {
  id: string;
  tenantId: string;
  correlationId: string;
  actorType: string;
  actorId: string;
  action: string;
  targetType: string | null;
  targetId: string | null;
  status: string;
  details: string;
  ipAddress: string | null;
  createdAt: string;
}

export interface AuditLogPage {
  items: AuditLogItem[];
  nextCursor: string | null;
}

