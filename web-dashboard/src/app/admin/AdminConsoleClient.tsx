"use client";

import React, { useState, useEffect } from "react";
import Link from "next/link";
import { AuditLogTable } from "@/components/audit/AuditLogTable";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Badge } from "@/components/ui/badge";
import { ThemeToggle } from "@/components/ui/ThemeToggle";
import { Card, CardHeader, CardTitle, CardDescription, CardContent } from "@/components/ui/card";
import {
  ShieldAlert,
  KeyRound,
  FileText,
  Activity,
  Lock,
  Unlock,
  CheckCircle2,
  Terminal,
  ExternalLink,
} from "lucide-react";

export function AdminConsoleClient() {
  const [activeTab, setActiveTab] = useState<"audit" | "loki">("audit");
  const [adminSecret, setAdminSecret] = useState<string>("");
  const [inputSecret, setInputSecret] = useState<string>("");
  const [isUnlocked, setIsUnlocked] = useState(false);
  const [errorMsg, setErrorMsg] = useState("");

  useEffect(() => {
    const saved = sessionStorage.getItem("ru_beacon_admin_secret");
    if (saved) {
      setAdminSecret(saved);
      setIsUnlocked(true);
    }
  }, []);

  const handleUnlock = (e: React.FormEvent) => {
    e.preventDefault();
    if (!inputSecret.trim()) {
      setErrorMsg("마스터 시크릿을 입력해주세요.");
      return;
    }

    sessionStorage.setItem("ru_beacon_admin_secret", inputSecret.trim());
    setAdminSecret(inputSecret.trim());
    setIsUnlocked(true);
    setErrorMsg("");
  };

  const handleLock = () => {
    sessionStorage.removeItem("ru_beacon_admin_secret");
    setAdminSecret("");
    setIsUnlocked(false);
  };

  return (
    <div className="min-h-screen flex flex-col bg-background text-foreground selection:bg-primary selection:text-primary-foreground">
      {/* 운영자 헤더 */}
      <header className="border-b border-border/40 bg-card/90 backdrop-blur sticky top-0 z-50">
        <div className="max-w-7xl mx-auto px-4 h-14 flex items-center justify-between">
          <div className="flex items-center gap-3">
            <Link href="/" className="flex items-center gap-2 hover:opacity-80 transition-opacity">
              <div className="w-7 h-7 rounded bg-destructive flex items-center justify-center text-destructive-foreground font-bold text-xs tracking-tighter">
                A
              </div>
              <span className="font-semibold text-sm tracking-tight text-foreground">Ru-Beacon Control Center</span>
            </Link>

            <span className="text-border">/</span>

            <Badge variant="outline" className="border-destructive/40 bg-destructive/10 text-destructive text-[10px] font-mono px-2">
              PLATFORM SUPER ADMIN
            </Badge>
          </div>

          <div className="flex items-center gap-3">
            {isUnlocked ? (
              <Button
                variant="outline"
                size="sm"
                onClick={handleLock}
                className="h-7 text-xs border-border/50 text-muted-foreground hover:text-foreground gap-1.5"
              >
                <Unlock className="w-3 h-3 text-emerald-500" />
                2차 잠금 해제됨 (잠그기)
              </Button>
            ) : (
              <span className="text-[11px] font-mono text-amber-500 flex items-center gap-1">
                <Lock className="w-3 h-3" /> 2차 인증 필요
              </span>
            )}

            <ThemeToggle />

            <Link href="/app">
              <Button variant="outline" size="sm" className="h-7 text-xs gap-1 border-border/60">
                고객 콘솔로 전환
              </Button>
            </Link>
          </div>
        </div>
      </header>

      {/* 2대 관제 탭 */}
      <div className="border-b border-border/40 bg-muted/20">
        <div className="max-w-7xl mx-auto px-4 flex space-x-6">
          <button
            onClick={() => setActiveTab("audit")}
            className={`py-3 px-1 border-b-2 font-medium text-xs flex items-center gap-2 transition-colors ${
              activeTab === "audit"
                ? "border-primary text-primary font-semibold"
                : "border-transparent text-muted-foreground hover:text-foreground"
            }`}
          >
            <ShieldAlert className="w-3.5 h-3.5" /> 전체 테넌트 감사 로그 (Audit Trail)
          </button>
          <button
            onClick={() => setActiveTab("loki")}
            className={`py-3 px-1 border-b-2 font-medium text-xs flex items-center gap-2 transition-colors ${
              activeTab === "loki"
                ? "border-primary text-primary font-semibold"
                : "border-transparent text-muted-foreground hover:text-foreground"
            }`}
          >
            <Activity className="w-3.5 h-3.5" /> Loki 시스템 로그 (System Observability)
          </button>
        </div>
      </div>

      <main className="flex-1 max-w-7xl w-full mx-auto px-4 py-6">
        {/* 2차 인증 잠금 상태일 때 모달/배너 */}
        {!isUnlocked ? (
          <div className="max-w-md mx-auto my-12">
            <Card className="border border-border/60 bg-card/80 backdrop-blur">
              <CardHeader className="text-center pb-3">
                <div className="mx-auto w-10 h-10 rounded-full bg-destructive/10 border border-destructive/30 flex items-center justify-center text-destructive mb-2">
                  <KeyRound className="w-5 h-5" />
                </div>
                <CardTitle className="text-base font-semibold">2차 마스터 시크릿 인증</CardTitle>
                <CardDescription className="text-xs text-muted-foreground">
                  Ru-Beacon 개발 디스코드 보안 채널 또는 환경변수(`PLATFORM_ADMIN_SECRET`)에 설정된 2차 인증키를 입력하세요.
                </CardDescription>
              </CardHeader>
              <CardContent>
                <form onSubmit={handleUnlock} className="space-y-3">
                  <div>
                    <Input
                      type="password"
                      placeholder="X-Platform-Admin-Secret 입력..."
                      value={inputSecret}
                      onChange={(e) => setInputSecret(e.target.value)}
                      className="text-xs font-mono h-9 bg-background"
                      autoFocus
                    />
                    {errorMsg && <p className="text-[11px] text-destructive mt-1">{errorMsg}</p>}
                  </div>
                  <Button type="submit" className="w-full text-xs h-9 bg-primary hover:bg-primary/90 text-primary-foreground font-medium">
                    2차 인증 및 잠금 해제
                  </Button>
                </form>
              </CardContent>
            </Card>
          </div>
        ) : (
          <div>
            {/* 탭 1: 전체 테넌트 감사 로그 (전수 조회) */}
            {activeTab === "audit" && (
              <AuditLogTable
                adminSecret={adminSecret}
                title="플랫폼 전수 비즈니스 감사 로그"
                description="모든 테넌트의 워크플로우 배포, 토큰 발급, 이상 징후를 추적하는 플랫폼 전역 감사 스트림입니다."
              />
            )}

            {/* 탭 2: Loki 시스템 로그 (플레이스홀더) */}
            {activeTab === "loki" && (
              <Card className="border border-border/60 bg-card/80 backdrop-blur p-8 text-center space-y-3">
                <div className="mx-auto w-10 h-10 rounded bg-muted flex items-center justify-center text-muted-foreground">
                  <Terminal className="w-5 h-5" />
                </div>
                <h3 className="font-semibold text-sm text-foreground">Loki 시스템 로그 관제 파이프라인</h3>
                <p className="text-xs text-muted-foreground max-w-md mx-auto">
                  백엔드 Loki 조회 전용 엔드포인트 구축 대기 상태입니다. 현재 라우트와 탭 구조가 격리되어 있으며,
                  이후 LogQL 스트림 및 타임스탬프 필터가 연동될 예정입니다.
                </p>
                <Badge variant="outline" className="text-[10px] font-mono text-muted-foreground">
                  STATUS: PIPELINE_STANDBY
                </Badge>
              </Card>
            )}
          </div>
        )}
      </main>
    </div>
  );
}
