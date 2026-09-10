"use client";

import React, { useState } from "react";
import { WorkflowWizard } from "@/components/workflow/WorkflowWizard";
import { WorkflowList } from "@/components/workflow/WorkflowList";
import { OnboardingView } from "@/components/onboarding/OnboardingView";
import { Radio, Layers, Settings, ShieldCheck, ExternalLink } from "lucide-react";

export default function DashboardPage() {
  const [activeTab, setActiveTab] = useState<"wizard" | "list" | "onboarding">("wizard");
  const [tenantId, setTenantId] = useState("tenant_default");
  const [refreshTrigger, setRefreshTrigger] = useState(0);

  const handleWorkflowSaved = () => {
    setRefreshTrigger((prev) => prev + 1);
  };

  return (
    <div className="min-h-screen flex flex-col">
      {/* 헤더 네비게이션 */}
      <header className="border-b border-slate-800 bg-slate-950/80 backdrop-blur sticky top-0 z-50">
        <div className="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8 h-16 flex items-center justify-between">
          <div className="flex items-center gap-3">
            <div className="w-8 h-8 rounded-lg bg-blue-600 flex items-center justify-center text-white font-bold text-lg shadow-lg shadow-blue-500/20">
              R
            </div>
            <div>
              <div className="font-bold text-white text-base tracking-tight flex items-center gap-2">
                Ru-Beacon <span className="text-xs text-blue-400 font-mono font-normal">v1.0.0</span>
              </div>
              <div className="text-xs text-slate-400">Minecraft & Discord Unified Automation</div>
            </div>
          </div>

          <div className="flex items-center gap-2">
            <div className="flex items-center gap-2 px-3 py-1.5 rounded-full bg-slate-900 border border-slate-800 text-xs">
              <span className="w-2 h-2 rounded-full bg-emerald-500 animate-pulse"></span>
              <span className="text-slate-300 font-mono">{tenantId}</span>
            </div>
          </div>
        </div>
      </header>

      {/* 탭 네비게이션 */}
      <div className="border-b border-slate-800 bg-slate-900/40">
        <div className="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8 flex space-x-8">
          <button
            onClick={() => setActiveTab("wizard")}
            className={`py-3.5 px-1 border-b-2 font-medium text-sm flex items-center gap-2 transition-colors ${
              activeTab === "wizard"
                ? "border-blue-500 text-blue-400"
                : "border-transparent text-slate-400 hover:text-slate-200"
            }`}
          >
            <Layers className="w-4 h-4" /> 카드형 위저드 워크플로우 빌더
          </button>
          <button
            onClick={() => setActiveTab("list")}
            className={`py-3.5 px-1 border-b-2 font-medium text-sm flex items-center gap-2 transition-colors ${
              activeTab === "list"
                ? "border-blue-500 text-blue-400"
                : "border-transparent text-slate-400 hover:text-slate-200"
            }`}
          >
            <Radio className="w-4 h-4" /> 워크플로우 목록 및 배포
          </button>
          <button
            onClick={() => setActiveTab("onboarding")}
            className={`py-3.5 px-1 border-b-2 font-medium text-sm flex items-center gap-2 transition-colors ${
              activeTab === "onboarding"
                ? "border-blue-500 text-blue-400"
                : "border-transparent text-slate-400 hover:text-slate-200"
            }`}
          >
            <Settings className="w-4 h-4" /> 온보딩 & Discord/인스턴스 설정
          </button>
        </div>
      </div>

      {/* 메인 컨텐츠 영역 */}
      <main className="flex-1 max-w-7xl w-full mx-auto px-4 sm:px-6 lg:px-8 py-8">
        {activeTab === "wizard" && (
          <WorkflowWizard tenantId={tenantId} onWorkflowSaved={handleWorkflowSaved} />
        )}
        {activeTab === "list" && (
          <WorkflowList tenantId={tenantId} refreshTrigger={refreshTrigger} />
        )}
        {activeTab === "onboarding" && (
          <OnboardingView tenantId={tenantId} />
        )}
      </main>

      {/* 푸터 */}
      <footer className="border-t border-slate-800/80 bg-slate-950 py-6 text-center text-xs text-slate-500">
        Ru-Beacon Dashboard &copy; 2026. Next.js 14 App Router & Shadcn UI.
      </footer>
    </div>
  );
}
