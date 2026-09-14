"use client";

import React, { useState, useEffect } from "react";
import Link from "next/link";
import { InteractiveSimulator } from "@/components/landing/InteractiveSimulator";
import { RotatingHeadline } from "@/components/landing/RotatingHeadline";
import { ThemeToggle } from "@/components/ui/ThemeToggle";
import { Button } from "@/components/ui/button";
import { Badge } from "@/components/ui/badge";
import { fetchUserProfile } from "@/lib/api";
import { UserProfile } from "@/types/workflow";
import {
  Download,
  ArrowRight,
  ShieldCheck,
  Zap,
  Layers,
  Copy,
  Check,
  Server,
  Terminal,
  ExternalLink,
} from "lucide-react";

export default function LandingPage() {
  const [user, setUser] = useState<UserProfile | null>(null);
  const [copied, setCopied] = useState(false);

  useEffect(() => {
    fetchUserProfile().then((profile) => {
      if (profile && profile.tenants.length > 0) {
        setUser(profile);
      }
    });
  }, []);

  const tenantId = user?.tenants[0]?.tenantId || "<YOUR_TENANT_ID>";

  const configYaml = `# Ru-Beacon Plugin Configuration
# https://github.com/mmmphyun/ru-beacon

server:
  # 백엔드 WebSocket Gateway 주소
  gateway_url: "wss://api.ru-beacon.internal/ws/minecraft"
  
tenant:
  # 소속 서버(테넌트) 고유 식별자
  id: "${tenantId}"
  
auth:
  # 콘솔(/app)의 [개요 & 온보딩] 탭에서 발급받은 인스턴스 전용 토큰
  instance_token: "<YOUR_INSTANCE_TOKEN>"

# LMAX Disruptor 기반 인메모리 링버퍼 크기 (이벤트 버스트 대비)
ring_buffer_size: 4096
`;

  const handleCopyYaml = () => {
    navigator.clipboard.writeText(configYaml);
    setCopied(true);
    setTimeout(() => setCopied(false), 2000);
  };

  return (
    <div className="min-h-screen flex flex-col bg-background text-foreground selection:bg-primary selection:text-primary-foreground">
      {/* 최상단 네비게이션 헤더 */}
      <header className="border-b border-border/40 bg-background/80 backdrop-blur sticky top-0 z-50">
        <div className="max-w-6xl mx-auto px-4 h-14 flex items-center justify-between">
          <div className="flex items-center gap-3">
            <div className="w-7 h-7 rounded bg-primary flex items-center justify-center text-primary-foreground font-bold text-sm tracking-tighter">
              R
            </div>
            <div className="font-semibold text-sm tracking-tight flex items-center gap-2">
              Ru-Beacon
              <span className="text-[10px] font-mono px-1.5 py-0.2 rounded bg-muted text-muted-foreground border border-border/40">
                v1.0.0
              </span>
            </div>
          </div>

          <div className="flex items-center gap-2">
            <Link href="#setup" className="text-xs text-muted-foreground hover:text-foreground px-3 py-1.5 transition-colors">
              3분 셋업
            </Link>
            <Link href="#architecture" className="text-xs text-muted-foreground hover:text-foreground px-3 py-1.5 transition-colors">
              아키텍처
            </Link>
            <ThemeToggle />
            <Link href="/app">
              <Button size="sm" className="h-8 text-xs bg-primary hover:bg-primary/90 text-primary-foreground font-medium gap-1">
                {user ? "내 대시보드로 이동" : "콘솔 시작하기"}
                <ArrowRight className="w-3.5 h-3.5" />
              </Button>
            </Link>
          </div>
        </div>
      </header>

      {/* Hero Section */}
      <section className="py-16 md:py-24 px-4 border-b border-border/30 relative overflow-hidden">
        <div className="max-w-4xl mx-auto text-center space-y-5">
          <div className="inline-flex items-center gap-2 px-2.5 py-1 rounded border border-border/60 bg-card/60 text-xs text-muted-foreground font-mono">
            <span className="w-1.5 h-1.5 rounded-full bg-primary animate-pulse"></span>
            마인크래프트 & 디스코드 실시간 양방향 자동화 OS
          </div>

          <RotatingHeadline />

          <p className="text-sm md:text-base text-muted-foreground max-w-2xl mx-auto leading-relaxed">
            복잡한 봇 호스팅이나 서버 틱(TPS) 급락 없이, 디스코드 활동과 인게임 플레이를
            초저지연 링버퍼 파이프라인으로 완전 동기화합니다.
          </p>

          <div className="pt-3 flex flex-wrap items-center justify-center gap-3">
            <Link href="/app">
              <Button size="lg" className="h-10 px-5 text-xs bg-primary hover:bg-primary/90 text-primary-foreground font-medium gap-2">
                무료로 콘솔 시작하기
                <ArrowRight className="w-4 h-4" />
              </Button>
            </Link>
            <a href="/api/download/plugin" download>
              <Button variant="outline" size="lg" className="h-10 px-5 text-xs border-border/60 hover:bg-muted/40 gap-2">
                <Download className="w-4 h-4 text-muted-foreground" />
                Paper 플러그인 JAR 다운로드
              </Button>
            </a>
          </div>
        </div>

        {/* 인터랙티브 시뮬레이터 임베드 */}
        <div className="mt-12 px-2">
          <InteractiveSimulator />
        </div>
      </section>

      {/* 아키텍처 & 벤치마크 비교 섹션 */}
      <section id="architecture" className="py-16 px-4 border-b border-border/30 bg-muted/10">
        <div className="max-w-5xl mx-auto space-y-8">
          <div className="text-center space-y-2">
            <h2 className="text-xl md:text-2xl font-bold tracking-tight">
              왜 기존 연동 솔루션은 틱 렉을 유발할까요?
            </h2>
            <p className="text-xs text-muted-foreground max-w-xl mx-auto">
              메인 루프를 블로킹하는 기존 웹훅 방식과 Ru-Beacon의 2-Tier Admission 구조를 비교합니다.
            </p>
          </div>

          <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
            {/* 기존 방식 카드 */}
            <div className="p-5 rounded border border-border/40 bg-card/40 space-y-4">
              <div className="flex items-center justify-between">
                <span className="font-semibold text-sm text-foreground/80">기존 플러그인 연동 방식</span>
                <span className="text-[10px] font-mono px-2 py-0.5 rounded bg-destructive/10 text-destructive border border-destructive/20">
                  TPS 저하 위험
                </span>
              </div>
              <ul className="space-y-2.5 text-xs text-muted-foreground">
                <li className="flex items-start gap-2">
                  <span className="text-destructive font-mono">✕</span>
                  <span><strong>동기식 HTTP/웹훅 블로킹:</strong> Discord API 응답 지연 시 서버 틱 스파이크(TPS 20 → 14) 발생.</span>
                </li>
                <li className="flex items-start gap-2">
                  <span className="text-destructive font-mono">✕</span>
                  <span><strong>분산되지 않은 봇 호스팅:</strong> 서버장이 직접 봇 토큰 발급, 포트포워딩, 봇 프로세스 유지보수 부담.</span>
                </li>
                <li className="flex items-start gap-2">
                  <span className="text-destructive font-mono">✕</span>
                  <span><strong>설정 변경 시 재부팅 강제:</strong> 규칙을 고칠 때마다 서버 리로드로 플레이어 연결 불안정.</span>
                </li>
              </ul>
            </div>

            {/* Ru-Beacon 카드 */}
            <div className="p-5 rounded border border-primary/30 bg-card/80 space-y-4 relative">
              <div className="flex items-center justify-between">
                <span className="font-semibold text-sm text-primary flex items-center gap-1.5">
                  <Zap className="w-4 h-4 text-primary" /> Ru-Beacon 2-Tier Admission
                </span>
                <span className="text-[10px] font-mono px-2 py-0.5 rounded bg-primary/10 text-primary border border-primary/20">
                  Zero-allocation
                </span>
              </div>
              <ul className="space-y-2.5 text-xs text-foreground/90">
                <li className="flex items-start gap-2">
                  <span className="text-emerald-500 font-mono">✓</span>
                  <span><strong>동접 100명 몰려도 렉 0%:</strong> 메인 스레드를 멈추지 않는 비동기 처리로 인게임 TPS 20.0을 완벽히 방어합니다.</span>
                </li>
                <li className="flex items-start gap-2">
                  <span className="text-emerald-500 font-mono">✓</span>
                  <span><strong>귀찮은 봇 호스팅/포트포워딩 0:</strong> 직접 봇을 띄울 필요 없이, 발급받은 토큰 1줄만 넣으면 3분 만에 즉시 연결됩니다.</span>
                </li>
                <li className="flex items-start gap-2">
                  <span className="text-emerald-500 font-mono">✓</span>
                  <span><strong>서버 재부팅 없는 무중단 운영:</strong> 접속 중인 플레이어를 튕기게 하지 않고, 웹에서 클릭 한 번으로 룰 배포 및 1초 롤백이 가능합니다.</span>
                </li>
              </ul>
            </div>
          </div>

          {/* 벤치마크 주석 (신뢰성 고지) */}
          <div className="p-3 rounded bg-muted/30 border border-border/30 text-center">
            <p className="text-[11px] text-muted-foreground font-mono">
              * 벤치마크 측정 기준: Paper 1.20.4 환경, 가상 동시 접속자 100명 인메모리 링버퍼 인하우스 로컬 측정치 기준. 실제 수치는 서버 하드웨어 및 네트워크 상태에 따라 차이가 있을 수 있습니다.
            </p>
          </div>
        </div>
      </section>

      {/* 3분 셋업 가이드 섹션 */}
      <section id="setup" className="py-16 px-4">
        <div className="max-w-4xl mx-auto space-y-6">
          <div className="text-center space-y-2">
            <h2 className="text-xl md:text-2xl font-bold tracking-tight">
              3분 만에 시작하는 플러그인 셋업
            </h2>
            <p className="text-xs text-muted-foreground">
              {user ? (
                <span className="text-primary font-medium">
                  현재 로그인된 테넌트({user.tenants[0]?.guildName}) ID가 주입되었습니다.
                </span>
              ) : (
                "JAR 파일을 다운로드하고 config.yml에 토큰만 입력하면 끝납니다."
              )}
            </p>
          </div>

          <div className="rounded border border-border/60 bg-card/90 backdrop-blur overflow-hidden">
            <div className="flex items-center justify-between px-4 py-2.5 bg-muted/40 border-b border-border/40 text-xs">
              <div className="flex items-center gap-2 text-muted-foreground font-mono text-[11px]">
                <Server className="w-3.5 h-3.5 text-primary" />
                plugins/RuBeacon/config.yml
              </div>
              <Button
                variant="outline"
                size="sm"
                onClick={handleCopyYaml}
                className="h-7 text-xs gap-1 px-2.5"
              >
                {copied ? <Check className="w-3.5 h-3.5 text-emerald-500" /> : <Copy className="w-3.5 h-3.5" />}
                {copied ? "복사됨!" : "YAML 복사"}
              </Button>
            </div>

            <div className="p-4 bg-black/80 font-mono text-xs text-slate-200 overflow-x-auto leading-relaxed">
              <pre>{configYaml}</pre>
            </div>

            <div className="p-4 bg-muted/20 border-t border-border/40 flex flex-col sm:flex-row sm:items-center justify-between gap-3 text-xs">
              <div className="text-muted-foreground">
                <span className="font-semibold text-foreground">Next Step:</span> 콘솔에 접속하여 인스턴스 전용 토큰을 발급받으세요.
              </div>
              <Link href="/app">
                <Button size="sm" className="h-8 text-xs bg-primary hover:bg-primary/90 text-primary-foreground">
                  인스턴스 토큰 발급하러 가기
                </Button>
              </Link>
            </div>
          </div>
        </div>
      </section>

      {/* 푸터 */}
      <footer className="mt-auto border-t border-border/40 bg-card py-6 text-center text-xs text-muted-foreground">
        Ru-Beacon Community Platform &copy; 2026. Built with Next.js 14, Tailwind & Kotlin Microservices.
      </footer>
    </div>
  );
}

