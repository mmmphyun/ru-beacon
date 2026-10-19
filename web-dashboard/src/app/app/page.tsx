"use client";

import React, { useState, useEffect } from "react";
import Link from "next/link";
import { WorkflowWizard } from "@/components/workflow/WorkflowWizard";
import { WorkflowList } from "@/components/workflow/WorkflowList";
import { OnboardingView } from "@/components/onboarding/OnboardingView";
import { AuditLogTable } from "@/components/audit/AuditLogTable";
import { fetchUserProfile, devMockLogin } from "@/lib/api";
import { UserProfile } from "@/types/workflow";
import { ThemeToggle } from "@/components/ui/ThemeToggle";
import { Button } from "@/components/ui/button";
import { Badge } from "@/components/ui/badge";
import { RuBeaconLogo } from "@/components/brand/RuBeaconLogo";
import {
  Layers,
  Settings,
  Shield,
  PlusCircle,
  ListFilter,
  UserCheck,
  RotateCw,
  LogOut,
  ShieldAlert,
} from "lucide-react";

export default function TenantConsolePage() {
  const [activeTab, setActiveTab] = useState<"onboarding" | "workflow" | "audit">("workflow");
  const [workflowSubView, setWorkflowSubView] = useState<"list" | "wizard">("list");
  const [user, setUser] = useState<UserProfile | null>(null);
  const [loadingUser, setLoadingUser] = useState(true);
  const [refreshTrigger, setRefreshTrigger] = useState(0);

  const loadSession = async () => {
    setLoadingUser(true);
    try {
      const profile = await fetchUserProfile();
      setUser(profile);
    } finally {
      setLoadingUser(false);
    }
  };

  useEffect(() => {
    loadSession();
  }, []);

  const handleDevRoleSwitch = async (role: "OWNER" | "ADMIN") => {
    await devMockLogin(
      role === "OWNER" ? "dev_owner_01" : "dev_admin_01",
      role === "OWNER" ? "GuildOwner" : "GuildAdmin",
      role
    );
    await loadSession();
  };

  const handleWorkflowSaved = () => {
    setRefreshTrigger((prev) => prev + 1);
    setWorkflowSubView("list");
  };

  const currentTenant = user?.tenants[0] || {
    tenantId: "tenant_default",
    guildName: "루비콘 기본 서버",
    role: "OWNER" as const,
  };

  const isOwner = currentTenant.role === "OWNER";

  return (
    <div className="min-h-screen flex flex-col bg-background text-foreground selection:bg-primary selection:text-primary-foreground">
      {/* 콘솔 상단 헤더 */}
      <header className="border-b border-border/40 bg-card/60 backdrop-blur sticky top-0 z-50">
        <div className="max-w-7xl mx-auto px-4 h-14 flex items-center justify-between">
          <div className="flex items-center gap-3">
            <Link href="/" className="flex items-center gap-2 hover:opacity-80 transition-opacity">
              <RuBeaconLogo size={22} />
              <span className="font-semibold text-sm tracking-tight text-foreground">Ru-Beacon</span>
            </Link>

            <span className="text-border">/</span>

            <div className="flex items-center gap-2">
              <span className="text-xs font-medium text-foreground">{currentTenant.guildName}</span>
              <Badge
                variant="outline"
                className={`text-[10px] font-mono px-1.5 py-0 ${
                  isOwner
                    ? "border-primary/40 bg-primary/10 text-primary font-semibold"
                    : "border-border text-muted-foreground"
                }`}
              >
                {currentTenant.role}
              </Badge>
            </div>
          </div>

          <div className="flex items-center gap-3">
            {/* 로컬 개발용 퀵 스위처 */}
            <div className="hidden sm:flex items-center gap-1 rounded border border-border/50 bg-background/60 p-0.5 text-xs">
              <span className="text-[10px] font-mono text-muted-foreground px-2">DevMock:</span>
              <button
                onClick={() => handleDevRoleSwitch("OWNER")}
                className={`px-2 py-0.5 rounded text-[11px] transition-colors ${
                  isOwner ? "bg-primary text-primary-foreground font-medium" : "text-muted-foreground hover:text-foreground"
                }`}
              >
                OWNER
              </button>
              <button
                onClick={() => handleDevRoleSwitch("ADMIN")}
                className={`px-2 py-0.5 rounded text-[11px] transition-colors ${
                  !isOwner ? "bg-primary text-primary-foreground font-medium" : "text-muted-foreground hover:text-foreground"
                }`}
              >
                ADMIN
              </button>
            </div>

            {/* 유저 배지 */}
            <div className="flex items-center gap-2 text-xs text-muted-foreground">
              <span className="font-mono text-[11px] text-foreground">{user?.username || "Guest"}</span>
            </div>

            {/* 슈퍼어드민인 경우 관제센터 바로가기 */}
            {user?.isPlatformSuperAdmin && (
              <Link href="/admin">
                <Button variant="outline" size="sm" className="h-7 text-[11px] text-primary border-primary/30 hover:bg-primary/10 gap-1">
                  <ShieldAlert className="w-3 h-3 text-primary" />
                  플랫폼 관제
                </Button>
              </Link>
            )}

            <ThemeToggle />
          </div>
        </div>
      </header>

      {/* 대시보드 3대 탭 네비게이션: 모바일 가로 스크롤 안전 처리 */}
      <div className="border-b border-border/40 bg-muted/20">
        <div className="max-w-7xl mx-auto px-4 flex space-x-6 overflow-x-auto whitespace-nowrap">
          <button
            onClick={() => setActiveTab("workflow")}
            className={`py-3 px-1 border-b-2 font-medium text-xs sm:text-sm flex items-center gap-2 transition-colors shrink-0 ${
              activeTab === "workflow"
                ? "border-primary text-primary font-semibold"
                : "border-transparent text-muted-foreground hover:text-foreground"
            }`}
          >
            <Layers className="w-3.5 h-3.5" /> 이벤트 연동 규칙
          </button>
          <button
            onClick={() => setActiveTab("onboarding")}
            className={`py-3 px-1 border-b-2 font-medium text-xs sm:text-sm flex items-center gap-2 transition-colors shrink-0 ${
              activeTab === "onboarding"
                ? "border-primary text-primary font-semibold"
                : "border-transparent text-muted-foreground hover:text-foreground"
            }`}
          >
            <Settings className="w-3.5 h-3.5" /> 서버 연결 설정
          </button>
          <button
            onClick={() => setActiveTab("audit")}
            className={`py-3 px-1 border-b-2 font-medium text-xs sm:text-sm flex items-center gap-2 transition-colors shrink-0 ${
              activeTab === "audit"
                ? "border-primary text-primary font-semibold"
                : "border-transparent text-muted-foreground hover:text-foreground"
            }`}
          >
            <Shield className="w-3.5 h-3.5" /> 활동 감사 기록
          </button>
        </div>
      </div>

      {/* 메인 컨텐츠 영역 */}
      <main className="flex-1 max-w-7xl w-full mx-auto px-4 py-6">
        {/* 탭 1: 워크플로우 자동화 (서브 세그먼트: 목록 <-> 위저드) */}
        {activeTab === "workflow" && (
          <div className="space-y-4">
            <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-3 pb-2 border-b border-border/30">
              <div className="inline-flex rounded border border-border/60 bg-muted/40 p-0.5 text-xs">
                <button
                  onClick={() => setWorkflowSubView("list")}
                  className={`px-3 py-1.5 rounded transition-all font-medium flex items-center gap-1.5 ${
                    workflowSubView === "list"
                      ? "bg-card text-foreground shadow-sm font-semibold"
                      : "text-muted-foreground hover:text-foreground"
                  }`}
                >
                  <ListFilter className="w-3.5 h-3.5" /> 연동 규칙 목록
                </button>
                <button
                  onClick={() => setWorkflowSubView("wizard")}
                  className={`px-3 py-1.5 rounded transition-all font-medium flex items-center gap-1.5 ${
                    workflowSubView === "wizard"
                      ? "bg-card text-foreground shadow-sm font-semibold"
                      : "text-muted-foreground hover:text-foreground"
                  }`}
                >
                  <PlusCircle className="w-3.5 h-3.5 text-primary" /> 새 규칙 만들기
                </button>
              </div>

              {workflowSubView === "list" && (
                <Button
                  size="sm"
                  onClick={() => setWorkflowSubView("wizard")}
                  className="h-8 text-xs bg-primary hover:bg-primary/90 text-primary-foreground font-medium gap-1 self-start sm:self-auto"
                >
                  <PlusCircle className="w-3.5 h-3.5" />
                  새 연동 규칙 만들기
                </Button>
              )}
            </div>

            {workflowSubView === "list" ? (
              <WorkflowList
                tenantId={currentTenant.tenantId}
                refreshTrigger={refreshTrigger}
                onCreateNew={() => setWorkflowSubView("wizard")}
              />
            ) : (
              <WorkflowWizard tenantId={currentTenant.tenantId} onWorkflowSaved={handleWorkflowSaved} />
            )}
          </div>
        )}

        {/* 탭 2: 개요 & 온보딩 (OWNER 가드) */}
        {activeTab === "onboarding" && (
          <div className="space-y-4">
            {!isOwner && (
              <div className="p-3 rounded border border-amber-500/30 bg-amber-500/10 text-amber-300 text-xs flex items-center gap-2">
                <ShieldAlert className="w-4 h-4 text-amber-400 shrink-0" />
                <span>
                  현재 관리자(ADMIN) 권한으로 확인 중입니다. 서버 연결 키 발급 및 기본 설정 변경은 서버 소유자(OWNER)만 가능합니다.
                </span>
              </div>
            )}
            <OnboardingView
              tenantId={currentTenant.tenantId}
              isOwner={isOwner}
              onComplete={() => setActiveTab("workflow")}
            />
          </div>
        )}

        {/* 탭 3: 테넌트 비즈니스 감사 로그 */}
        {activeTab === "audit" && (
          <AuditLogTable
            tenantId={currentTenant.tenantId}
            title={`${currentTenant.guildName} 활동 감사 이력`}
            description="서버에서 수행된 규칙 적용, 연결 키 발급, 인게임 명령어 실행 등 모든 활동 이력입니다."
          />
        )}
      </main>
    </div>
  );
}
