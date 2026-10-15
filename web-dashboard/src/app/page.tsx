"use client";

import React, { useState, useEffect } from "react";
import Link from "next/link";
import { InteractiveSimulator } from "@/components/landing/InteractiveSimulator";
import { ThemeToggle } from "@/components/ui/ThemeToggle";
import { Button } from "@/components/ui/button";
import { Badge } from "@/components/ui/badge";
import { fetchUserProfile } from "@/lib/api";
import { UserProfile } from "@/types/workflow";
import {
  ArrowRight,
  Layers,
  Activity,
  ShieldCheck,
  Bot,
  Key,
  Radio,
} from "lucide-react";

export default function LandingPage() {
  const [user, setUser] = useState<UserProfile | null>(null);

  useEffect(() => {
    fetchUserProfile().then((profile) => {
      if (profile && profile.tenants.length > 0) {
        setUser(profile);
      }
    });
  }, []);

  return (
    <div className="min-h-screen flex flex-col bg-background text-foreground selection:bg-primary selection:text-primary-foreground">
      {/* 1.1 최상단 네비게이션 헤더 */}
      <header className="border-b border-border bg-background/90 backdrop-blur sticky top-0 z-50">
        <div className="max-w-6xl mx-auto px-4 h-14 flex items-center justify-between">
          <div className="flex items-center gap-3">
            <div className="w-7 h-7 rounded bg-primary flex items-center justify-center text-primary-foreground font-bold text-sm tracking-tighter">
              R
            </div>
            <div className="font-semibold text-sm tracking-tight flex items-center gap-2">
              RuBeacon
              <Badge variant="outline" className="text-[10px] font-mono border-border text-muted-foreground">
                v1.0.0
              </Badge>
            </div>
          </div>

          <div className="flex items-center gap-2">
            <Link
              href="#setup"
              className="text-xs text-muted-foreground hover:text-foreground px-3 py-1.5 transition-colors"
            >
              3분 연동
            </Link>
            <Link
              href="#architecture"
              className="text-xs text-muted-foreground hover:text-foreground px-3 py-1.5 transition-colors"
            >
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

      {/* 1.2 Hero 섹션 */}
      <section className="py-16 md:py-24 px-4 border-b border-border relative overflow-hidden">
        <div className="max-w-4xl mx-auto text-center space-y-5">
          <div className="inline-flex items-center gap-2 px-3 py-1 rounded border border-border bg-card text-xs text-muted-foreground font-mono">
            <span className="w-1.5 h-1.5 rounded-full bg-primary" />
            초저지연 마인크래프트-디스코드 브릿지
          </div>

          <h1 className="text-2xl sm:text-3xl md:text-5xl font-bold tracking-tight text-foreground leading-tight">
            서버 TPS 저하 없는 마인크래프트-디스코드 실시간 연동
          </h1>

          <p className="text-sm md:text-base text-muted-foreground max-w-2xl mx-auto leading-relaxed">
            서버에 이벤트가 몰려도 렉이 걸리지 않습니다. 게임 속 사건을 디스코드 알림과 역할에 곧바로 반영합니다.
          </p>

          <div className="pt-3 flex flex-wrap items-center justify-center gap-3">
            <Link href="/app">
              <Button size="lg" className="h-10 px-5 text-xs bg-primary hover:bg-primary/90 text-primary-foreground font-semibold gap-2">
                3분 만에 시작하기
                <ArrowRight className="w-4 h-4" />
              </Button>
            </Link>
            <Link href="#architecture">
              <Button variant="outline" size="lg" className="h-10 px-5 text-xs border-border hover:bg-card gap-2">
                아키텍처 명세 보기
              </Button>
            </Link>
          </div>
        </div>

        {/* 1.3 인터랙티브 제품 텔레메트리 쇼케이스 */}
        <div className="mt-12 px-2">
          <InteractiveSimulator />
        </div>
      </section>

      {/* 1.4 핵심 인프라 3대 명세 카드 */}
      <section id="architecture" className="py-16 px-4 border-b border-border bg-surface">
        <div className="max-w-5xl mx-auto space-y-8">
          <div className="text-center space-y-2">
            <h2 className="text-xl md:text-2xl font-bold tracking-tight text-foreground">
              초저지연 비동기 인프라 명세
            </h2>
            <p className="text-xs text-muted-foreground max-w-xl mx-auto">
              메인 루프를 블로킹하는 웹훅 대신 단일 계층 WebSocket과 LMAX 링버퍼로 20.0 TPS를 사수합니다.
            </p>
          </div>

          <div className="grid grid-cols-1 md:grid-cols-3 gap-4">
            {/* 카드 1 */}
            <div className="p-5 rounded border border-border bg-card space-y-3">
              <div className="w-8 h-8 rounded border border-border bg-background flex items-center justify-center">
                <Layers className="w-4 h-4 text-primary" />
              </div>
              <h3 className="font-semibold text-sm text-foreground">
                WSS 단일 계층 게이트웨이
              </h3>
              <p className="text-xs text-muted-foreground leading-relaxed">
                중계 서버 없이 백엔드와 플러그인이 WebSocket으로 직접 통신하여 네트워크 홉을 최소화합니다.
              </p>
            </div>

            {/* 카드 2 */}
            <div className="p-5 rounded border border-border bg-card space-y-3">
              <div className="w-8 h-8 rounded border border-border bg-background flex items-center justify-center">
                <Activity className="w-4 h-4 text-primary" />
              </div>
              <h3 className="font-semibold text-sm text-foreground">
                LMAX Disruptor 인메모리 링버퍼
              </h3>
              <p className="text-xs text-muted-foreground leading-relaxed">
                초당 수천 건의 블록 파괴와 전투 이벤트가 폭증해도 메인 스레드 20 TPS를 방어합니다.
              </p>
            </div>

            {/* 카드 3 */}
            <div className="p-5 rounded border border-border bg-card space-y-3">
              <div className="w-8 h-8 rounded border border-border bg-background flex items-center justify-center">
                <ShieldCheck className="w-4 h-4 text-primary" />
              </div>
              <h3 className="font-semibold text-sm text-foreground">
                디스코드 권한 실시간 동기화
              </h3>
              <p className="text-xs text-muted-foreground leading-relaxed">
                별도의 웹 인증 절차 없이 인게임 플레이어와 디스코드 역할을 지연 없이 일치시킵니다.
              </p>
            </div>
          </div>
        </div>
      </section>

      {/* 1.5 빠른 3단계 연동 안내 (코드 블록 대체 요약) */}
      <section id="setup" className="py-16 px-4">
        <div className="max-w-4xl mx-auto space-y-8">
          <div className="text-center space-y-2">
            <h2 className="text-xl md:text-2xl font-bold tracking-tight text-foreground">
              빠른 3단계 연동 안내
            </h2>
            <p className="text-xs text-muted-foreground">
              복잡한 수동 설정 없이 3단계 온보딩으로 마인크래프트 서버와 디스코드를 연결합니다.
            </p>
          </div>

          <div className="grid grid-cols-1 md:grid-cols-3 gap-4">
            <div className="p-5 rounded border border-border bg-card space-y-3">
              <div className="flex items-center justify-between">
                <span className="font-mono text-xs font-semibold text-primary">STEP 01</span>
                <Bot className="w-4 h-4 text-muted-foreground" />
              </div>
              <h3 className="font-semibold text-sm text-foreground">디스코드 봇 초대</h3>
              <p className="text-xs text-muted-foreground leading-relaxed">
                내 디스코드 서버에 전용 봇을 추가하여 알림 채널과 연동 권한을 확보합니다.
              </p>
            </div>

            <div className="p-5 rounded border border-border bg-card space-y-3">
              <div className="flex items-center justify-between">
                <span className="font-mono text-xs font-semibold text-primary">STEP 02</span>
                <Key className="w-4 h-4 text-muted-foreground" />
              </div>
              <h3 className="font-semibold text-sm text-foreground">인스턴스 토큰 발급</h3>
              <p className="text-xs text-muted-foreground leading-relaxed">
                콘솔에서 발급한 고유 토큰을 플러그인 설정에 등록하여 보안 채널을 승인합니다.
              </p>
            </div>

            <div className="p-5 rounded border border-border bg-card space-y-3">
              <div className="flex items-center justify-between">
                <span className="font-mono text-xs font-semibold text-primary">STEP 03</span>
                <Radio className="w-4 h-4 text-muted-foreground" />
              </div>
              <h3 className="font-semibold text-sm text-foreground">실시간 하트비트 확인</h3>
              <p className="text-xs text-muted-foreground leading-relaxed">
                서버 부팅 즉시 웹소켓 핑 신호를 감지하여 관제탑을 온라인으로 활성화합니다.
              </p>
            </div>
          </div>

          <div className="text-center pt-2">
            <Link href="/app">
              <Button size="lg" className="h-10 px-6 text-xs bg-primary hover:bg-primary/90 text-primary-foreground font-semibold gap-2">
                지금 콘솔 시작하기
                <ArrowRight className="w-4 h-4" />
              </Button>
            </Link>
          </div>
        </div>
      </section>

      {/* 푸터 */}
      <footer className="mt-auto border-t border-border bg-card py-6 text-center text-xs text-muted-foreground">
        RuBeacon &copy; 2026. 초저지연 마인크래프트-디스코드 브릿지.
      </footer>
    </div>
  );
}
