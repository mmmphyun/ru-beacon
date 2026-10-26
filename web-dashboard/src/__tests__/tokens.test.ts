import { describe, it, expect } from "vitest";
import colors from "../../.design/tokens/colors.json";
import fs from "fs";
import path from "path";

describe("Ruby-Sand Design Tokens & SSOT Bridge", () => {
  it("루비-샌드 핵심 서피스 및 시그니처 컬러 토큰이 규격대로 정의되어야 한다", () => {
    expect(colors.background.primary).toBe("#141414");
    expect(colors.background.surface).toBe("#1c1c1c");
    expect(colors.border.default).toBe("#32302b");
    expect(colors.accent.primary).toBe("#d41153");
    expect(colors.text.primary).toBe("#f6f5f3");
    expect(colors.text.muted).toBe("#928e85");
  });

  it("시스템 관제 상태 시맨틱 컬러가 정의되어야 한다", () => {
    expect(colors.status.error).toBe("#f42e24");
    expect(colors.status.warning).toBe("#f59f0a");
    expect(colors.status.success).toBe("#19b366");
  });

  it("DESIGN.md에 3-Dial Taste 설정이 규격대로 정의되어야 한다", () => {
    const designMdPath = path.resolve(__dirname, "../../.design/DESIGN.md");
    const content = fs.readFileSync(designMdPath, "utf-8");
    expect(content).toMatch(/`DESIGN_VARIANCE`:\s*5/);
    expect(content).toMatch(/`VISUAL_DENSITY`:\s*5/);
    expect(content).toMatch(/`MOTION_INTENSITY`:\s*2/);
  });

  it("globals.css의 Line 1에서 design tokens.css를 임포트하고 LevelUp 클래스를 정의해야 한다", () => {
    const globalsCssPath = path.resolve(__dirname, "../app/globals.css");
    const content = fs.readFileSync(globalsCssPath, "utf-8");
    const firstLine = content.split("\n")[0].trim();
    expect(firstLine).toBe('@import "../../.design/tokens/tokens.css";');
    expect(content).toContain('font-family: "LevelUp"');
    expect(content).toContain(".headline");
    expect(content).toContain(".score");
    expect(content).toContain("--sand:");
    expect(content).toContain("--background: 35 12% 9%;");
  });
});
