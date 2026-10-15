"use client";

import React, { useState, useEffect } from "react";
import Link from "next/link";
import { ThemeToggle } from "@/components/ui/ThemeToggle";
import { Button } from "@/components/ui/button";
import { Badge } from "@/components/ui/badge";
import { fetchUserProfile } from "@/lib/api";
import { UserProfile } from "@/types/workflow";
import {
  ArrowRight,
  Server,
  Zap,
  Cpu,
  Globe,
  Radio,
  Check,
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
    <div className="min-h-screen flex flex-col bg-background text-foreground selection:bg-primary selection:text-primary-foreground font-sans">
      {/* 1. 최상단 헤더 */}
      <header className="border-b border-border bg-background/90 backdrop-blur sticky top-0 z-50">
        <div className="max-w-6xl mx-auto px-6 h-14 flex items-center justify-between">
          <div className="flex items-center gap-3">
            <div className="w-6 h-6 rounded bg-primary flex items-center justify-center text-primary-foreground font-bold text-xs tracking-tighter">
              R
            </div>
            <div className="font-semibold text-sm tracking-tight flex items-center gap-2">
              RuBeacon
              <span className="text-[10px] font-mono text-muted-foreground border-l border-border pl-2">
                v1.0.0
              </span>
            </div>
          </div>

          <div className="flex items-center gap-4">
            <Link
              href="#pipeline"
              className="text-xs text-muted-foreground hover:text-foreground transition-colors hidden sm:inline-block"
            >
              데이터 파이프라인
            </Link>
            <Link
              href="#architecture"
              className="text-xs text-muted-foreground hover:text-foreground transition-colors hidden sm:inline-block"
            >
              아키텍처 명세
            </Link>
            <Link
              href="#setup"
              className="text-xs text-muted-foreground hover:text-foreground transition-colors hidden sm:inline-block"
            >
              3단계 연동
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

      {/* 2. Hero: 박스 없는 대담한 타이포그래피 & 명제 */}
      <section className="pt-20 pb-16 md:pt-28 md:pb-24 px-6 border-b border-border">
        <div className="max-w-5xl mx-auto space-y-8">
          <div className="inline-flex items-center gap-2 text-xs font-mono text-muted-foreground">
            <span className="w-1.5 h-1.5 rounded-full bg-primary" />
            초저지연 마인크래프트-디스코드 브릿지
          </div>

          <h1 className="text-3xl sm:text-5xl md:text-6xl font-bold tracking-tight text-foreground leading-[1.12] max-w-4xl">
            서버 TPS 저하 없는 마인크래프트-디스코드 실시간 연동
          </h1>

          <p className="text-base md:text-lg text-muted-foreground max-w-3xl leading-relaxed">
            서버에 이벤트가 몰려도 렉이 걸리지 않습니다. 게임 속 사건을 디스코드 알림과 역할에 곧바로 반영합니다.
          </p>
          <p className="text-sm md:text-base text-muted-foreground/80 max-w-3xl leading-relaxed">
            초당 수만 건의 이벤트가 폭증해도 마인크래프트 메인 틱을 0.00ms도 점유하지 않습니다.
          </p>

          <div className="pt-2 flex flex-wrap items-center gap-4">
            <Link href="/app">
              <Button size="lg" className="h-10 px-6 text-xs bg-primary hover:bg-primary/90 text-primary-foreground font-semibold gap-2">
                루비콘 콘솔 시작하기
                <ArrowRight className="w-4 h-4" />
              </Button>
            </Link>
            <Link href="#pipeline">
              <Button variant="outline" size="lg" className="h-10 px-6 text-xs border-border hover:bg-muted/40 transition-colors">
                실제 데이터 파이프라인 보기 ↓
              </Button>
            </Link>
          </div>
        </div>
      </section>

      {/* 3. 텔레메트리 지표 (박스 없는 3분할 직선 그리드) */}
      <section className="border-b border-border bg-background">
        <div className="max-w-5xl mx-auto grid grid-cols-1 md:grid-cols-3 divide-y md:divide-y-0 md:divide-x divide-border">
          <div className="p-8 space-y-2">
            <div className="text-3xl md:text-4xl font-mono font-bold text-foreground tracking-tight">
              0.08ms
            </div>
            <div className="text-xs font-semibold text-foreground">링버퍼 내부 큐잉 지연</div>
            <div className="text-xs text-muted-foreground leading-relaxed">
              LMAX Disruptor 락프리 큐를 통해 힙 메모리 스래싱 및 컨텐션을 방어합니다.
            </div>
          </div>

          <div className="p-8 space-y-2">
            <div className="text-3xl md:text-4xl font-mono font-bold text-primary tracking-tight">
              0.00ms
            </div>
            <div className="text-xs font-semibold text-foreground">마인크래프트 메인 스레드 점유 오버헤드</div>
            <div className="text-xs text-muted-foreground leading-relaxed">
              완전 비동기 스레드 풀에서 즉시 배출되어 서버 20.0 TPS를 사수합니다.
            </div>
          </div>

          <div className="p-8 space-y-2">
            <div className="text-3xl md:text-4xl font-mono font-bold text-foreground tracking-tight">
              40-50ms
            </div>
            <div className="text-xs font-semibold text-foreground">E2E 디스코드 도착 시간</div>
            <div className="text-xs text-muted-foreground leading-relaxed">
              WSS 단일 홉 통신과 Redis Streams 이벤트 버스를 통한 실측 도착 지연입니다.
            </div>
          </div>
        </div>
      </section>

      {/* 4. 실제 데이터 파이프라인 (직선과 점으로 흐름을 표현하는 청사진 뷰) */}
      <section id="pipeline" className="py-20 px-6 border-b border-border bg-muted/10">
        <div className="max-w-5xl mx-auto space-y-12">
          <div className="space-y-2">
            <div className="text-xs font-mono text-primary uppercase tracking-widest font-semibold">
              Event Pipeline Architecture
            </div>
            <h2 className="text-2xl md:text-3xl font-bold tracking-tight text-foreground">
              이벤트가 처리되는 실제 런타임 경로
            </h2>
            <p className="text-xs md:text-sm text-muted-foreground max-w-2xl leading-relaxed">
              클릭용 데모 대신, 마인크래프트 서버의 Bukkit 이벤트가 디스코드에 도달하기까지의 실측 처리 과정을 직선 구조로 나타냅니다.
            </p>
          </div>

          {/* 직선으로 연결된 수평 파이프라인 노드 */}
          <div className="relative pt-4">
            {/* 배경 가로선 */}
            <div className="hidden md:block absolute top-10 left-0 right-0 h-px bg-border -z-0" />

            <div className="grid grid-cols-1 md:grid-cols-4 gap-8 md:gap-4 relative z-10">
              {/* 노드 1 */}
              <div className="space-y-3">
                <div className="flex items-center gap-2">
                  <span className="w-3.5 h-3.5 rounded-full bg-background border-2 border-primary shrink-0" />
                  <span className="font-mono text-xs font-semibold text-primary">NODE 01</span>
                </div>
                <div className="font-semibold text-sm text-foreground">마인크래프트 서버</div>
                <div className="text-xs font-mono text-muted-foreground bg-background p-2.5 rounded border border-border">
                  PlayerLevelUpEvent<br />
                  <span className="text-emerald-500 font-semibold">0.00ms (완전 비동기)</span>
                </div>
                <p className="text-xs text-muted-foreground leading-relaxed">
                  플러그인 리스너가 메인 스레드를 블로킹하지 않고 즉시 링버퍼로 이벤트를 핸드오프합니다.
                </p>
              </div>

              {/* 노드 2 */}
              <div className="space-y-3">
                <div className="flex items-center gap-2">
                  <span className="w-3.5 h-3.5 rounded-full bg-background border-2 border-border shrink-0" />
                  <span className="font-mono text-xs font-semibold text-muted-foreground">NODE 02</span>
                </div>
                <div className="font-semibold text-sm text-foreground">LMAX Disruptor</div>
                <div className="text-xs font-mono text-muted-foreground bg-background p-2.5 rounded border border-border">
                  RingBuffer Seq: 1M<br />
                  <span className="text-foreground font-semibold">0.08ms Lock-free</span>
                </div>
                <p className="text-xs text-muted-foreground leading-relaxed">
                  초당 수만 건의 이벤트가 폭증해도 락 경합 없이 순차 버퍼링되어 메모리 누수를 원천 차단합니다.
                </p>
              </div>

              {/* 노드 3 */}
              <div className="space-y-3">
                <div className="flex items-center gap-2">
                  <span className="w-3.5 h-3.5 rounded-full bg-background border-2 border-border shrink-0" />
                  <span className="font-mono text-xs font-semibold text-muted-foreground">NODE 03</span>
                </div>
                <div className="font-semibold text-sm text-foreground">WSS 게이트웨이 & Redis</div>
                <div className="text-xs font-mono text-muted-foreground bg-background p-2.5 rounded border border-border">
                  WSS Single-Hop Frame<br />
                  <span className="text-foreground font-semibold">Redis Streams MAXLEN</span>
                </div>
                <p className="text-xs text-muted-foreground leading-relaxed">
                  중계 서버 없는 단일 계층 WebSocket으로 수신 즉시 비즈니스 멱등키를 부여하여 스트림에 발행합니다.
                </p>
              </div>

              {/* 노드 4 */}
              <div className="space-y-3">
                <div className="flex items-center gap-2">
                  <span className="w-3.5 h-3.5 rounded-full bg-background border-2 border-emerald-500 shrink-0" />
                  <span className="font-mono text-xs font-semibold text-emerald-500">NODE 04</span>
                </div>
                <div className="font-semibold text-sm text-foreground">디스코드 봇 서비스</div>
                <div className="text-xs font-mono text-muted-foreground bg-background p-2.5 rounded border border-border">
                  Discord API v10 Ack<br />
                  <span className="text-emerald-500 font-semibold">42ms E2E Delivered</span>
                </div>
                <p className="text-xs text-muted-foreground leading-relaxed">
                  지정된 채널에 축하 임베드 메시지를 발송하거나 대상 사용자에게 실시간 역할을 자동 부여합니다.
                </p>
              </div>
            </div>
          </div>
        </div>
      </section>

      {/* 5. 아키텍처 명세 (직선으로 구분된 3대 핵심 엔지니어링) */}
      <section id="architecture" className="py-20 px-6 border-b border-border bg-background">
        <div className="max-w-5xl mx-auto space-y-12">
          <div className="space-y-2">
            <h2 className="text-2xl md:text-3xl font-bold tracking-tight text-foreground">
              초저지연 비동기 인프라 명세
            </h2>
            <p className="text-xs md:text-sm text-muted-foreground max-w-xl">
              메인 루프를 블로킹하는 웹훅 대신 단일 계층 WebSocket과 LMAX 링버퍼로 20.0 TPS를 사수합니다.
            </p>
          </div>

          <div className="divide-y divide-border border-y border-border">
            <div className="py-8 grid grid-cols-1 md:grid-cols-12 gap-6 items-start">
              <div className="md:col-span-4 font-mono text-xs text-primary font-semibold">
                ARCHITECTURE 01
              </div>
              <div className="md:col-span-8 space-y-2">
                <h3 className="font-semibold text-base text-foreground">
                  WSS 단일 계층 게이트웨이
                </h3>
                <p className="text-xs md:text-sm text-muted-foreground leading-relaxed">
                  중계 서버 없이 백엔드와 플러그인이 WebSocket으로 직접 통신하여 네트워크 홉을 최소화합니다.
                  중복 핸드셰이크를 없애고 단일 영속 연결 위에서 프로토콜 프레임을 양방향 직결합니다.
                </p>
              </div>
            </div>

            <div className="py-8 grid grid-cols-1 md:grid-cols-12 gap-6 items-start">
              <div className="md:col-span-4 font-mono text-xs text-primary font-semibold">
                ARCHITECTURE 02
              </div>
              <div className="md:col-span-8 space-y-2">
                <h3 className="font-semibold text-base text-foreground">
                  LMAX Disruptor 인메모리 링버퍼
                </h3>
                <p className="text-xs md:text-sm text-muted-foreground leading-relaxed">
                  초당 수천 건의 블록 파괴와 전투 이벤트가 폭증해도 메인 스레드 20 TPS를 방어합니다.
                  락을 사용하지 않고 CPU 캐시 라인 패딩(False Sharing 방어)이 적용된 링버퍼에 안전하게 격리합니다.
                </p>
              </div>
            </div>

            <div className="py-8 grid grid-cols-1 md:grid-cols-12 gap-6 items-start">
              <div className="md:col-span-4 font-mono text-xs text-primary font-semibold">
                ARCHITECTURE 03
              </div>
              <div className="md:col-span-8 space-y-2">
                <h3 className="font-semibold text-base text-foreground">
                  디스코드 권한 실시간 동기화
                </h3>
                <p className="text-xs md:text-sm text-muted-foreground leading-relaxed">
                  별도의 웹 인증 절차 없이 인게임 플레이어와 디스코드 역할을 지연 없이 일치시킵니다.
                  2-Tier Redis 선착순 제어 로직을 내장하여 동시 1,000 RPS 트래픽 인입 시에도 데이터베이스 커넥션 풀을 보호합니다.
                </p>
              </div>
            </div>
          </div>
        </div>
      </section>

      {/* 6. 기술적 격차 비교 (박스 없는 1px 직선 테뷸러 뷰) */}
      <section className="py-20 px-6 border-b border-border bg-background">
        <div className="max-w-5xl mx-auto space-y-8">
          <div className="space-y-2">
            <div className="text-xs font-mono text-primary uppercase tracking-widest font-semibold">
              Performance Matrix
            </div>
            <h2 className="text-2xl md:text-3xl font-bold tracking-tight text-foreground">
              기존 연동 방식과의 기술적 격차
            </h2>
            <p className="text-xs md:text-sm text-muted-foreground">
              동기식 HTTP 웹훅과 비동기 LMAX Disruptor 기반 RuBeacon의 구조적 차이입니다.
            </p>
          </div>

          <div className="border-t border-b border-border divide-y divide-border text-xs md:text-sm">
            <div className="py-3.5 grid grid-cols-12 text-muted-foreground font-mono text-[11px] uppercase tracking-wider">
              <div className="col-span-5 md:col-span-4">평가 지표</div>
              <div className="col-span-4 md:col-span-4 text-primary font-semibold">RuBeacon</div>
              <div className="col-span-3 md:col-span-4">기존 DiscordSRV / Webhook</div>
            </div>

            <div className="py-4 grid grid-cols-12 items-baseline gap-2">
              <div className="col-span-5 md:col-span-4 font-medium text-foreground">
                마인크래프트 메인 틱 점유 시간
              </div>
              <div className="col-span-4 md:col-span-4 font-mono font-bold text-primary">
                0.00ms (완전 비동기)
              </div>
              <div className="col-span-3 md:col-span-4 text-muted-foreground">
                15 - 50ms (동기 I/O 렉 유발)
              </div>
            </div>

            <div className="py-4 grid grid-cols-12 items-baseline gap-2">
              <div className="col-span-5 md:col-span-4 font-medium text-foreground">
                이벤트 처리 엔진
              </div>
              <div className="col-span-4 md:col-span-4 font-semibold text-foreground">
                LMAX Disruptor 링버퍼
              </div>
              <div className="col-span-3 md:col-span-4 text-muted-foreground">
                단순 HTTP Webhook / 동기 블로킹
              </div>
            </div>

            <div className="py-4 grid grid-cols-12 items-baseline gap-2">
              <div className="col-span-5 md:col-span-4 font-medium text-foreground">
                네트워크 아키텍처
              </div>
              <div className="col-span-4 md:col-span-4 font-semibold text-foreground">
                WSS 단일 홉 양방향 게이트웨이
              </div>
              <div className="col-span-3 md:col-span-4 text-muted-foreground">
                매 이벤트마다 신규 TCP 연결
              </div>
            </div>

            <div className="py-4 grid grid-cols-12 items-baseline gap-2">
              <div className="col-span-5 md:col-span-4 font-medium text-foreground">
                대량 이벤트 폭증 시 동작
              </div>
              <div className="col-span-4 md:col-span-4 font-semibold text-foreground">
                락프리 큐잉 및 백프레셔 제어
              </div>
              <div className="col-span-3 md:col-span-4 text-muted-foreground">
                마인크래프트 서버 틱 드랍 및 중단
              </div>
            </div>
          </div>
        </div>
      </section>

      {/* 7. 빠른 3단계 연동 안내 (직선 인라인 플로우) */}
      <section id="setup" className="py-20 px-6 border-b border-border bg-muted/10">
        <div className="max-w-5xl mx-auto space-y-12">
          <div className="space-y-2">
            <h2 className="text-2xl md:text-3xl font-bold tracking-tight text-foreground">
              빠른 3단계 연동 안내
            </h2>
            <p className="text-xs md:text-sm text-muted-foreground">
              복잡한 수동 설정 없이 3단계 온보딩으로 마인크래프트 서버와 디스코드를 연결합니다.
            </p>
          </div>

          <div className="grid grid-cols-1 md:grid-cols-3 gap-8">
            <div className="space-y-3 border-t border-border pt-4">
              <div className="font-mono text-xs font-bold text-primary">STEP 01</div>
              <h3 className="font-semibold text-sm text-foreground">디스코드 봇 초대</h3>
              <p className="text-xs text-muted-foreground leading-relaxed">
                내 디스코드 서버에 전용 봇을 추가하여 알림 채널과 연동 권한을 확보합니다.
              </p>
            </div>

            <div className="space-y-3 border-t border-border pt-4">
              <div className="font-mono text-xs font-bold text-primary">STEP 02</div>
              <h3 className="font-semibold text-sm text-foreground">인스턴스 토큰 발급</h3>
              <p className="text-xs text-muted-foreground leading-relaxed">
                콘솔에서 발급한 고유 토큰을 플러그인 설정에 등록하여 보안 채널을 승인합니다.
              </p>
            </div>

            <div className="space-y-3 border-t border-border pt-4">
              <div className="font-mono text-xs font-bold text-primary">STEP 03</div>
              <h3 className="font-semibold text-sm text-foreground">실시간 하트비트 확인</h3>
              <p className="text-xs text-muted-foreground leading-relaxed">
                서버 부팅 즉시 웹소켓 핑 신호를 감지하여 관제탑을 온라인으로 활성화합니다.
              </p>
            </div>
          </div>
        </div>
      </section>

      {/* 7. 꼭 필요한 부분에만 쓰는 단 하나의 미니멀 박스 (Terminal Snippet & CTA) */}
      <section className="py-20 px-6 bg-background">
        <div className="max-w-5xl mx-auto rounded-lg border border-border bg-card p-8 md:p-12 flex flex-col md:flex-row items-start md:items-center justify-between gap-8 shadow-sm">
          <div className="space-y-3 max-w-xl">
            <h2 className="text-xl md:text-2xl font-bold tracking-tight text-foreground">
              서버 렉 없는 디스코드 커뮤니티, 지금 즉시 연결하세요.
            </h2>
            <p className="text-xs md:text-sm text-muted-foreground leading-relaxed">
              PaperMC/Purpur 플러그인 JAR 파일 하나와 웹 콘솔 토큰 발급으로 즉시 연동됩니다.
            </p>
            <div className="pt-2 font-mono text-[11px] text-muted-foreground">
              curl -O https://rubeacon.internal/download/ru-beacon-plugin.jar
            </div>
          </div>

          <div className="shrink-0">
            <Link href="/app">
              <Button size="lg" className="h-10 px-6 text-xs bg-primary hover:bg-primary/90 text-primary-foreground font-semibold gap-2 shadow-sm">
                루비콘 콘솔 열기
                <ArrowRight className="w-4 h-4" />
              </Button>
            </Link>
          </div>
        </div>
      </section>

      {/* 8. 푸터 */}
      <footer className="mt-auto border-t border-border py-8 text-xs text-muted-foreground">
        <div className="max-w-5xl mx-auto px-6 flex flex-col sm:flex-row items-center justify-between gap-4">
          <div>RuBeacon &copy; 2026. 초저지연 마인크래프트-디스코드 브릿지.</div>
          <div className="flex gap-6 font-mono text-[11px]">
            <Link href="#pipeline" className="hover:text-foreground transition-colors">파이프라인</Link>
            <Link href="#architecture" className="hover:text-foreground transition-colors">아키텍처</Link>
            <Link href="#setup" className="hover:text-foreground transition-colors">3분 연동</Link>
            <Link href="/admin" className="hover:text-foreground transition-colors">관리 콘솔</Link>
          </div>
        </div>
      </footer>
    </div>
  );
}
