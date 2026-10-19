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
import { RuBeaconLogo } from "@/components/brand/RuBeaconLogo";

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
 * 뷰포트 1 전용: 요동치는 3D 인게임 이벤트 파형(Wave Grid) vs 흔들림 없는 20.0 TPS 수평 기준면 생성
 */
function buildTpsWaveModel(phaseRad: number, cx: number, cy: number) {
  const cols = 7;
  const rows = 5;
  const step = 28;
  const waveLines: string[] = [];

  const getWaveY = (gx: number, gz: number) => {
    const d = Math.sqrt(gx * gx + gz * gz);
    return (
      Math.sin(gx * 0.9 + phaseRad * 2.2) *
        Math.cos(gz * 0.9 - phaseRad * 1.6) *
        22 +
      Math.sin(d * 1.2 - phaseRad * 3) * 10 -
      38
    );
  };

  for (let r = 0; r < rows; r++) {
    const gz = (r - (rows - 1) / 2) * step;
    const pts: string[] = [];
    for (let c = 0; c < cols; c++) {
      const gx = (c - (cols - 1) / 2) * step;
      const wy = getWaveY(c, r);
      const [sx, sy] = isoPoint(gx, wy, gz, 18, cx, cy);
      pts.push(`${c === 0 ? "M" : "L"}${sx.toFixed(1)},${sy.toFixed(1)}`);
    }
    waveLines.push(pts.join(" "));
  }

  for (let c = 0; c < cols; c++) {
    const gx = (c - (cols - 1) / 2) * step;
    const pts: string[] = [];
    for (let r = 0; r < rows; r++) {
      const gz = (r - (rows - 1) / 2) * step;
      const wy = getWaveY(c, r);
      const [sx, sy] = isoPoint(gx, wy, gz, 18, cx, cy);
      pts.push(`${r === 0 ? "M" : "L"}${sx.toFixed(1)},${sy.toFixed(1)}`);
    }
    waveLines.push(pts.join(" "));
  }

  const halfW = ((cols - 1) * step) / 2;
  const halfD = ((rows - 1) * step) / 2;
  const flatY = 32;
  const fCorners = [
    isoPoint(-halfW, flatY, -halfD, 18, cx, cy),
    isoPoint(halfW, flatY, -halfD, 18, cx, cy),
    isoPoint(halfW, flatY, halfD, 18, cx, cy),
    isoPoint(-halfW, flatY, halfD, 18, cx, cy),
  ];
  const flatPolygon = fCorners.map((p) => `${p[0].toFixed(1)},${p[1].toFixed(1)}`).join(" ");
  const flatLaserStart = isoPoint(-halfW - 24, flatY, 0, 18, cx, cy);
  const flatLaserEnd = isoPoint(halfW + 24, flatY, 0, 18, cx, cy);

  return {
    wavePath: waveLines.join(" "),
    flatPolygon,
    laserLine: `M${flatLaserStart[0].toFixed(1)},${flatLaserStart[1].toFixed(1)} L${flatLaserEnd[0].toFixed(1)},${flatLaserEnd[1].toFixed(1)}`,
    laserEnd: flatLaserEnd,
  };
}

/**
 * 뷰포트 2 노드 01: 마인크래프트 서버 상징 3D 정육면체(Cube)
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
 * 뷰포트 2 노드 02: LMAX Disruptor 상징 소형 3D 동심원 링(Ring)
 */
function buildMiniRing(radius: number, angleDeg: number, cx: number, cy: number) {
  const segs = 8;
  const inner = radius * 0.52;
  const lines: string[] = [];
  for (let i = 0; i < segs; i++) {
    const a1 = (i / segs) * Math.PI * 2;
    const a2 = ((i + 1) / segs) * Math.PI * 2;
    const o1 = isoPoint(Math.cos(a1) * radius, 0, Math.sin(a1) * radius, angleDeg, cx, cy);
    const o2 = isoPoint(Math.cos(a2) * radius, 0, Math.sin(a2) * radius, angleDeg, cx, cy);
    const i1 = isoPoint(Math.cos(a1) * inner, 0, Math.sin(a1) * inner, angleDeg, cx, cy);
    const i2 = isoPoint(Math.cos(a2) * inner, 0, Math.sin(a2) * inner, angleDeg, cx, cy);
    lines.push(`M${o1[0].toFixed(1)},${o1[1].toFixed(1)} L${o2[0].toFixed(1)},${o2[1].toFixed(1)}`);
    lines.push(`M${i1[0].toFixed(1)},${i1[1].toFixed(1)} L${i2[0].toFixed(1)},${i2[1].toFixed(1)}`);
    lines.push(`M${o1[0].toFixed(1)},${o1[1].toFixed(1)} L${i1[0].toFixed(1)},${i1[1].toFixed(1)}`);
  }
  return lines.join(" ");
}

/**
 * 뷰포트 2 노드 03: WSS 게이트웨이 상징 3D 팔면체 다이아몬드(Octahedron)
 */
function buildWireOctahedron(radius: number, angleDeg: number, cx: number, cy: number) {
  const topApex = isoPoint(0, radius * 1.15, 0, angleDeg, cx, cy);
  const botApex = isoPoint(0, -radius * 1.15, 0, angleDeg, cx, cy);
  const mid = [
    isoPoint(-radius, 0, 0, angleDeg, cx, cy),
    isoPoint(0, 0, -radius, angleDeg, cx, cy),
    isoPoint(radius, 0, 0, angleDeg, cx, cy),
    isoPoint(0, 0, radius, angleDeg, cx, cy),
  ];
  const lines: string[] = [];
  for (let i = 0; i < 4; i++) {
    const n = (i + 1) % 4;
    lines.push(`M${mid[i][0].toFixed(1)},${mid[i][1].toFixed(1)} L${mid[n][0].toFixed(1)},${mid[n][1].toFixed(1)}`);
    lines.push(`M${topApex[0].toFixed(1)},${topApex[1].toFixed(1)} L${mid[i][0].toFixed(1)},${mid[i][1].toFixed(1)}`);
    lines.push(`M${botApex[0].toFixed(1)},${botApex[1].toFixed(1)} L${mid[i][0].toFixed(1)},${mid[i][1].toFixed(1)}`);
  }
  const midPoly = mid.map((p) => `${p[0].toFixed(1)},${p[1].toFixed(1)}`).join(" ");
  return { path: lines.join(" "), midPoly };
}

/**
 * 뷰포트 3 전용: LMAX Disruptor 12슬롯 대형 3D 링버퍼 토러스 모델
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

    lines.push(`M${oTop1[0].toFixed(1)},${oTop1[1].toFixed(1)} L${oTop2[0].toFixed(1)},${oTop2[1].toFixed(1)}`);
    lines.push(`M${oBot1[0].toFixed(1)},${oBot1[1].toFixed(1)} L${oBot2[0].toFixed(1)},${oBot2[1].toFixed(1)}`);
    lines.push(`M${oTop1[0].toFixed(1)},${oTop1[1].toFixed(1)} L${oBot1[0].toFixed(1)},${oBot1[1].toFixed(1)}`);

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
      duration: 24000,
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
          const fastGauge = entry.target.querySelectorAll("[data-gauge-fast]");
          const slowGauge = entry.target.querySelectorAll("[data-gauge-slow]");

          if (entry.isIntersecting) {
            anime.remove(curves);
            anime.remove(items);
            anime.remove(models);
            anime.remove(fastGauge);
            anime.remove(slowGauge);

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
                scale: [0.86, 1],
                opacity: [0, 1],
                duration: 950,
                easing: "easeOutElastic(1, .65)",
              });
            }

            if (items.length > 0) {
              anime({
                targets: items,
                opacity: [0, 1],
                translateY: [32, 0],
                delay: anime.stagger(80),
                duration: 650,
                easing: "easeOutCubic",
              });
            }

            if (fastGauge.length > 0) {
              anime({
                targets: fastGauge,
                scaleX: [0, 1],
                duration: 280,
                easing: "easeOutExpo",
              });
            }

            if (slowGauge.length > 0) {
              anime({
                targets: slowGauge,
                scaleX: [0, 0.38],
                duration: 1800,
                easing: "steps(6)",
              });
            }
          } else {
            anime.set(items, { opacity: 0.08, translateY: 32 });
            anime.set(curves, { strokeDashoffset: 1400, opacity: 0.1 });
            anime.set(fastGauge, { scaleX: 0 });
            anime.set(slowGauge, { scaleX: 0 });
          }
        });
      },
      { threshold: 0.18 }
    );

    sections.forEach((sec) => observer.observe(sec));
    return () => observer.disconnect();
  }, []);

  const phaseRad = (modelAngle * Math.PI) / 45;
  const tpsWaveModel = buildTpsWaveModel(phaseRad, 250, 165);

  const node1Cube = buildWireCube(44, modelAngle, 95, 195);
  const node2Ring = buildMiniRing(30, -modelAngle * 1.4, 310, 95);
  const node3Octa = buildWireOctahedron(28, modelAngle * 1.1, 560, 215);
  const pulseRadius = 14 + (Math.sin(phaseRad * 2) * 0.5 + 0.5) * 14;

  const ringBufferPath = buildWireRingBuffer(modelAngle * 0.5, 230, 190);
  const ringCoreOcta = buildWireOctahedron(32, -modelAngle * 1.2, 230, 190);

  const targetOcta = buildWireOctahedron(20, modelAngle, 120, 88);

  return (
    <div
      ref={pageRef}
      className="min-h-screen flex flex-col bg-background text-foreground selection:bg-primary selection:text-primary-foreground font-sans break-keep"
    >
      {/* 상단 고정 헤더: 블러 글래스모피즘 + 본문 그리드 일치(max-w-6xl) */}
      <header className="border-b border-border/30 bg-background/70 backdrop-blur-md sticky top-0 z-50 transition-colors">
        <div className="max-w-6xl mx-auto px-6 h-14 flex items-center justify-between">
          <div className="flex items-center gap-3">
            <RuBeaconLogo size={24} />

            <div className="flex items-center gap-2">
              <span className="headline text-base sm:text-lg tracking-tight text-foreground select-none">
                Ru
                <span className="opacity-45 line-through decoration-primary decoration-[1.8px] -mx-[0.03em]">
                  by
                </span>
                Beacon
              </span>
              <span className="text-xs font-mono text-muted-foreground border-l border-border/80 pl-2">
                {release.tagName}
              </span>
            </div>
          </div>

          <div className="flex items-center gap-6">
            <nav className="hidden sm:flex items-center gap-6">
              <Link
                href="#pipeline"
                className="relative py-1 text-sm font-medium text-muted-foreground hover:text-foreground transition-colors after:absolute after:bottom-0 after:left-0 after:right-0 after:h-[2px] after:bg-primary after:scale-x-0 hover:after:scale-x-100 after:transition-transform after:duration-200 after:origin-center"
              >
                작동 방식
              </Link>
              <Link
                href="#architecture"
                className="relative py-1 text-sm font-medium text-muted-foreground hover:text-foreground transition-colors after:absolute after:bottom-0 after:left-0 after:right-0 after:h-[2px] after:bg-primary after:scale-x-0 hover:after:scale-x-100 after:transition-transform after:duration-200 after:origin-center"
              >
                핵심 기술
              </Link>
              <Link
                href="#comparison"
                className="relative py-1 text-sm font-medium text-muted-foreground hover:text-foreground transition-colors after:absolute after:bottom-0 after:left-0 after:right-0 after:h-[2px] after:bg-primary after:scale-x-0 hover:after:scale-x-100 after:transition-transform after:duration-200 after:origin-center"
              >
                성능 비교
              </Link>
              <Link
                href="#setup"
                className="relative py-1 text-sm font-medium text-muted-foreground hover:text-foreground transition-colors after:absolute after:bottom-0 after:left-0 after:right-0 after:h-[2px] after:bg-primary after:scale-x-0 hover:after:scale-x-100 after:transition-transform after:duration-200 after:origin-center"
              >
                시작하기
              </Link>
            </nav>

            <div className="flex items-center gap-3">
              <ThemeToggle />
              <Link href="/app">
                <Button
                  size="sm"
                  className="h-9 px-4 text-xs sm:text-sm bg-primary hover:bg-primary/90 text-primary-foreground font-semibold gap-1.5 shadow-sm transition-all"
                >
                  {user ? "대시보드 이동" : "콘솔 시작하기"}
                  <ArrowRight className="w-3.5 h-3.5" />
                </Button>
              </Link>
            </div>
          </div>
        </div>
      </header>

      {/* 뷰포트 0: 정육면체 비콘 피라미드 + 심장박동 + 광선 축 90도 회전 Ru-by-Beacon 헤더 */}
      <BeaconStage />

      {/* 뷰포트 1: 좌측 헤드라인 / 우측 [3D 이벤트 파형 vs 20.0 TPS 수평 레이저] + 루비-샌드 그라디언트 */}
      <section
        id="hero"
        data-viewport-section
        className="relative py-24 md:py-0 md:min-h-[calc(100dvh-3.5rem)] px-6 border-b border-border/60 flex flex-col justify-between overflow-hidden"
      >
        {/* 3D 파형 모델 주변 루비-샌드(Ruby-Sand) 앰비언트 그라디언트 */}
        <div
          className="pointer-events-none absolute inset-0"
          style={{
            background:
              "radial-gradient(circle at 72% 46%, rgba(212, 17, 83, 0.16) 0%, rgba(206, 178, 138, 0.14) 36%, transparent 70%)",
          }}
        />

        <div className="hidden md:block" />

        <div className="relative z-10 max-w-6xl mx-auto w-full grid grid-cols-1 lg:grid-cols-12 gap-12 items-center my-auto">
          <div className="lg:col-span-7 space-y-6" data-animate-item>
            <div className="text-xs sm:text-sm font-medium tracking-[0.18em] text-primary uppercase">
              초저지연 마인크래프트-디스코드 브릿지
            </div>

            <h1 className="text-3xl sm:text-5xl md:text-[3.5rem] font-bold tracking-tight text-foreground leading-[1.15] text-balance">
              서버 TPS 저하 없는 마인크래프트-디스코드 실시간 연동
            </h1>

            <p className="text-base sm:text-lg font-normal text-muted-foreground leading-relaxed max-w-xl">
              서버에 이벤트가 몰려도 렉이 걸리지 않습니다. 게임 속 사건을 디스코드 알림과 역할에 곧바로 반영합니다.
            </p>

            <div className="pt-3 flex flex-wrap items-center gap-3">
              <Link href="/app">
                <Button
                  size="lg"
                  className="h-11 px-6 text-xs sm:text-sm bg-primary hover:bg-primary/90 text-primary-foreground font-semibold gap-2"
                >
                  Ru-Beacon 콘솔 시작하기
                  <ArrowRight className="w-4 h-4" />
                </Button>
              </Link>
              <a href={release.downloadUrl} target="_blank" rel="noopener noreferrer">
                <Button
                  variant="outline"
                  size="lg"
                  className="h-11 px-6 text-xs sm:text-sm border-border hover:bg-muted/40 transition-colors gap-2"
                >
                  <Download className="w-4 h-4" />
                  플러그인 다운로드 ({release.tagName})
                </Button>
              </a>
            </div>
          </div>

          <div className="lg:col-span-5 flex flex-col items-center justify-center" data-animate-model>
            <svg
              viewBox="0 0 500 320"
              className="w-full max-w-[480px] h-auto overflow-visible"
              aria-hidden="true"
            >
              <defs>
                <radialGradient id="waveGlow" cx="50%" cy="50%" r="50%">
                  <stop offset="0%" stopColor="#E11D48" stopOpacity="0.22" />
                  <stop offset="55%" stopColor="#CEB28A" stopOpacity="0.12" />
                  <stop offset="100%" stopColor="#CEB28A" stopOpacity="0" />
                </radialGradient>
              </defs>
              <ellipse cx="250" cy="165" rx="220" ry="130" fill="url(#waveGlow)" />

              {/* 하단: 샌드(Sand) 톤으로 출렁이는 3D 이벤트 폭증 파형 그리드 */}
              <path
                d={tpsWaveModel.wavePath}
                fill="none"
                stroke="hsl(var(--sand))"
                strokeOpacity="0.52"
                strokeWidth="1.05"
              />

              {/* 상단: 흔들림 없이 평탄한 20.0 TPS 루비 보호 기준면 */}
              <polygon
                points={tpsWaveModel.flatPolygon}
                fill="#E11D48"
                fillOpacity="0.11"
                stroke="#E11D48"
                strokeOpacity="0.42"
                strokeWidth="1.1"
              />

              <path
                data-animate-curve
                d={tpsWaveModel.laserLine}
                fill="none"
                stroke="#E11D48"
                strokeWidth="2.2"
                strokeDasharray="1400"
                strokeDashoffset="0"
              />
              <circle
                cx={tpsWaveModel.laserEnd[0]}
                cy={tpsWaveModel.laserEnd[1]}
                r="4"
                fill="#E11D48"
              />
            </svg>
            <div className="mt-3 flex items-center gap-6 text-xs sm:text-sm font-normal tracking-[0.14em] text-muted-foreground">
              <span className="flex items-center gap-2">
                <span className="w-3 h-[2px] bg-primary inline-block" />
                20.0 TPS 수평 유지
              </span>
              <span className="flex items-center gap-2">
                <span
                  className="w-3 h-[1.5px] inline-block"
                  style={{ backgroundColor: "hsl(var(--sand))" }}
                />
                인게임 이벤트 폭증 파형
              </span>
            </div>
          </div>
        </div>

        {/* 하단: 3대 실측 지표 (라벨 크기 상향) */}
        <div className="relative z-10 max-w-6xl mx-auto w-full pt-8 pb-10 border-t border-border/60 grid grid-cols-3 gap-6">
          <div data-animate-item>
            <div className="font-mono tabular-nums font-bold text-2xl sm:text-4xl text-primary tracking-tight">
              0.00ms
            </div>
            <div className="mt-1.5 text-xs sm:text-sm font-normal tracking-[0.12em] text-muted-foreground">
              메인 틱 점유
            </div>
          </div>
          <div data-animate-item className="text-center">
            <div className="font-mono tabular-nums font-bold text-2xl sm:text-4xl text-foreground tracking-tight">
              0.08ms
            </div>
            <div className="mt-1.5 text-xs sm:text-sm font-normal tracking-[0.12em] text-muted-foreground">
              링버퍼 큐잉
            </div>
          </div>
          <div data-animate-item className="text-right">
            <div className="font-mono tabular-nums font-bold text-2xl sm:text-4xl text-foreground tracking-tight">
              40ms
            </div>
            <div className="mt-1.5 text-xs sm:text-sm font-normal tracking-[0.12em] text-muted-foreground">
              디스코드 도달
            </div>
          </div>
        </div>
      </section>

      {/* 뷰포트 2: 우측 정렬 헤더 + 4가지 3D 도형 궤적 + 중앙 루비-샌드 오로라 그라디언트 */}
      <section
        id="pipeline"
        data-viewport-section
        className="relative py-24 md:py-0 md:min-h-[calc(100dvh-3.5rem)] px-6 border-b border-border/60 flex flex-col justify-center overflow-hidden"
      >
        {/* 4개 3D 파이프라인 도형 영역 배경 루비-샌드 그라디언트 */}
        <div
          className="pointer-events-none absolute inset-0"
          style={{
            background:
              "radial-gradient(ellipse 75% 50% at 50% 48%, rgba(212, 17, 83, 0.14) 0%, rgba(206, 178, 138, 0.13) 44%, transparent 76%)",
          }}
        />

        <div className="relative z-10 max-w-6xl mx-auto w-full space-y-12 md:space-y-16">
          <div className="ml-auto text-left md:text-right max-w-xl space-y-2.5" data-animate-item>
            <div className="text-xs sm:text-sm font-medium tracking-[0.18em] text-primary uppercase">
              Signal Trajectory
            </div>
            <h2 className="text-2xl sm:text-4xl font-bold tracking-tight text-foreground">
              이벤트가 처리되는 실제 런타임 경로
            </h2>
          </div>

          <div className="relative w-full" data-animate-model>
            <svg
              viewBox="0 0 900 260"
              className="hidden md:block w-full h-auto overflow-visible"
              aria-hidden="true"
            >
              <defs>
                <radialGradient id="nodeGlowRuby" cx="50%" cy="50%" r="50%">
                  <stop offset="0%" stopColor="#E11D48" stopOpacity="0.28" />
                  <stop offset="100%" stopColor="#E11D48" stopOpacity="0" />
                </radialGradient>
                <radialGradient id="nodeGlowSand" cx="50%" cy="50%" r="50%">
                  <stop offset="0%" stopColor="#CEB28A" stopOpacity="0.26" />
                  <stop offset="100%" stopColor="#CEB28A" stopOpacity="0" />
                </radialGradient>
              </defs>

              {/* 각 3D 모델 뒤편의 개별 후광 */}
              <circle cx="95" cy="195" r="70" fill="url(#nodeGlowRuby)" />
              <circle cx="310" cy="95" r="70" fill="url(#nodeGlowSand)" />
              <circle cx="560" cy="215" r="70" fill="url(#nodeGlowSand)" />
              <circle cx="795" cy="110" r="75" fill="url(#nodeGlowRuby)" />

              <path
                d="M 40,210 C 260,30 620,270 860,85"
                fill="none"
                stroke="hsl(var(--sand))"
                strokeOpacity="0.3"
                strokeWidth="1.1"
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

              {/* 노드 01: 마인크래프트 서버 (3D 정육면체 블록) */}
              <g>
                <polygon points={node1Cube.topPoly} fill="#E11D48" fillOpacity="0.22" />
                <path d={node1Cube.path} fill="none" stroke="#E11D48" strokeOpacity="0.9" strokeWidth="1.25" />
              </g>

              {/* 노드 02: LMAX Disruptor (3D 샌드 원형 링버퍼) */}
              <g>
                <path d={node2Ring} fill="none" stroke="hsl(var(--sand))" strokeOpacity="0.85" strokeWidth="1.25" />
                <circle cx="310" cy="95" r="4.5" fill="#E11D48" />
              </g>

              {/* 노드 03: WSS 게이트웨이 (3D 샌드-루비 팔면체 다이아몬드 게이트) */}
              <g>
                <polygon points={node3Octa.midPoly} fill="#E11D48" fillOpacity="0.18" />
                <path d={node3Octa.path} fill="none" stroke="hsl(var(--sand))" strokeOpacity="0.88" strokeWidth="1.25" />
              </g>

              {/* 노드 04: 디스코드 봇 서비스 (동심원 신호 파동 안테나) */}
              <g>
                <circle
                  cx="795"
                  cy="110"
                  r={pulseRadius}
                  fill="none"
                  stroke="#E11D48"
                  strokeOpacity="0.5"
                  strokeWidth="1.1"
                />
                <ellipse
                  cx="795"
                  cy="110"
                  rx="22"
                  ry="11"
                  fill="none"
                  stroke="#E11D48"
                  strokeOpacity="0.8"
                  strokeWidth="1.2"
                />
                <ellipse
                  cx="795"
                  cy="110"
                  rx="11"
                  ry="22"
                  fill="none"
                  stroke="hsl(var(--sand))"
                  strokeOpacity="0.7"
                  strokeWidth="1.1"
                />
                <circle cx="795" cy="110" r="5" fill="#E11D48" />
              </g>
            </svg>

            <div className="grid grid-cols-2 md:grid-cols-4 gap-8 pt-4">
              <div data-animate-item className="space-y-1.5">
                <div className="font-mono text-xs sm:text-sm text-primary font-semibold">
                  01 // 0.00ms (완전 비동기)
                </div>
                <h3 className="font-semibold text-base sm:text-lg text-foreground">마인크래프트 서버</h3>
                <p className="text-sm font-normal text-muted-foreground">
                  메인 틱 중단 없이 사건 즉시 분리
                </p>
              </div>

              <div data-animate-item className="space-y-1.5 md:pt-8">
                <div className="font-mono text-xs sm:text-sm text-muted-foreground">02 // 0.08ms Queue</div>
                <h3 className="font-semibold text-base sm:text-lg text-foreground">LMAX Disruptor</h3>
                <p className="text-sm font-normal text-muted-foreground">
                  수만 건의 이벤트를 무잠금 정렬
                </p>
              </div>

              <div data-animate-item className="space-y-1.5">
                <div className="font-mono text-xs sm:text-sm text-muted-foreground">03 // Single-Hop</div>
                <h3 className="font-semibold text-base sm:text-lg text-foreground">WSS 게이트웨이 & Redis</h3>
                <p className="text-sm font-normal text-muted-foreground">
                  상시 열린 웹소켓 통로로 직결 전송
                </p>
              </div>

              <div data-animate-item className="space-y-1.5 md:pt-8 md:text-right">
                <div className="font-mono text-xs sm:text-sm text-emerald-500 font-semibold">04 // 42ms Ack</div>
                <h3 className="font-semibold text-base sm:text-lg text-foreground">디스코드 봇 서비스</h3>
                <p className="text-sm font-normal text-muted-foreground">
                  채널 알림 및 유저 역할 즉시 반영
                </p>
              </div>
            </div>
          </div>
        </div>
      </section>

      {/* 뷰포트 3: 좌측 3D 12슬롯 링버퍼 토러스 모델(루비-샌드 후광) + 우측 3대 기술 명세 */}
      <section
        id="architecture"
        data-viewport-section
        className="relative py-24 md:py-0 md:min-h-[calc(100dvh-3.5rem)] px-6 border-b border-border/60 flex flex-col justify-center overflow-hidden"
      >
        {/* 좌측 3D 링버퍼 모델 뒤편 루비-샌드 집중 그라디언트 */}
        <div
          className="pointer-events-none absolute inset-0"
          style={{
            background:
              "radial-gradient(circle at 28% 54%, rgba(212, 17, 83, 0.17) 0%, rgba(206, 178, 138, 0.15) 36%, transparent 68%)",
          }}
        />

        <div className="relative z-10 max-w-6xl mx-auto w-full grid grid-cols-1 lg:grid-cols-12 gap-12 items-center">
          <div className="lg:col-span-6 flex flex-col items-center lg:items-start" data-animate-model>
            <div className="space-y-2.5 mb-6" data-animate-item>
              <div className="text-xs sm:text-sm font-medium tracking-[0.18em] text-primary uppercase">
                Core Engine
              </div>
              <h2 className="text-2xl sm:text-4xl font-bold tracking-tight text-foreground">
                초저지연 비동기 인프라 명세
              </h2>
            </div>

            <svg
              viewBox="0 0 460 360"
              className="w-full max-w-[440px] h-auto overflow-visible"
              aria-hidden="true"
            >
              <defs>
                <radialGradient id="torusGlow" cx="50%" cy="50%" r="50%">
                  <stop offset="0%" stopColor="#E11D48" stopOpacity="0.26" />
                  <stop offset="55%" stopColor="#CEB28A" stopOpacity="0.14" />
                  <stop offset="100%" stopColor="#CEB28A" stopOpacity="0" />
                </radialGradient>
              </defs>
              <circle cx="230" cy="190" r="175" fill="url(#torusGlow)" />

              <path
                data-animate-curve
                d="M 25,240 C 90,60 370,60 435,140 C 380,310 110,320 25,240"
                fill="none"
                stroke="#E11D48"
                strokeOpacity="0.7"
                strokeWidth="1.6"
                strokeDasharray="1400"
                strokeDashoffset="0"
              />
              {/* 12슬롯 샌드(Sand) 와이어프레임 링버퍼 */}
              <path
                d={ringBufferPath}
                fill="none"
                stroke="hsl(var(--sand))"
                strokeOpacity="0.55"
                strokeWidth="1.05"
              />
              {/* 중심부 루비 코어 팔면체 */}
              <polygon points={ringCoreOcta.midPoly} fill="#E11D48" fillOpacity="0.3" />
              <path
                d={ringCoreOcta.path}
                fill="none"
                stroke="#E11D48"
                strokeOpacity="0.92"
                strokeWidth="1.35"
              />
            </svg>
          </div>

          <div className="lg:col-span-6 divide-y divide-border/70 border-y border-border/70">
            <div data-animate-item className="py-7 space-y-2">
              <div className="text-xs sm:text-sm font-medium tracking-[0.16em] text-primary uppercase">
                01 / Direct Socket
              </div>
              <h3 className="font-semibold text-lg sm:text-xl text-foreground">
                WSS 단일 계층 게이트웨이
              </h3>
              <p className="text-sm sm:text-base font-normal text-muted-foreground">
                중계 서버 없이 플러그인과 관제 서버가 직접 연결됩니다.
              </p>
            </div>

            <div data-animate-item className="py-7 space-y-2 lg:pl-8">
              <div className="text-xs sm:text-sm font-medium tracking-[0.16em] text-primary uppercase">
                02 / Lock-Free Ring
              </div>
              <h3 className="font-semibold text-lg sm:text-xl text-foreground">
                LMAX Disruptor 인메모리 링버퍼
              </h3>
              <p className="text-sm sm:text-base font-normal text-muted-foreground">
                대규모 전투·채굴 이벤트도 대기 시간 없이 메모리에서 즉시 소화합니다.
              </p>
            </div>

            <div data-animate-item className="py-7 space-y-2 lg:pl-16">
              <div className="text-xs sm:text-sm font-medium tracking-[0.16em] text-primary uppercase">
                03 / Instant Role Sync
              </div>
              <h3 className="font-semibold text-lg sm:text-xl text-foreground">
                디스코드 권한 실시간 동기화
              </h3>
              <p className="text-sm sm:text-base font-normal text-muted-foreground">
                인게임 달성 조건을 감지하는 즉시 디스코드 역할과 칭호를 바꿉니다.
              </p>
            </div>
          </div>
        </div>
      </section>

      {/* 뷰포트 4: 수직 비콘 광선 착지(루비-샌드 후광) + 직선 속도 비교 게이지 & 3단계 시작 */}
      <section
        id="comparison"
        data-viewport-section
        className="relative py-24 md:py-0 md:min-h-[calc(100dvh-3.5rem)] px-6 flex flex-col justify-between overflow-hidden"
      >
        {/* 상단 수직 비콘 착지 타겟 주변 루비-샌드 그라디언트 */}
        <div
          className="pointer-events-none absolute inset-0"
          style={{
            background:
              "radial-gradient(circle at 50% 22%, rgba(212, 17, 83, 0.16) 0%, rgba(206, 178, 138, 0.13) 38%, transparent 72%)",
          }}
        />

        <div className="relative z-10 flex flex-col items-center pt-2" data-animate-model>
          <svg
            viewBox="0 0 240 120"
            className="w-48 h-24 overflow-visible"
            aria-hidden="true"
          >
            <defs>
              <linearGradient id="landingBeamGrad" x1="0" y1="0" x2="0" y2="1">
                <stop offset="0%" stopColor="#E11D48" stopOpacity="0" />
                <stop offset="60%" stopColor="#E11D48" stopOpacity="0.25" />
                <stop offset="100%" stopColor="#E11D48" stopOpacity="0.6" />
              </linearGradient>
            </defs>
            <rect x="108" y="0" width="24" height="88" fill="url(#landingBeamGrad)" />
            <line
              data-animate-curve
              x1="120"
              y1="0"
              x2="120"
              y2="88"
              stroke="#E11D48"
              strokeWidth="1.8"
              strokeDasharray="1400"
              strokeDashoffset="0"
            />
            <polygon points={targetOcta.midPoly} fill="#E11D48" fillOpacity="0.28" />
            <path d={targetOcta.path} fill="none" stroke="hsl(var(--sand))" strokeWidth="1.25" />
          </svg>
        </div>

        <div className="relative z-10 max-w-4xl mx-auto w-full space-y-12 my-auto">
          <div className="space-y-6">
            <div className="text-center space-y-2" data-animate-item>
              <div className="text-xs sm:text-sm font-medium tracking-[0.18em] text-primary uppercase">
                Benchmark Contrast
              </div>
              <h2 className="text-2xl sm:text-4xl font-bold tracking-tight text-foreground">
                기존 연동 방식과의 기술적 격차
              </h2>
              <p className="text-sm font-normal text-muted-foreground">
                마인크래프트 메인 틱 점유 시간
              </p>
            </div>

            <div
              data-animate-item
              className="max-w-2xl mx-auto space-y-5 border-y border-border/70 py-6"
            >
              <div className="space-y-2">
                <div className="flex items-center justify-between text-xs sm:text-sm">
                  <span className="font-semibold text-foreground">RuBeacon</span>
                  <span className="font-mono font-bold text-primary">0.00ms (완전 비동기)</span>
                </div>
                <div className="w-full h-2 bg-muted/70 overflow-hidden">
                  <div
                    data-gauge-fast
                    className="w-full h-full bg-primary origin-left"
                  />
                </div>
              </div>

              <div className="space-y-2">
                <div className="flex items-center justify-between text-xs sm:text-sm text-muted-foreground">
                  <span className="font-normal">기존 웹훅 플러그인</span>
                  <span className="font-mono">15 - 50ms (동기 블로킹 렉 유발)</span>
                </div>
                <div className="w-full h-2 bg-muted/70 overflow-hidden">
                  <div
                    data-gauge-slow
                    className="w-full h-full origin-left"
                    style={{ backgroundColor: "hsl(var(--sand-strong))" }}
                  />
                </div>
              </div>
            </div>
          </div>

          <div id="setup" className="space-y-5">
            <div className="text-center" data-animate-item>
              <span className="text-xs sm:text-sm font-medium tracking-[0.18em] text-muted-foreground uppercase">
                빠른 3단계 연동 안내
              </span>
            </div>

            <div className="grid grid-cols-1 md:grid-cols-3 gap-6 text-center">
              <div data-animate-item className="space-y-1.5 pt-4 border-t border-border/60">
                <div className="font-mono text-xs sm:text-sm text-primary font-bold">01</div>
                <h3 className="font-semibold text-base text-foreground">디스코드 봇 초대</h3>
                <p className="text-sm font-normal text-muted-foreground">전용 봇을 서버에 추가</p>
              </div>
              <div data-animate-item className="space-y-1.5 pt-4 border-t border-border/60">
                <div className="font-mono text-xs sm:text-sm text-primary font-bold">02</div>
                <h3 className="font-semibold text-base text-foreground">인스턴스 토큰 발급</h3>
                <p className="text-sm font-normal text-muted-foreground">플러그인 설정에 토큰 등록</p>
              </div>
              <div data-animate-item className="space-y-1.5 pt-4 border-t border-border/60">
                <div className="font-mono text-xs sm:text-sm text-primary font-bold">03</div>
                <h3 className="font-semibold text-base text-foreground">실시간 하트비트 확인</h3>
                <p className="text-sm font-normal text-muted-foreground">서버 가동 즉시 온라인 연결</p>
              </div>
            </div>
          </div>

          <div data-animate-item className="text-center space-y-5 pt-2">
            <h3 className="text-xl sm:text-2xl font-bold tracking-tight text-foreground">
              서버 렉 없는 디스코드 커뮤니티, 지금 즉시 연결하세요.
            </h3>

            <div className="flex flex-wrap items-center justify-center gap-3">
              <a href={release.downloadUrl} target="_blank" rel="noopener noreferrer">
                <Button
                  variant="outline"
                  size="lg"
                  className="h-11 px-6 text-xs sm:text-sm border-border hover:bg-muted/40 font-medium gap-2"
                >
                  <Download className="w-4 h-4" />
                  플러그인 JAR 다운로드 ({release.tagName})
                </Button>
              </a>
              <Link href="/app">
                <Button
                  size="lg"
                  className="h-11 px-7 text-xs sm:text-sm bg-primary hover:bg-primary/90 text-primary-foreground font-semibold gap-2"
                >
                  루비콘 콘솔 열기
                  <ArrowRight className="w-4 h-4" />
                </Button>
              </Link>
            </div>
          </div>
        </div>

        {/* 미니멀 푸터 */}
        <footer className="relative z-10 max-w-6xl mx-auto w-full pt-8 pb-6 border-t border-border/50 text-xs font-normal tracking-wider text-muted-foreground flex flex-col sm:flex-row items-center justify-between gap-4">
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
