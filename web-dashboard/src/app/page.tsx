"use client";

import React, { useState, useEffect, useRef } from "react";
import Link from "next/link";
import anime from "animejs";
import { ThemeToggle } from "@/components/ui/ThemeToggle";
import { Button } from "@/components/ui/button";
import { fetchUserProfile } from "@/lib/api";
import { UserProfile } from "@/types/workflow";
import { BeaconStage, useLatestRelease } from "@/components/landing/BeaconStage";
import { ArrowRight, Download } from "lucide-react";

export default function LandingPage() {
  const [user, setUser] = useState<UserProfile | null>(null);
  const release = useLatestRelease();
  const pageRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    fetchUserProfile().then((profile) => {
      if (profile && profile.tenants.length > 0) {
        setUser(profile);
      }
    });
  }, []);

  // 섹션 진입 시 시선 흐름을 돕는 절제된 직선 드로잉 및 스태거 모션
  useEffect(() => {
    const prefersReduced =
      typeof window !== "undefined" &&
      window.matchMedia &&
      window.matchMedia("(prefers-reduced-motion: reduce)").matches;

    if (prefersReduced || typeof IntersectionObserver === "undefined") return;

    const root = pageRef.current;
    if (!root) return;

    const sections = root.querySelectorAll<HTMLElement>("[data-viewport-section]");
    const animatedSet = new WeakSet<Element>();

    const observer = new IntersectionObserver(
      (entries) => {
        entries.forEach((entry) => {
          if (!entry.isIntersecting || animatedSet.has(entry.target)) return;
          animatedSet.add(entry.target);

          const lines = entry.target.querySelectorAll("[data-animate-line]");
          const items = entry.target.querySelectorAll("[data-animate-item]");

          if (lines.length > 0) {
            anime({
              targets: lines,
              scaleX: [0, 1],
              duration: 750,
              easing: "easeOutQuart",
            });
          }

          if (items.length > 0) {
            anime({
              targets: items,
              opacity: [0.15, 1],
              translateY: [14, 0],
              delay: anime.stagger(65),
              duration: 520,
              easing: "easeOutCubic",
            });
          }
        });
      },
      { threshold: 0.22 }
    );

    sections.forEach((sec) => observer.observe(sec));
    return () => observer.disconnect();
  }, []);

  return (
    <div
      ref={pageRef}
      className="min-h-screen flex flex-col bg-background text-foreground selection:bg-primary selection:text-primary-foreground font-sans break-keep"
    >
      {/* 상단 고정 헤더 */}
      <header className="border-b border-border/70 bg-background/85 backdrop-blur-md sticky top-0 z-50">
        <div className="max-w-5xl mx-auto px-6 h-14 flex items-center justify-between">
          <div className="flex items-center gap-3">
            <div className="w-5 h-5 rounded-sm bg-primary flex items-center justify-center text-primary-foreground font-bold text-[11px] tracking-tighter">
              R
            </div>
            <div className="font-semibold text-sm tracking-tight flex items-center gap-2">
              RuBeacon
              <span className="text-[11px] font-mono text-muted-foreground border-l border-border pl-2">
                {release.tagName}
              </span>
            </div>
          </div>

          <div className="flex items-center gap-5">
            <Link
              href="#pipeline"
              className="text-xs text-muted-foreground hover:text-foreground transition-colors hidden sm:inline-block"
            >
              작동 방식
            </Link>
            <Link
              href="#architecture"
              className="text-xs text-muted-foreground hover:text-foreground transition-colors hidden sm:inline-block"
            >
              핵심 기술
            </Link>
            <Link
              href="#comparison"
              className="text-xs text-muted-foreground hover:text-foreground transition-colors hidden sm:inline-block"
            >
              성능 비교
            </Link>
            <Link
              href="#setup"
              className="text-xs text-muted-foreground hover:text-foreground transition-colors hidden sm:inline-block"
            >
              시작하기
            </Link>
            <ThemeToggle />
            <Link href="/app">
              <Button
                size="sm"
                className="h-8 px-3.5 text-xs bg-primary hover:bg-primary/90 text-primary-foreground font-medium gap-1.5"
              >
                {user ? "대시보드 이동" : "콘솔 시작하기"}
                <ArrowRight className="w-3.5 h-3.5" />
              </Button>
            </Link>
          </div>
        </div>
      </header>

      {/* 뷰포트 0: 루비 비콘 피라미드 오프닝 스테이지 (오직 모션과 Scroll 꺽쇄만 존재) */}
      <BeaconStage />

      {/* 뷰포트 1: 핵심 가치 제안 (Hero & 3대 실측 지표) */}
      <section
        id="hero"
        data-viewport-section
        className="py-24 md:py-0 md:min-h-[calc(100dvh-3.5rem)] px-6 border-b border-border/70 flex flex-col justify-center"
      >
        <div className="max-w-5xl mx-auto w-full space-y-16 md:space-y-24">
          <div className="space-y-6 max-w-3xl" data-animate-item>
            <div className="inline-flex items-center gap-2 text-xs text-muted-foreground">
              <span className="w-1.5 h-1.5 rounded-full bg-primary" />
              초저지연 마인크래프트-디스코드 브릿지
            </div>

            <h1 className="text-3xl sm:text-5xl md:text-6xl font-bold tracking-tight text-foreground leading-[1.14] text-balance">
              서버 TPS 저하 없는 마인크래프트-디스코드 실시간 연동
            </h1>

            <p className="text-base md:text-xl text-muted-foreground leading-relaxed max-w-2xl">
              서버에 이벤트가 몰려도 렉이 걸리지 않습니다. 게임 속 사건을 디스코드 알림과 역할에 곧바로 반영합니다.
            </p>

            <div className="pt-2 flex flex-wrap items-center gap-3">
              <Link href="/app">
                <Button
                  size="lg"
                  className="h-11 px-6 text-xs md:text-sm bg-primary hover:bg-primary/90 text-primary-foreground font-semibold gap-2"
                >
                  루비콘 콘솔 시작하기
                  <ArrowRight className="w-4 h-4" />
                </Button>
              </Link>
              <a href={release.downloadUrl} target="_blank" rel="noopener noreferrer">
                <Button
                  variant="outline"
                  size="lg"
                  className="h-11 px-6 text-xs md:text-sm border-border hover:bg-muted/40 transition-colors gap-2"
                >
                  <Download className="w-4 h-4" />
                  플러그인 다운로드 ({release.tagName})
                </Button>
              </a>
            </div>
          </div>

          {/* 1px 직선으로만 구획된 3대 실측 지표 */}
          <div className="pt-10 border-t border-border origin-left" data-animate-line>
            <div className="grid grid-cols-1 md:grid-cols-3 gap-10 md:gap-0 md:divide-x divide-border">
              <div className="md:pr-10 space-y-1.5" data-animate-item>
                <div className="text-3xl md:text-4xl font-mono font-bold text-primary tracking-tight">
                  0.00ms
                </div>
                <div className="text-sm font-semibold text-foreground">
                  마인크래프트 메인 스레드 점유
                </div>
                <p className="text-xs text-muted-foreground leading-relaxed">
                  모든 알림을 백그라운드에서 분리 처리해 서버 20.0 TPS를 지킵니다.
                </p>
              </div>

              <div className="md:px-10 space-y-1.5" data-animate-item>
                <div className="text-3xl md:text-4xl font-mono font-bold text-foreground tracking-tight">
                  0.08ms
                </div>
                <div className="text-sm font-semibold text-foreground">초고속 순차 대기열</div>
                <p className="text-xs text-muted-foreground leading-relaxed">
                  동시 수만 건의 이벤트가 몰려도 병목 없이 순서대로 담아냅니다.
                </p>
              </div>

              <div className="md:pl-10 space-y-1.5" data-animate-item>
                <div className="text-3xl md:text-4xl font-mono font-bold text-foreground tracking-tight">
                  40-50ms
                </div>
                <div className="text-sm font-semibold text-foreground">디스코드 도착 시간</div>
                <p className="text-xs text-muted-foreground leading-relaxed">
                  게임 내 사건 발생 즉시 눈 깜짝할 사이에 디스코드에 전달됩니다.
                </p>
              </div>
            </div>
          </div>
        </div>
      </section>

      {/* 뷰포트 2: 런타임 데이터 파이프라인 (1화면 1헤더 + 시선 유도 수평 직선) */}
      <section
        id="pipeline"
        data-viewport-section
        className="py-24 md:py-0 md:min-h-[calc(100dvh-3.5rem)] px-6 border-b border-border/70 flex flex-col justify-center"
      >
        <div className="max-w-5xl mx-auto w-full space-y-16 md:space-y-20">
          <div className="space-y-3 max-w-2xl" data-animate-item>
            <h2 className="text-2xl sm:text-4xl font-bold tracking-tight text-foreground text-balance">
              이벤트가 처리되는 실제 런타임 경로
            </h2>
            <p className="text-sm md:text-base text-muted-foreground leading-relaxed">
              게임 속 사건이 서버 렉을 유발하지 않고 디스코드에 도달하는 4단계 흐름입니다.
            </p>
          </div>

          <div className="relative">
            {/* 데스크톱 수평 관통 직선 (스크롤 진입 시 좌->우 드로잉) */}
            <div
              data-animate-line
              className="hidden md:block w-full h-px bg-border origin-left mb-8"
            />

            <div className="grid grid-cols-1 md:grid-cols-4 gap-10 md:gap-8">
              <div
                data-animate-item
                className="space-y-3 border-l-2 md:border-l-0 border-primary pl-4 md:pl-0"
              >
                <div className="flex items-center gap-2">
                  <span className="hidden md:inline-block w-2 h-2 rounded-full bg-primary" />
                  <span className="font-mono text-xs text-primary font-semibold">01</span>
                </div>
                <h3 className="font-semibold text-base text-foreground">마인크래프트 서버</h3>
                <div className="text-xs font-mono text-emerald-500 font-semibold">
                  0.00ms (완전 비동기)
                </div>
                <p className="text-xs md:text-sm text-muted-foreground leading-relaxed">
                  게임 진행을 멈추지 않고 발생한 사건만 즉시 대기열로 넘깁니다.
                </p>
              </div>

              <div
                data-animate-item
                className="space-y-3 border-l-2 md:border-l-0 border-border pl-4 md:pl-0"
              >
                <div className="flex items-center gap-2">
                  <span className="hidden md:inline-block w-2 h-2 rounded-full bg-foreground/40" />
                  <span className="font-mono text-xs text-muted-foreground">02</span>
                </div>
                <h3 className="font-semibold text-base text-foreground">LMAX Disruptor</h3>
                <div className="text-xs font-mono text-foreground/80 font-semibold">
                  0.08ms 초고속 버퍼
                </div>
                <p className="text-xs md:text-sm text-muted-foreground leading-relaxed">
                  전투나 채굴 이벤트가 폭증해도 순서대로 안전하게 줄을 세웁니다.
                </p>
              </div>

              <div
                data-animate-item
                className="space-y-3 border-l-2 md:border-l-0 border-border pl-4 md:pl-0"
              >
                <div className="flex items-center gap-2">
                  <span className="hidden md:inline-block w-2 h-2 rounded-full bg-foreground/40" />
                  <span className="font-mono text-xs text-muted-foreground">03</span>
                </div>
                <h3 className="font-semibold text-base text-foreground">WSS 게이트웨이 & Redis</h3>
                <div className="text-xs font-mono text-foreground/80 font-semibold">
                  직결 웹소켓 통로
                </div>
                <p className="text-xs md:text-sm text-muted-foreground leading-relaxed">
                  매번 새로 연결하지 않고 열려 있는 전용 통로로 데이터를 즉시 보냅니다.
                </p>
              </div>

              <div
                data-animate-item
                className="space-y-3 border-l-2 md:border-l-0 border-emerald-500 pl-4 md:pl-0"
              >
                <div className="flex items-center gap-2">
                  <span className="hidden md:inline-block w-2 h-2 rounded-full bg-emerald-500" />
                  <span className="font-mono text-xs text-emerald-500 font-semibold">04</span>
                </div>
                <h3 className="font-semibold text-base text-foreground">디스코드 봇 서비스</h3>
                <div className="text-xs font-mono text-emerald-500 font-semibold">
                  42ms 전달 완료
                </div>
                <p className="text-xs md:text-sm text-muted-foreground leading-relaxed">
                  지정된 디스코드 채널에 알림을 띄우거나 유저 역할을 바로 부여합니다.
                </p>
              </div>
            </div>
          </div>
        </div>
      </section>

      {/* 뷰포트 3: 초저지연 비동기 인프라 명세 (1화면 1헤더 + 여백 중심 3행 직선 구획) */}
      <section
        id="architecture"
        data-viewport-section
        className="py-24 md:py-0 md:min-h-[calc(100dvh-3.5rem)] px-6 border-b border-border/70 flex flex-col justify-center"
      >
        <div className="max-w-5xl mx-auto w-full space-y-14 md:space-y-16">
          <div className="space-y-3 max-w-2xl" data-animate-item>
            <h2 className="text-2xl sm:text-4xl font-bold tracking-tight text-foreground text-balance">
              초저지연 비동기 인프라 명세
            </h2>
            <p className="text-sm md:text-base text-muted-foreground leading-relaxed">
              복잡한 설정 없이도 대규모 인원이 접속하는 서버를 거뜬히 지탱하는 핵심 구조입니다.
            </p>
          </div>

          <div className="divide-y divide-border border-y border-border">
            <div
              data-animate-item
              className="py-7 md:py-9 grid grid-cols-1 md:grid-cols-12 gap-3 md:gap-8 items-baseline"
            >
              <h3 className="md:col-span-4 font-semibold text-base md:text-lg text-foreground">
                WSS 단일 계층 게이트웨이
              </h3>
              <p className="md:col-span-8 text-sm md:text-base text-muted-foreground leading-relaxed">
                중간 중계 서버를 거치지 않고 플러그인과 관제 서버가 웹소켓으로 직접 소통하여 전송 지연을 줄입니다.
              </p>
            </div>

            <div
              data-animate-item
              className="py-7 md:py-9 grid grid-cols-1 md:grid-cols-12 gap-3 md:gap-8 items-baseline"
            >
              <h3 className="md:col-span-4 font-semibold text-base md:text-lg text-foreground">
                LMAX Disruptor 인메모리 링버퍼
              </h3>
              <p className="md:col-span-8 text-sm md:text-base text-muted-foreground leading-relaxed">
                초당 수천 건의 블록 파괴와 전투 기록이 쏟아져도 메인 스레드를 기다리게 하지 않고 메모리에서 즉시 처리합니다.
              </p>
            </div>

            <div
              data-animate-item
              className="py-7 md:py-9 grid grid-cols-1 md:grid-cols-12 gap-3 md:gap-8 items-baseline"
            >
              <h3 className="md:col-span-4 font-semibold text-base md:text-lg text-foreground">
                디스코드 권한 실시간 동기화
              </h3>
              <p className="md:col-span-8 text-sm md:text-base text-muted-foreground leading-relaxed">
                번거로운 명령어 입력 없이 인게임 달성 조건에 맞춰 디스코드 역할과 칭호를 실시간으로 일치시킵니다.
              </p>
            </div>
          </div>
        </div>
      </section>

      {/* 뷰포트 4: 기존 연동 방식과의 기술적 격차 (1화면 1헤더 + 모바일 반응형 대응 직선 표) */}
      <section
        id="comparison"
        data-viewport-section
        className="py-24 md:py-0 md:min-h-[calc(100dvh-3.5rem)] px-6 border-b border-border/70 flex flex-col justify-center"
      >
        <div className="max-w-5xl mx-auto w-full space-y-14 md:space-y-16">
          <div className="space-y-3 max-w-2xl" data-animate-item>
            <h2 className="text-2xl sm:text-4xl font-bold tracking-tight text-foreground text-balance">
              기존 연동 방식과의 기술적 격차
            </h2>
            <p className="text-sm md:text-base text-muted-foreground leading-relaxed">
              일반 웹훅 플러그인과 RuBeacon의 실제 서버 부하 차이입니다.
            </p>
          </div>

          <div className="border-t border-b border-border divide-y divide-border text-sm">
            <div className="hidden sm:grid py-4 grid-cols-12 text-muted-foreground text-xs font-medium">
              <div className="col-span-4">비교 항목</div>
              <div className="col-span-4 text-primary font-semibold">RuBeacon</div>
              <div className="col-span-4">기존 웹훅 플러그인</div>
            </div>

            <div
              data-animate-item
              className="py-5 grid grid-cols-1 sm:grid-cols-12 gap-2 sm:gap-4 items-baseline"
            >
              <div className="sm:col-span-4 font-medium text-foreground">
                마인크래프트 메인 틱 점유 시간
              </div>
              <div className="sm:col-span-4 font-mono font-bold text-primary">
                0.00ms (완전 비동기)
              </div>
              <div className="sm:col-span-4 text-xs sm:text-sm text-muted-foreground">
                15 - 50ms (알림 전송 중 서버 멈칫 현상)
              </div>
            </div>

            <div
              data-animate-item
              className="py-5 grid grid-cols-1 sm:grid-cols-12 gap-2 sm:gap-4 items-baseline"
            >
              <div className="sm:col-span-4 font-medium text-foreground">이벤트 처리 방식</div>
              <div className="sm:col-span-4 font-semibold text-foreground">
                초고속 메모리 대기열 (LMAX Disruptor)
              </div>
              <div className="sm:col-span-4 text-xs sm:text-sm text-muted-foreground">
                단순 웹 요청 대기 (응답이 늦으면 서버 렉 발생)
              </div>
            </div>

            <div
              data-animate-item
              className="py-5 grid grid-cols-1 sm:grid-cols-12 gap-2 sm:gap-4 items-baseline"
            >
              <div className="sm:col-span-4 font-medium text-foreground">통신 연결 구조</div>
              <div className="sm:col-span-4 font-semibold text-foreground">
                상시 열린 전용 통로 (WSS 단일 연결)
              </div>
              <div className="sm:col-span-4 text-xs sm:text-sm text-muted-foreground">
                사건마다 매번 새 연결 생성
              </div>
            </div>

            <div
              data-animate-item
              className="py-5 grid grid-cols-1 sm:grid-cols-12 gap-2 sm:gap-4 items-baseline"
            >
              <div className="sm:col-span-4 font-medium text-foreground">대량 이벤트 발생 시</div>
              <div className="sm:col-span-4 font-semibold text-foreground">
                순서대로 안전 보관 후 순차 전송
              </div>
              <div className="sm:col-span-4 text-xs sm:text-sm text-muted-foreground">
                알림 누락 및 서버 TPS 급락
              </div>
            </div>
          </div>
        </div>
      </section>

      {/* 뷰포트 5: 빠른 3단계 연동 안내 & GitHub 릴리스 다운로드 */}
      <section
        id="setup"
        data-viewport-section
        className="py-24 md:py-0 md:min-h-[calc(100dvh-3.5rem)] px-6 flex flex-col justify-between"
      >
        <div className="hidden md:block" />

        <div className="max-w-5xl mx-auto w-full space-y-16 md:space-y-20 my-auto">
          <div className="space-y-12">
            <div className="space-y-3 max-w-xl" data-animate-item>
              <h2 className="text-2xl sm:text-4xl font-bold tracking-tight text-foreground text-balance">
                빠른 3단계 연동 안내
              </h2>
              <p className="text-sm md:text-base text-muted-foreground">
                복잡한 코드 작업 없이 3분 만에 서버와 디스코드를 연결합니다.
              </p>
            </div>

            <div className="grid grid-cols-1 md:grid-cols-3 gap-10">
              <div data-animate-item className="space-y-2.5 border-t border-border pt-5">
                <div className="font-mono text-xs font-bold text-primary">01</div>
                <h3 className="font-semibold text-base text-foreground">디스코드 봇 초대</h3>
                <p className="text-xs md:text-sm text-muted-foreground leading-relaxed">
                  연동할 디스코드 서버에 전용 봇을 초대해 알림 채널 권한을 허용합니다.
                </p>
              </div>

              <div data-animate-item className="space-y-2.5 border-t border-border pt-5">
                <div className="font-mono text-xs font-bold text-primary">02</div>
                <h3 className="font-semibold text-base text-foreground">인스턴스 토큰 발급</h3>
                <p className="text-xs md:text-sm text-muted-foreground leading-relaxed">
                  웹 콘솔에서 발급받은 인증 토큰을 마인크래프트 플러그인 설정에 붙여넣습니다.
                </p>
              </div>

              <div data-animate-item className="space-y-2.5 border-t border-border pt-5">
                <div className="font-mono text-xs font-bold text-primary">03</div>
                <h3 className="font-semibold text-base text-foreground">실시간 하트비트 확인</h3>
                <p className="text-xs md:text-sm text-muted-foreground leading-relaxed">
                  서버를 켜는 즉시 연결 상태가 온라인으로 바뀌며 실시간 연동이 시작됩니다.
                </p>
              </div>
            </div>
          </div>

          {/* 하단 단일 액션 영역 (GitHub 최신 릴리스 직결 다운로드 & 콘솔 진입) */}
          <div
            data-animate-item
            className="pt-10 border-t border-border flex flex-col md:flex-row items-start md:items-center justify-between gap-8"
          >
            <div className="space-y-2 max-w-xl">
              <h3 className="text-xl md:text-2xl font-bold tracking-tight text-foreground text-balance">
                서버 렉 없는 디스코드 커뮤니티, 지금 즉시 연결하세요.
              </h3>
              <p className="text-xs md:text-sm text-muted-foreground leading-relaxed">
                GitHub 공식 릴리스에서 최신 PaperMC/Purpur 플러그인({release.tagName})을 바로 내려받을 수 있습니다.
              </p>
            </div>

            <div className="flex flex-wrap items-center gap-3 shrink-0">
              <a href={release.downloadUrl} target="_blank" rel="noopener noreferrer">
                <Button
                  variant="outline"
                  size="lg"
                  className="h-11 px-5 text-xs md:text-sm border-border hover:bg-muted/40 font-medium gap-2"
                >
                  <Download className="w-4 h-4" />
                  플러그인 JAR 다운로드 ({release.tagName})
                </Button>
              </a>
              <Link href="/app">
                <Button
                  size="lg"
                  className="h-11 px-6 text-xs md:text-sm bg-primary hover:bg-primary/90 text-primary-foreground font-semibold gap-2"
                >
                  루비콘 콘솔 열기
                  <ArrowRight className="w-4 h-4" />
                </Button>
              </Link>
            </div>
          </div>
        </div>

        {/* 미니멀 푸터 */}
        <footer className="max-w-5xl mx-auto w-full pt-12 pb-6 border-t border-border/50 text-xs text-muted-foreground flex flex-col sm:flex-row items-center justify-between gap-4">
          <div>RuBeacon &copy; 2026. 초저지연 마인크래프트-디스코드 브릿지.</div>
          <div className="flex gap-6 text-[11px]">
            <Link href="#pipeline" className="hover:text-foreground transition-colors">
              작동 방식
            </Link>
            <Link href="#architecture" className="hover:text-foreground transition-colors">
              핵심 기술
            </Link>
            <Link href="#comparison" className="hover:text-foreground transition-colors">
              성능 비교
            </Link>
            <Link href="/admin" className="hover:text-foreground transition-colors">
              관리 콘솔
            </Link>
          </div>
        </footer>
      </section>
    </div>
  );
}
