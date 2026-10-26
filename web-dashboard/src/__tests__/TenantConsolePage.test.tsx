import { describe, it, expect, vi, afterEach } from "vitest";
import React from "react";
import { render, screen, waitFor } from "@testing-library/react";
import TenantConsolePage from "../app/app/page";
import * as api from "@/lib/api";

describe("TenantConsolePage", () => {
  afterEach(() => {
    vi.restoreAllMocks();
  });

  it("미인증 사용자에게 Discord 로그인 안내 화면과 링크를 렌더링해야 한다", async () => {
    vi.spyOn(api, "fetchUserProfile").mockResolvedValue(null);

    render(<TenantConsolePage />);

    await waitFor(() => {
      expect(screen.getByText("Ru-Beacon 관리자 콘솔")).toBeDefined();
      expect(screen.getByText("Discord로 로그인")).toBeDefined();
    });
  });
});
