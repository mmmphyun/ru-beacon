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

const COS30 = Math.cos(Math.PI / 6);
const SIN30 = Math.sin(Math.PI / 6);

function isoPoint(x: number, y: number, z: number, angleDeg: number, cx: number, cy: number) {
  const rad = (angleDeg * Math.PI) / 180;
  const cosA = Math.cos(rad);
  const sinA = Math.sin(rad);
  const rx = x * cosA - z * sinA;
  const rz = x * sinA + z * cosA;
  return [cx + (rx - rz) * COS30, cy + (rx + rz) * SIN30 - y] as const;
}

/**
 * 여백에 배치할 정육면체(1:1:1) 3D 와이어프레임 경로 생성 헬퍼
 */
function buildWireCube(size: number, angleDeg: number, cx: number, cy: number) {
  const h = size / 2;
  const top = [
    isoPoint(-h, h, -h, angleDeg, cx, cy),
    isoPoint(h, h, -h, angleDeg, cx, cy),
    isoPoint(h, h, h, angleDeg, cx, cy),
    isoPoint(-h, h, h, angleDeg, cx, cy),
  ];
  const bot = [
    isoPoint(-h, -h, -h, angleDeg, cx, cy),
    isoPoint(h, -h, -h, angleDeg, cx, cy),
    isoPoint(h, -h, h, angleDeg, cx, cy),
    isoPoint(-h, -h, h, angleDeg, cx, cy),
  ];

  const lines: string[] = [];
  for (let i = 0; i < 4; i++) {
    const n = (i + 1) % 4;
    lines.push(`M${top[i][0].toFixed(1)},${top[i][1].toFixed(1)} L${top[n][0].toFixed(1)},${top[n][1].toFixed(1)}`);
    lines.push(`M${bot[i][0].toFixed(1)},${bot[i][1].toFixed(1)} L${bot[n][0].toFixed(1)},${bot[n][1].toFixed(1)}`);
    lines.push(`M${top[i][0].toFixed(1)},${top[i][1].toFixed(1)} L${bot[i][0].toFixed(1)},${bot[i][1].toFixed(1)}`);
  }
  const topPoly = top.map((p) => `${p[0].toFixed(1)},${p[1].toFixed(1)}`).join(" ");
  return { path: lines.join(" "), topPoly };
}

/**
 * LMAX Disruptor 링버퍼를 상징하는 3D 팔각 링(Torus/Ring) 와이어프레임 경로 생성 헬퍼
 */
function buildWireRingBuffer(angleDeg: number, cx: number, cy: number) {
  const segments = 12;
  const outerR = 115;
  const innerR = 62;
  const height = 28;
  const lines: string[] = [];

  for (let i = 0; i < segments; i++) {
    const a1 = (i / segments) * Math.PI * 2;
    const a2 = ((i + 1) / segments) * Math.PI * 2;

    const ox1 = Math.cos(a1) * outerR;
    const oz1 = Math.sin(a1) * outerR;
    const ox2 = Math.cos(a2) * outerR;
    const oz2 = Math.sin(a2) * outerR;

    const ix1 = Math.cos(a1) * innerR;
    const iz1 = Math.sin(a1) * innerR;
    const ix2 = Math.cos(a2) * innerR;
    const iz2 = Math.sin(a2) * innerR;

    const oTop1 = isoPoint(ox1, height / 2, oz1, angleDeg, cx, cy);
    const oTop2 = isoPoint(ox2, height / 2, oz2, angleDeg, cx, cy);
    const oBot1 = isoPoint(ox1, -height / 2, oz1, angleDeg, cx, cy);
    const oBot2 = isoPoint(ox2, -height / 2, oz2, angleDeg, cx, cy);

    const iTop1 = isoPoint(ix1, height / 2, iz1, angleDeg, cx, cy);
    const iTop2 = isoPoint(ix2, height / 2, iz2, angleDeg, cx, cy);
    const iBot1 = isoPoint(ix1, -height / 2, iz1, angleDeg, cx, cy);

    // 외곽 상·하단 링 및 수직 기둥
    lines.push(`M${oTop1[0].toFixed(1)},${oTop1[1].toFixed(1)} L${oTop2[0].toFixed(1)},${oTop2[1].toFixed(1)}`);
    lines.push(`M${oBot1[0].toFixed(1)},${oBot1[1].toFixed(1)} L${oBot2[0].toFixed(1)},${oBot2[1].toFixed(1)}`);
    lines.push(`M${oTop1[0].toFixed(1)},${oTop1[1].toFixed(1)} L${oBot1[0].toFixed(1)},${oBot1[1].toFixed(1)}`);

    // 내측 상단 링 및 방사형 슬롯 구분선 (링버퍼 시퀀스 슬롯 표현)
    lines.push(`M${iTop1[0].toFixed(1)},${iTop1[1].toFixed(1)} L${iTop2[0].toFixed(1)},${iTop2[1].toFixed(1)}`);
    lines.push(`M${oTop1[0].toFixed(1)},${oTop1[1].toFixed(1)} L${iTop1[0].toFixed(1)},${iTop1[1].toFixed(1)}`);
    lines.push(`M${iTop1[0].toFixed(1)},${iTop1[1].toFixed(1)} L${iBot1[0].toFixed(1)},${iBot1[1].toFixed(1)}`);
  }

  return lines.join(" ");
}

export default function LandingPage() {
  const [user, setUser] = useState<UserProfile | null>(null);
  const [modelAngle, setModelAngle] = useState(22);
  const release = useLatestRelease();
  const pageRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    fetchUserProfile().then((profile) => {
      if (profile && profile.tenants.length > 0) {
        setUser(profile);
      }
    });
  }, []);

  // 여백 내 3D 선 모델(링버퍼 토러스 & 큐브 노드)의 은은한 연속 회전 루프
  useEffect(() => {
    const prefersReduced =
      typeof window !== "undefined" &&
      window.matchMedia &&
      window.matchMedia("(prefers-reduced-motion: reduce)").matches;

    if (prefersReduced) return;

    const rotObj = { angle: 22 };
    const anim = anime({
      targets: rotObj,
      angle: 382,
      duration: 28000,
      easing: "linear",
      loop: true,
      update: () => {
        setModelAngle(rotObj.angle);
      },
    });

    return () => {
      anim.pause();
    };
  }, []);

  // 양방향 스크롤 감지: 내릴 때와 다시 올릴 때 모두 곡선 드로잉 및 요소 모션 재실행
  useEffect(() => {
    const prefersReduced =
      typeof window !== "undefined" &&
      window.matchMedia &&
      window.matchMedia("(prefers-reduced-motion: reduce)").matches;

    if (prefersReduced || typeof IntersectionObserver === "undefined") return;

    const root = pageRef.current;
    if (!root) return;

    const sections = root.querySelectorAll<HTMLElement>("[data-viewport-section]");

    const observer = new IntersectionObserver(
      (entries) => {
        entries.forEach((entry) => {
          const curves = entry.target.querySelectorAll("[data-animate-curve]");
          const items = entry.target.querySelectorAll("[data-animate-item]");
          const models = entry.target.querySelectorAll("[data-animate-model]");

          if (entry.isIntersecting) {
            anime.remove(curves);
            anime.remove(items);
            anime.remove(models);

            if (curves.length > 0) {
              anime({
                targets: curves,
                strokeDashoffset: [1400, 0],
                opacity: [0.1, 1],
                duration: 1150,
                easing: "easeOutQuart",
              });
            }

            if (models.length > 0) {
              anime({
                targets: models,
                scale: [0.84, 1],
                opacity: [0, 1],
                duration: 950,
                easing: "easeOutElastic(1, .65)",
              });
            }

            if (items.length > 0) {
              anime({
                targets: items,
                opacity: [0, 1],
                translateY: [34, 0],
                delay: anime.stagger(85),
                duration: 680,
                easing: "easeOutCubic",
              });
            }
          } else {
            // 화면 밖으로 벗어나면 다시 스크롤해 돌아왔을 때 재실행되도록 초기 상태 리셋
            anime.set(items, { opacity: 0.08, translateY: 34 });
            anime.set(curves, { strokeDashoffset: 1400, opacity: 0.1 });
          }
        });
      },
      { threshold: 0.18 }
    );

    sections.forEach((sec) => observer.observe(sec));
    return () => observer.disconnect();
  }, []);

  const heroCube1 = buildWireCube(46, modelAngle * 0.8, 120, 210);
  const heroCube2 = buildWireCube(64, -modelAngle * 0.6, 260, 145);
  const heroCube3 = buildWireCube(52, modelAngle * 1.1, 405, 85);

  const pipeCube1 = buildWireCube(44, modelAngle, 95, 195);
  const pipeCube2 = buildWireCube(44, -modelAngle, 310, 95);
  const pipeCube3 = buildWireCube(44, modelAngle, 560, 215);
  const pipeCube4 = buildWireCube(44, -modelAngle, 795, 110);

  const ringBufferPath = buildWireRingBuffer(modelAngle * 0.5, 230, 190);
  const ringCoreCube = buildWireCube(42, -modelAngle * 1.2, 230, 190);

  return (
    <div
      ref={pageRef}
      className="min-h-screen flex flex-col bg-background text-foreground selection:bg-primary selection:text-primary-foreground font-sans break-keep"
    >
      {/* 상단 고정 헤더 (기존 형태 유지) */}
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

      {/* 뷰포트 0: 정육면체 비콘 피라미드 + 심장박동 + 광선 축 90도 회전 Ru-by-Beacon 헤더 */}
      <BeaconStage />

      {/* 뷰포트 1: 비대칭 분할 (좌측 대담한 헤드라인 / 우측 여백 루비 기하학 곡선 & 3D 정육면체 트라이어드) */}
      <section
        id="hero"
        data-viewport-section
        className="relative py-24 md:py-0 md:min-h-[calc(100dvh-3.5rem)] px-6 border-b border-border/60 flex flex-col justify-between overflow-hidden"
      >
        <div className="hidden md:block" />

        <div className="max-w-6xl mx-auto w-full grid grid-cols-1 lg:grid-cols-12 gap-12 items-center my-auto">
          {/* 좌측 7열: 핵심 슬로건 및 CTA */}
          <div className="lg:col-span-7 space-y-6" data-animate-item>
            <div className="text-[11px] font-light tracking-[0.28em] text-primary uppercase">
              초저지연 마인크래프트-디스코드 브릿지
            </div>

            <h1 className="headline italic text-4xl sm:text-5xl md:text-6xl tracking-tight text-foreground leading-[1.12] text-balance">
              <em>서버 TPS 저하 없는 마인크래프트-디스코드 실시간 연동</em>
            </h1>

            <p className="text-sm sm:text-base font-light tracking-wide text-muted-foreground leading-relaxed max-w-xl">
              서버에 이벤트가 몰려도 렉이 걸리지 않습니다. 게임 속 사건을 디스코드 알림과 역할에 곧바로 반영합니다.
            </p>

            <div className="pt-3 flex flex-wrap items-center gap-3">
              <Link href="/app">
                <Button
                  size="lg"
                  className="h-11 px-6 text-xs bg-primary hover:bg-primary/90 text-primary-foreground font-semibold gap-2"
                >
                  루비콘 콘솔 시작하기
                  <ArrowRight className="w-4 h-4" />
                </Button>
              </Link>
              <a href={release.downloadUrl} target="_blank" rel="noopener noreferrer">
                <Button
                  variant="outline"
                  size="lg"
                  className="h-11 px-6 text-xs border-border hover:bg-muted/40 transition-colors gap-2"
                >
                  <Download className="w-4 h-4" />
                  플러그인 다운로드 ({release.tagName})
                </Button>
              </a>
            </div>
          </div>

          {/* 우측 5열 여백: 루비 기하학 베지어 곡선 + 회전하는 3D 정육면체 노드 모델 */}
          <div className="lg:col-span-5 flex items-center justify-center" data-animate-model>
            <svg
              viewBox="0 0 500 300"
              className="w-full max-w-[460px] h-auto overflow-visible"
              aria-hidden="true"
            >
              {/* 배경 기하학 보조 호 */}
              <path
                d="M 20,260 Q 240,280 460,40"
                fill="none"
                stroke="currentColor"
                strokeOpacity="0.1"
                strokeWidth="1"
                strokeDasharray="4 6"
              />
              {/* 메인 루비 기하학 곡선 */}
              <path
                data-animate-curve
                d="M 30,245 C 165,245 200,85 455,65"
                fill="none"
                stroke="#E11D48"
                strokeWidth="1.75"
                strokeDasharray="1400"
                strokeDashoffset="0"
              />
              {/* 3개의 3D 와이어프레임 정육면체 */}
              <g className="text-foreground">
                <polygon points={heroCube1.topPoly} fill="#E11D48" fillOpacity="0.12" />
                <path d={heroCube1.path} fill="none" stroke="currentColor" strokeOpacity="0.35" strokeWidth="1" />

                <polygon points={heroCube2.topPoly} fill="#E11D48" fillOpacity="0.22" />
                <path d={heroCube2.path} fill="none" stroke="#E11D48" strokeOpacity="0.75" strokeWidth="1.2" />

                <polygon points={heroCube3.topPoly} fill="#E11D48" fillOpacity="0.15" />
                <path d={heroCube3.path} fill="none" stroke="currentColor" strokeOpacity="0.45" strokeWidth="1" />
              </g>
            </svg>
          </div>
        </div>

        {/* 하단: 얇은 자간과 LevelUp 고정폭 숫자(.score)로만 구성된 미니멀 지표 */}
        <div className="max-w-6xl mx-auto w-full pt-8 pb-10 border-t border-border/60 grid grid-cols-3 gap-6">
          <div data-animate-item>
            <div className="score headline italic text-2xl sm:text-4xl text-primary">
              <em>0.00ms</em>
            </div>
            <div className="mt-1 text-[11px] font-light tracking-[0.18em] text-muted-foreground uppercase">
              메인 틱 점유
            </div>
          </div>
          <div data-animate-item className="text-center">
            <div className="score headline italic text-2xl sm:text-4xl text-foreground">
              <em>0.08ms</em>
            </div>
            <div className="mt-1 text-[11px] font-light tracking-[0.18em] text-muted-foreground uppercase">
              링버퍼 큐잉
            </div>
          </div>
          <div data-animate-item className="text-right">
            <div className="score headline italic text-2xl sm:text-4xl text-foreground">
              <em>40ms</em>
            </div>
            <div className="mt-1 text-[11px] font-light tracking-[0.18em] text-muted-foreground uppercase">
              디스코드 도달
            </div>
          </div>
        </div>
      </section>

      {/* 뷰포트 2: 우측 정렬 헤더 + 중앙 여백을 가로지르는 루비 S-곡선과 4개 3D 정육면체 파이프라인 */}
      <section
        id="pipeline"
        data-viewport-section
        className="relative py-24 md:py-0 md:min-h-[calc(100dvh-3.5rem)] px-6 border-b border-border/60 flex flex-col justify-center overflow-hidden"
      >
        <div className="max-w-6xl mx-auto w-full space-y-12 md:space-y-16">
          {/* 우측 정렬 헤더로 이전 뷰포트와 시선 대칭 변화 */}
          <div className="ml-auto text-left md:text-right max-w-xl space-y-2" data-animate-item>
            <div className="text-[11px] font-light tracking-[0.28em] text-primary uppercase">
              Signal Trajectory
            </div>
            <h2 className="headline italic text-3xl sm:text-5xl tracking-tight text-foreground">
              <em>이벤트가 처리되는 실제 런타임 경로</em>
            </h2>
          </div>

          {/* 여백을 채우는 대형 루비 S-커브 + 4개 3D 정육면체 모델 */}
          <div className="relative w-full" data-animate-model>
            <svg
              viewBox="0 0 900 260"
              className="hidden md:block w-full h-auto overflow-visible"
              aria-hidden="true"
            >
              <path
                d="M 40,210 C 260,30 620,270 860,85"
                fill="none"
                stroke="currentColor"
                strokeOpacity="0.1"
                strokeWidth="1"
              />
              <path
                data-animate-curve
                d="M 40,195 C 260,15 620,255 860,70"
                fill="none"
                stroke="#E11D48"
                strokeWidth="2"
                strokeDasharray="1400"
                strokeDashoffset="0"
              />
              {[pipeCube1, pipeCube2, pipeCube3, pipeCube4].map((cube, idx) => (
                <g key={idx} className="text-foreground">
                  <polygon points={cube.topPoly} fill="#E11D48" fillOpacity={0.18 + idx * 0.06} />
                  <path
                    d={cube.path}
                    fill="none"
                    stroke={idx === 0 || idx === 3 ? "#E11D48" : "currentColor"}
                    strokeOpacity="0.7"
                    strokeWidth="1.2"
                  />
                </g>
              ))}
            </svg>

            {/* 텍스트는 핵심 키워드와 1줄만 남겨 여백 극대화 */}
            <div className="grid grid-cols-2 md:grid-cols-4 gap-8 pt-4">
              <div data-animate-item className="space-y-1.5">
                <div className="score text-xs text-primary font-bold">01 // 0.00ms (완전 비동기)</div>
                <h3 className="font-semibold text-base text-foreground">마인크래프트 서버</h3>
                <p className="text-xs font-light text-muted-foreground">
                  메인 틱 중단 없이 사건 즉시 분리
                </p>
              </div>

              <div data-animate-item className="space-y-1.5 md:pt-8">
                <div className="score text-xs text-muted-foreground">02 // 0.08ms Queue</div>
                <h3 className="font-semibold text-base text-foreground">LMAX Disruptor</h3>
                <p className="text-xs font-light text-muted-foreground">
                  수만 건의 이벤트를 무잠금 정렬
                </p>
              </div>

              <div data-animate-item className="space-y-1.5">
                <div className="score text-xs text-muted-foreground">03 // Single-Hop</div>
                <h3 className="font-semibold text-base text-foreground">WSS 게이트웨이 & Redis</h3>
                <p className="text-xs font-light text-muted-foreground">
                  상시 열린 웹소켓 통로로 직결 전송
                </p>
              </div>

              <div data-animate-item className="space-y-1.5 md:pt-8 md:text-right">
                <div className="score text-xs text-emerald-500 font-bold">04 // 42ms Ack</div>
                <h3 className="font-semibold text-base text-foreground">디스코드 봇 서비스</h3>
                <p className="text-xs font-light text-muted-foreground">
                  채널 알림 및 유저 역할 즉시 반영
                </p>
              </div>
            </div>
          </div>
        </div>
      </section>

      {/* 뷰포트 3: 좌측 3D 와이어프레임 링버퍼 모델 + 우측 간결한 3대 기술 명세 */}
      <section
        id="architecture"
        data-viewport-section
        className="relative py-24 md:py-0 md:min-h-[calc(100dvh-3.5rem)] px-6 border-b border-border/60 flex flex-col justify-center overflow-hidden"
      >
        <div className="max-w-6xl mx-auto w-full grid grid-cols-1 lg:grid-cols-12 gap-12 items-center">
          {/* 좌측 6열 여백: 회전하는 3D 링버퍼 토러스 모델 & 루비 궤도 곡선 */}
          <div className="lg:col-span-6 flex flex-col items-center lg:items-start" data-animate-model>
            <div className="space-y-2 mb-6" data-animate-item>
              <div className="text-[11px] font-light tracking-[0.28em] text-primary uppercase">
                Core Engine
              </div>
              <h2 className="headline italic text-3xl sm:text-5xl tracking-tight text-foreground">
                <em>초저지연 비동기 인프라 명세</em>
              </h2>
            </div>

            <svg
              viewBox="0 0 460 360"
              className="w-full max-w-[440px] h-auto overflow-visible"
              aria-hidden="true"
            >
              {/* 링버퍼를 감싸는 루비 타원 궤도 곡선 */}
              <path
                data-animate-curve
                d="M 25,240 C 90,60 370,60 435,140 C 380,310 110,320 25,240"
                fill="none"
                stroke="#E11D48"
                strokeOpacity="0.65"
                strokeWidth="1.5"
                strokeDasharray="1400"
                strokeDashoffset="0"
              />
              {/* 3D 팔각 링버퍼 와이어프레임 */}
              <path
                d={ringBufferPath}
                fill="none"
                stroke="currentColor"
                strokeOpacity="0.32"
                strokeWidth="0.95"
              />
              {/* 중심부 코어 큐브 */}
              <polygon points={ringCoreCube.topPoly} fill="#E11D48" fillOpacity="0.3" />
              <path
                d={ringCoreCube.path}
                fill="none"
                stroke="#E11D48"
                strokeOpacity="0.9"
                strokeWidth="1.3"
              />
            </svg>
          </div>

          {/* 우측 6열: 불필요한 사족을 덜어낸 3대 명세 */}
          <div className="lg:col-span-6 divide-y divide-border/70 border-y border-border/70">
            <div data-animate-item className="py-7 space-y-1.5">
              <div className="text-[11px] font-light tracking-[0.24em] text-primary uppercase">
                01 / Direct Socket
              </div>
              <h3 className="headline text-xl text-foreground">WSS 단일 계층 게이트웨이</h3>
              <p className="text-sm font-light text-muted-foreground">
                중계 서버 없이 플러그인과 관제 서버가 직접 연결됩니다.
              </p>
            </div>

            <div data-animate-item className="py-7 space-y-1.5 lg:pl-8">
              <div className="text-[11px] font-light tracking-[0.24em] text-primary uppercase">
                02 / Lock-Free Ring
              </div>
              <h3 className="headline text-xl text-foreground">LMAX Disruptor 인메모리 링버퍼</h3>
              <p className="text-sm font-light text-muted-foreground">
                대규모 전투·채굴 이벤트도 대기 시간 없이 메모리에서 즉시 소화합니다.
              </p>
            </div>

            <div data-animate-item className="py-7 space-y-1.5 lg:pl-16">
              <div className="text-[11px] font-light tracking-[0.24em] text-primary uppercase">
                03 / Instant Role Sync
              </div>
              <h3 className="headline text-xl text-foreground">디스코드 권한 실시간 동기화</h3>
              <p className="text-sm font-light text-muted-foreground">
                인게임 달성 조건을 감지하는 즉시 디스코드 역할과 칭호를 바꿉니다.
              </p>
            </div>
          </div>
        </div>
      </section>

      {/* 뷰포트 4: 중앙 대칭 레이아웃 + 대형 루비 호(Arc) + 격차 비교 및 3단계 시작 */}
      <section
        id="comparison"
        data-viewport-section
        className="relative py-24 md:py-0 md:min-h-[calc(100dvh-3.5rem)] px-6 flex flex-col justify-between overflow-hidden"
      >
        {/* 배경 여백을 가로지르는 대형 루비 포물선 아크 */}
        <svg
          viewBox="0 0 1200 420"
          className="pointer-events-none absolute inset-x-0 top-10 w-full h-auto opacity-80"
          aria-hidden="true"
        >
          <path
            data-animate-curve
            d="M -40,380 Q 600,20 1240,380"
            fill="none"
            stroke="#E11D48"
            strokeOpacity="0.35"
            strokeWidth="1.5"
            strokeDasharray="1400"
            strokeDashoffset="0"
          />
          <path
            d="M 120,410 Q 600,110 1080,410"
            fill="none"
            stroke="currentColor"
            strokeOpacity="0.08"
            strokeWidth="1"
          />
        </svg>

        <div className="hidden md:block" />

        <div className="relative z-10 max-w-5xl mx-auto w-full space-y-14 my-auto">
          {/* 상단: 중앙 정렬 기술 격차 요약 */}
          <div className="text-center space-y-6">
            <div className="space-y-2" data-animate-item>
              <div className="text-[11px] font-light tracking-[0.28em] text-primary uppercase">
                Benchmark Contrast
              </div>
              <h2 className="headline italic text-3xl sm:text-5xl tracking-tight text-foreground">
                <em>기존 연동 방식과의 기술적 격차</em>
              </h2>
            </div>

            <div
              data-animate-item
              className="max-w-3xl mx-auto grid grid-cols-1 sm:grid-cols-3 border-y border-border/70 py-5 gap-4 items-center text-sm"
            >
              <div className="font-light tracking-wider text-muted-foreground">
                마인크래프트 메인 틱 점유 시간
              </div>
              <div className="score headline italic text-xl sm:text-2xl text-primary">
                <em>0.00ms (완전 비동기)</em>
              </div>
              <div className="text-xs font-light text-muted-foreground line-through opacity-65">
                기존 웹훅 15 - 50ms 렉 유발
              </div>
            </div>
          </div>

          {/* 중단: 빠른 3단계 연동 (한 눈에 들어오는 심플 3열) */}
          <div id="setup" className="space-y-6">
            <div className="text-center" data-animate-item>
              <span className="text-[11px] font-light tracking-[0.28em] text-muted-foreground uppercase">
                빠른 3단계 연동 안내
              </span>
            </div>

            <div className="grid grid-cols-1 md:grid-cols-3 gap-6 text-center">
              <div data-animate-item className="space-y-1 pt-4 border-t border-border/60">
                <div className="score text-xs text-primary font-bold">01</div>
                <h3 className="font-semibold text-sm text-foreground">디스코드 봇 초대</h3>
                <p className="text-xs font-light text-muted-foreground">전용 봇을 서버에 추가</p>
              </div>
              <div data-animate-item className="space-y-1 pt-4 border-t border-border/60">
                <div className="score text-xs text-primary font-bold">02</div>
                <h3 className="font-semibold text-sm text-foreground">인스턴스 토큰 발급</h3>
                <p className="text-xs font-light text-muted-foreground">플러그인 설정에 토큰 등록</p>
              </div>
              <div data-animate-item className="space-y-1 pt-4 border-t border-border/60">
                <div className="score text-xs text-primary font-bold">03</div>
                <h3 className="font-semibold text-sm text-foreground">실시간 하트비트 확인</h3>
                <p className="text-xs font-light text-muted-foreground">서버 가동 즉시 온라인 연결</p>
              </div>
            </div>
          </div>

          {/* 하단: 중앙 집중형 최종 CTA & GitHub 최신 릴리스 다운로드 */}
          <div data-animate-item className="text-center space-y-5 pt-4">
            <h3 className="headline italic text-2xl sm:text-3xl text-foreground">
              <em>서버 렉 없는 디스코드 커뮤니티, 지금 즉시 연결하세요.</em>
            </h3>

            <div className="flex flex-wrap items-center justify-center gap-3">
              <a href={release.downloadUrl} target="_blank" rel="noopener noreferrer">
                <Button
                  variant="outline"
                  size="lg"
                  className="h-11 px-6 text-xs border-border hover:bg-muted/40 font-medium gap-2"
                >
                  <Download className="w-4 h-4" />
                  플러그인 JAR 다운로드 ({release.tagName})
                </Button>
              </a>
              <Link href="/app">
                <Button
                  size="lg"
                  className="h-11 px-7 text-xs bg-primary hover:bg-primary/90 text-primary-foreground font-semibold gap-2"
                >
                  루비콘 콘솔 열기
                  <ArrowRight className="w-4 h-4" />
                </Button>
              </Link>
            </div>
          </div>
        </div>

        {/* 미니멀 푸터 */}
        <footer className="relative z-10 max-w-6xl mx-auto w-full pt-8 pb-6 border-t border-border/50 text-[11px] font-light tracking-widest text-muted-foreground flex flex-col sm:flex-row items-center justify-between gap-4">
          <div>RUBEACON &copy; 2026</div>
          <div className="flex gap-6">
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
