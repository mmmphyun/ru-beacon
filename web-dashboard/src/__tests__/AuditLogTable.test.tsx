import React from "react";
import { render, screen, waitFor } from "@testing-library/react";
import { describe, it, expect, vi } from "vitest";
import { AuditLogTable } from "@/components/audit/AuditLogTable";

vi.mock("@/lib/api", () => ({
  fetchAuditLogs: vi.fn().mockResolvedValue({
    items: [
      {
        id: "audit_test_01",
        tenantId: "tenant_default",
        correlationId: "corr_01",
        actorType: "DISCORD_USER",
        actorId: "user_123",
        action: "WORKFLOW_DEPLOYED",
        targetType: "WORKFLOW",
        targetId: "wf_01",
        status: "SUCCESS",
        details: "버전 1 배포 완료",
        ipAddress: "127.0.***.***",
        createdAt: "2026-09-14T09:00:00Z",
      },
    ],
    nextCursor: "cursor_next_token",
  }),
}));

describe("AuditLogTable Component", () => {
  it("감사 로그 항목을 올바르게 렌더링하고 더보기 버튼을 노출해야 한다", async () => {
    render(<AuditLogTable tenantId="tenant_default" />);

    await waitFor(() => {
      expect(screen.getByText("WORKFLOW_DEPLOYED")).toBeDefined();
      expect(screen.getByText("버전 1 배포 완료")).toBeDefined();
      expect(screen.getByText("이전 로그 더 불러오기")).toBeDefined();
    });
  });
});
