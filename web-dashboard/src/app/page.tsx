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
  Check,
  X,
  Server,
  Zap,
  Cpu,
  Globe,
  Sparkles,
  ChevronRight,
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
    <div className="min-h-screen flex flex-col bg-background text-foreground selection:bg-primary selection:text-primary-foreground relative overflow-hidden">
      {/* 상단 앰비언트 글로우 배경 (Parallel 레퍼런스 스타일) */}
      <div className="absolute top-0 left-1/2 -translate-x-1/2 w-[1200px] h-[600px] bg-gradient-to-b from-primary/15 via-primary/5 to-transparent blur-3xl pointer-events-none -z-10" />

      {/* 1. 최상단 네비게이션 헤더 */}
      <header className="border-b border-border bg-background/80 backdrop-blur-md sticky top-0 z-50">
        <div className="max-w-6xl mx-auto px-4 h-14 flex items-center justify-between">
          <div className="flex items-center gap-3">
            <div className="w-7 h-7 rounded-lg bg-primary flex items-center justify-center text-primary-foreground font-bold text-sm tracking-tighter shadow-sm">
              R
            </div>
            <div className="font-semibold text-sm tracking-tight flex items-center gap-2">
              RuBeacon
              <Badge variant="outline" className="text-[10px] font-mono border-border text-muted-foreground py-0">
                v1.0.0
              </Badge>
            </div>
          </div>

          <div className="flex items-center gap-2 sm:gap-3">
            <Link
              href="#architecture"
              className="text-xs text-muted-foreground hover:text-foreground px-2.5 py-1.5 transition-colors hidden sm:inline-block"
            >
              아키텍처
            </Link>
            <Link
              href="#comparison"
              className="text-xs text-muted-foreground hover:text-foreground px-2.5 py-1.5 transition-colors hidden sm:inline-block"
            >
              성능 비교
            </Link>
            <Link
              href="#setup"
              className="text-xs text-muted-foreground hover:text-foreground px-2.5 py-1.5 transition-colors hidden sm:inline-block"
            >
              3분 연동
            </Link>
            <ThemeToggle />
            <Link href="/app">
              <Button size="sm" className="h-8 text-xs bg-primary hover:bg-primary/90 text-primary-foreground font-medium gap-1 shadow-sm">
                {user ? "내 대시보드로 이동" : "콘솔 시작하기"}
                <ArrowRight className="w-3.5 h-3.5" />
              </Button>
            </Link>
          </div>
        </div>
      </header>

      {/* 2. Hero 섹션 (Parallel 스타일 대형 볼드 타이포 + 브라우저 윈도우 목업) */}
      <section className="pt-16 pb-20 md:pt-24 md:pb-28 px-4 relative">
        <div className="max-w-4xl mx-auto text-center space-y-6">
          <div className="inline-flex items-center gap-2 px-3.5 py-1.5 rounded-full border border-primary/30 bg-primary/5 text-xs text-primary font-mono shadow-sm">
            <span className="w-2 h-2 rounded-full bg-primary animate-pulse" />
            초저지연 마인크래프트-디스코드 브릿지
          </div>

          <h1 className="text-3xl sm:text-4xl md:text-6xl font-bold tracking-tight text-foreground leading-[1.15]">
            서버 TPS 저하 없는 마인크래프트-디스코드 실시간 연동
          </h1>

          <p className="text-sm md:text-base text-muted-foreground max-w-2xl mx-auto leading-relaxed">
            서버에 이벤트가 몰려도 렉이 걸리지 않습니다. 게임 속 사건을 디스코드 알림과 역할에 곧바로 반영합니다.
          </p>

          <div className="pt-2 flex flex-wrap items-center justify-center gap-3">
            <Link href="/app">
              <Button size="lg" className="h-11 px-6 text-xs md:text-sm bg-primary hover:bg-primary/90 text-primary-foreground font-semibold gap-2 shadow-md transition-all">
                3분 만에 시작하기
                <ArrowRight className="w-4 h-4" />
              </Button>
            </Link>
            <Link href="#architecture">
              <Button variant="outline" size="lg" className="h-11 px-6 text-xs md:text-sm border-border hover:bg-card gap-2 transition-colors">
                아키텍처 명세 보기
              </Button>
            </Link>
          </div>
        </div>

        {/* 2.1 브라우저 윈도우 프레임 목업 (Parallel 레퍼런스 스타일) */}
        <div className="mt-14 max-w-5xl mx-auto">
          <div className="rounded-2xl border border-border bg-card/80 shadow-2xl overflow-hidden backdrop-blur-sm">
            {/* 브라우저 상단 타이틀 바 */}
            <div className="px-4 py-3 border-b border-border bg-muted/40 flex items-center justify-between">
              <div className="flex items-center gap-2">
                <span className="w-3 h-3 rounded-full bg-rose-500/80 inline-block" />
                <span className="w-3 h-3 rounded-full bg-amber-500/80 inline-block" />
                <span className="w-3 h-3 rounded-full bg-emerald-500/80 inline-block" />
              </div>
              <div className="px-4 py-1 rounded-md bg-background border border-border text-[11px] font-mono text-muted-foreground max-w-xs w-full text-center truncate">
                https://console.rubeacon.internal/overview
              </div>
              <div className="flex items-center gap-1.5 text-xs text-emerald-500 font-mono">
                <span className="w-2 h-2 rounded-full bg-emerald-500 animate-pulse" />
                <span className="hidden sm:inline text-[11px]">LIVE 20.0 TPS</span>
              </div>
            </div>

            {/* 실제 텔레메트리 쇼케이스 및 시뮬레이터 인라인 임베드 */}
            <div className="p-4 sm:p-6 bg-card">
              <InteractiveSimulator />
            </div>
          </div>
        </div>
      </section>

      {/* 3. 소셜 프루프 생태계 바 (Ecosystem Integration Bar) */}
      <section className="py-8 border-y border-border bg-muted/20">
        <div className="max-w-5xl mx-auto px-4 flex flex-wrap items-center justify-center gap-6 sm:gap-10 text-xs font-mono text-muted-foreground uppercase tracking-wider">
          <span className="flex items-center gap-1.5"><Server className="w-3.5 h-3.5 text-primary" /> PaperMC / Purpur 1.20+</span>
          <span className="flex items-center gap-1.5"><Zap className="w-3.5 h-3.5 text-primary" /> Discord API v10</span>
          <span className="flex items-center gap-1.5"><Cpu className="w-3.5 h-3.5 text-primary" /> LMAX Disruptor</span>
          <span className="flex items-center gap-1.5"><Globe className="w-3.5 h-3.5 text-primary" /> Redis Streams 7.x</span>
          <span className="flex items-center gap-1.5"><Sparkles className="w-3.5 h-3.5 text-primary" /> Gemini 3.8 Flash</span>
        </div>
      </section>

      {/* 4. 비대칭 벤토 그리드(Bento Grid) 핵심 아키텍처 쇼케이스 */}
      <section id="architecture" className="py-20 px-4 border-b border-border bg-background">
        <div className="max-w-5xl mx-auto space-y-10">
          <div className="text-center space-y-3">
            <div className="inline-block text-xs font-mono text-primary uppercase tracking-widest font-semibold">
              Deep Architecture
            </div>
            <h2 className="text-2xl md:text-4xl font-bold tracking-tight text-foreground">
              초저지연 비동기 인프라 명세
            </h2>
            <p className="text-xs md:text-sm text-muted-foreground max-w-xl mx-auto leading-relaxed">
              메인 루프를 블로킹하는 웹훅 대신 단일 계층 WebSocket과 LMAX 링버퍼로 20.0 TPS를 사수합니다.
            </p>
          </div>

          {/* 비대칭 벤토 그리드 */}
          <div className="grid grid-cols-1 md:grid-cols-12 gap-5">
            {/* 카드 1 (7칸): LMAX Disruptor 링버퍼 */}
            <div className="md:col-span-7 rounded-xl border border-border bg-card p-6 sm:p-8 space-y-4 shadow-sm flex flex-col justify-between">
              <div className="space-y-3">
                <div className="w-9 h-9 rounded-lg border border-border bg-background flex items-center justify-center text-primary shadow-sm">
                  <Activity className="w-5 h-5" />
                </div>
                <h3 className="font-semibold text-base sm:text-lg text-foreground">
                  LMAX Disruptor 인메모리 링버퍼
                </h3>
                <p className="text-xs sm:text-sm text-muted-foreground leading-relaxed">
                  초당 수천 건의 블록 파괴와 전투 이벤트가 폭증해도 메인 스레드 20 TPS를 방어합니다. 락프리(Lock-free) 원형 큐 기반으로 0.08ms 초고속 버퍼링을 완결합니다.
                </p>
              </div>
              <div className="p-3 rounded-lg bg-background border border-border font-mono text-[11px] text-muted-foreground flex items-center justify-between">
                <span>RingBuffer Sequence: 1,048,576</span>
                <span className="text-emerald-500 font-semibold">0 Lock Contention</span>
              </div>
            </div>

            {/* 카드 2 (5칸): WSS 단일 계층 */}
            <div className="md:col-span-5 rounded-xl border border-border bg-card p-6 sm:p-8 space-y-4 shadow-sm flex flex-col justify-between">
              <div className="space-y-3">
                <div className="w-9 h-9 rounded-lg border border-border bg-background flex items-center justify-center text-primary shadow-sm">
                  <Layers className="w-5 h-5" />
                </div>
                <h3 className="font-semibold text-base sm:text-lg text-foreground">
                  WSS 단일 계층 게이트웨이
                </h3>
                <p className="text-xs sm:text-sm text-muted-foreground leading-relaxed">
                  중계 서버 없이 백엔드와 플러그인이 WebSocket으로 직접 통신하여 네트워크 홉을 최소화합니다.
                </p>
              </div>
              <div className="p-3 rounded-lg bg-background border border-border font-mono text-[11px] text-muted-foreground flex items-center justify-between">
                <span>Protocol: WSS Frame</span>
                <span className="text-primary font-semibold">0ms Polling</span>
              </div>
            </div>

            {/* 카드 3 (12칸 와이드): 디스코드 권한 실시간 동기화 & 2-Tier Redis 선착순 방어 */}
            <div className="md:col-span-12 rounded-xl border border-border bg-card p-6 sm:p-8 space-y-4 shadow-sm">
              <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-4">
                <div className="space-y-2">
                  <div className="w-9 h-9 rounded-lg border border-border bg-background flex items-center justify-center text-primary shadow-sm">
                    <ShieldCheck className="w-5 h-5" />
                  </div>
                  <h3 className="font-semibold text-base sm:text-lg text-foreground">
                    디스코드 권한 실시간 동기화
                  </h3>
                  <p className="text-xs sm:text-sm text-muted-foreground leading-relaxed max-w-2xl">
                    별도의 웹 인증 절차 없이 인게임 플레이어와 디스코드 역할을 지연 없이 일치시킵니다. 2-Tier Redis Admission Control로 1,000 RPS 동시 접속 이벤트 시에도 DB 부하 0%를 유지합니다.
                  </p>
                </div>
                <div className="shrink-0">
                  <Link href="/app">
                    <Button variant="outline" size="sm" className="text-xs border-border gap-1">
                      콘솔에서 파이프라인 구성 <ChevronRight className="w-3.5 h-3.5" />
                    </Button>
                  </Link>
                </div>
              </div>
            </div>
          </div>
        </div>
      </section>

      {/* 5. 인프라 대조표 (Feature Comparison Matrix - Parallel 레퍼런스 스타일) */}
      <section id="comparison" className="py-20 px-4 border-b border-border bg-muted/10">
        <div className="max-w-5xl mx-auto space-y-8">
          <div className="text-center space-y-2">
            <h2 className="text-2xl md:text-3xl font-bold tracking-tight text-foreground">
              기존 연동 방식과의 기술적 격차
            </h2>
            <p className="text-xs md:text-sm text-muted-foreground max-w-xl mx-auto">
              허위 과장 수치 없이 실제 런타임 벤치마크 지표를 투명하게 비교합니다.
            </p>
          </div>

          <div className="rounded-xl border border-border bg-card overflow-x-auto shadow-sm">
            <table className="w-full text-left text-xs sm:text-sm">
              <thead>
                <tr className="border-b border-border bg-muted/40 font-mono text-[11px] text-muted-foreground">
                  <th className="p-4 sm:p-5">검증 지표 및 기술 항목</th>
                  <th className="p-4 sm:p-5 text-primary font-bold">RuBeacon</th>
                  <th className="p-4 sm:p-5">레거시 웹훅 플러그인</th>
                  <th className="p-4 sm:p-5">단순 단독 봇</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-border">
                <tr>
                  <td className="p-4 sm:p-5 font-medium text-foreground">마인크래프트 메인 틱 점유 시간</td>
                  <td className="p-4 sm:p-5 text-primary font-bold font-mono">0.00ms (완전 비동기)</td>
                  <td className="p-4 sm:p-5 text-muted-foreground font-mono">15-50ms (틱 렉 스파이크)</td>
                  <td className="p-4 sm:p-5 text-muted-foreground">해당 없음</td>
                </tr>
                <tr>
                  <td className="p-4 sm:p-5 font-medium text-foreground">버퍼링 지연 메커니즘</td>
                  <td className="p-4 sm:p-5 text-primary font-bold font-mono">0.08ms (LMAX RingBuffer)</td>
                  <td className="p-4 sm:p-5 text-muted-foreground">버퍼 없음 (메모리 누수 위험)</td>
                  <td className="p-4 sm:p-5 text-muted-foreground">인메모리 단순 큐</td>
                </tr>
                <tr>
                  <td className="p-4 sm:p-5 font-medium text-foreground">동시성 1,000 RPS 선착순 이벤트</td>
                  <td className="p-4 sm:p-5 text-primary font-bold font-mono">Fast-Fail 0ms (Redis 2-Tier)</td>
                  <td className="p-4 sm:p-5 text-rose-500 font-medium">DB 커넥션 풀 고갈 장애</td>
                  <td className="p-4 sm:p-5 text-rose-500 font-medium">Discord API 429 차단</td>
                </tr>
                <tr>
                  <td className="p-4 sm:p-5 font-medium text-foreground">이벤트 중복 실행 방어 (멱등성)</td>
                  <td className="p-4 sm:p-5 text-emerald-500 font-semibold flex items-center gap-1.5">
                    <Check className="w-4 h-4" /> 비즈니스 멱등키 보장
                  </td>
                  <td className="p-4 sm:p-5 text-muted-foreground flex items-center gap-1.5">
                    <X className="w-4 h-4 text-rose-500" /> 재전송 시 중복 지급
                  </td>
                  <td className="p-4 sm:p-5 text-muted-foreground">부분 지원</td>
                </tr>
                <tr>
                  <td className="p-4 sm:p-5 font-medium text-foreground">자연어 워크플로우 자동 생성</td>
                  <td className="p-4 sm:p-5 text-emerald-500 font-semibold flex items-center gap-1.5">
                    <Check className="w-4 h-4" /> Gemini 3.8 Flash 에이전트
                  </td>
                  <td className="p-4 sm:p-5 text-muted-foreground flex items-center gap-1.5">
                    <X className="w-4 h-4 text-rose-500" /> YAML 수동 작성
                  </td>
                  <td className="p-4 sm:p-5 text-muted-foreground flex items-center gap-1.5">
                    <X className="w-4 h-4 text-rose-500" /> 미지원
                  </td>
                </tr>
              </tbody>
            </table>
          </div>
        </div>
      </section>

      {/* 6. 빠른 3단계 연동 안내 (How It Works) */}
      <section id="setup" className="py-20 px-4 border-b border-border bg-background">
        <div className="max-w-4xl mx-auto space-y-10">
          <div className="text-center space-y-2">
            <h2 className="text-2xl md:text-3xl font-bold tracking-tight text-foreground">
              빠른 3단계 연동 안내
            </h2>
            <p className="text-xs md:text-sm text-muted-foreground">
              복잡한 수동 설정 없이 3단계 온보딩으로 마인크래프트 서버와 디스코드를 연결합니다.
            </p>
          </div>

          <div className="grid grid-cols-1 md:grid-cols-3 gap-5">
            <div className="p-6 rounded-xl border border-border bg-card space-y-3 shadow-sm">
              <div className="flex items-center justify-between">
                <span className="font-mono text-xs font-bold text-primary px-2 py-0.5 rounded bg-primary/10">01</span>
                <Bot className="w-4 h-4 text-muted-foreground" />
              </div>
              <h3 className="font-semibold text-sm text-foreground">디스코드 봇 초대</h3>
              <p className="text-xs text-muted-foreground leading-relaxed">
                내 디스코드 서버에 전용 봇을 추가하여 알림 채널과 연동 권한을 확보합니다.
              </p>
            </div>

            <div className="p-6 rounded-xl border border-border bg-card space-y-3 shadow-sm">
              <div className="flex items-center justify-between">
                <span className="font-mono text-xs font-bold text-primary px-2 py-0.5 rounded bg-primary/10">02</span>
                <Key className="w-4 h-4 text-muted-foreground" />
              </div>
              <h3 className="font-semibold text-sm text-foreground">인스턴스 토큰 발급</h3>
              <p className="text-xs text-muted-foreground leading-relaxed">
                콘솔에서 발급한 고유 토큰을 플러그인 설정에 등록하여 보안 채널을 승인합니다.
              </p>
            </div>

            <div className="p-6 rounded-xl border border-border bg-card space-y-3 shadow-sm">
              <div className="flex items-center justify-between">
                <span className="font-mono text-xs font-bold text-primary px-2 py-0.5 rounded bg-primary/10">03</span>
                <Radio className="w-4 h-4 text-muted-foreground" />
              </div>
              <h3 className="font-semibold text-sm text-foreground">실시간 하트비트 확인</h3>
              <p className="text-xs text-muted-foreground leading-relaxed">
                서버 부팅 즉시 웹소켓 핑 신호를 감지하여 관제탑을 온라인으로 활성화합니다.
              </p>
            </div>
          </div>
        </div>
      </section>

      {/* 7. 풀 와이드 하이라이트 바텀 CTA (Parallel Bottom CTA 스타일) */}
      <section className="py-16 md:py-24 px-4 bg-background">
        <div className="max-w-4xl mx-auto rounded-3xl bg-gradient-to-r from-primary/90 via-primary to-primary/80 p-8 sm:p-12 md:p-16 text-center text-primary-foreground shadow-2xl space-y-6 relative overflow-hidden">
          <div className="space-y-3 max-w-xl mx-auto">
            <h2 className="text-2xl sm:text-3xl md:text-4xl font-bold tracking-tight leading-tight">
              서버 렉 없는 디스코드 커뮤니티, 지금 즉시 연결하세요.
            </h2>
            <p className="text-xs sm:text-sm text-primary-foreground/80 leading-relaxed">
              무료 플러그인 JAR 다운로드와 3분 온보딩으로 귀하의 서버에 즉시 무지연 텔레메트리를 가동할 수 있습니다.
            </p>
          </div>

          <div className="pt-2">
            <Link href="/app">
              <Button size="lg" className="h-11 px-8 text-xs sm:text-sm bg-background text-foreground hover:bg-background/90 font-bold shadow-lg gap-2">
                지금 콘솔 시작하기
                <ArrowRight className="w-4 h-4" />
              </Button>
            </Link>
          </div>
        </div>
      </section>

      {/* 푸터 */}
      <footer className="mt-auto border-t border-border bg-card py-8 text-center text-xs text-muted-foreground">
        <div className="max-w-5xl mx-auto px-4 flex flex-col sm:flex-row items-center justify-between gap-4">
          <div>RuBeacon &copy; 2026. 초저지연 마인크래프트-디스코드 브릿지.</div>
          <div className="flex gap-4">
            <Link href="#architecture" className="hover:text-foreground transition-colors">아키텍처</Link>
            <Link href="#comparison" className="hover:text-foreground transition-colors">성능 비교</Link>
            <Link href="#setup" className="hover:text-foreground transition-colors">연동 가이드</Link>
            <Link href="/admin" className="hover:text-foreground transition-colors">서버 관리 콘솔</Link>
          </div>
        </div>
      </footer>
    </div>
  );
}
