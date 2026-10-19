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
import { LANDING_COPY_KO } from "@/content/landing-copy";

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
 * 뷰포트 2 노드 02: LMAX Disruptor 상징 12슬롯 무잠금 회전 링버퍼 트랙(RingBuffer Dial)
 * 링버퍼 트랙이 시계 방향으로 회전하고, 패킷(Dot)은 트랙의 슬롯과 일체화되어 틱-틱-틱 다음 슬롯으로 스텝 전진함.
 */
function buildDisruptorDial(radius: number, innerRadius: number, slots: number, angleDeg: number, cx: number, cy: number) {
  const outerLines: string[] = [];
  const slotLines: string[] = [];
  const radOffset = (angleDeg * Math.PI) / 180;
  for (let i = 0; i < slots; i++) {
    const a1 = radOffset + (i / slots) * Math.PI * 2;
    const a2 = radOffset + ((i + 1) / slots) * Math.PI * 2;
    const o1x = cx + Math.cos(a1) * radius;
    const o1y = cy + Math.sin(a1) * (radius * 0.58);
    const o2x = cx + Math.cos(a2) * radius;
    const o2y = cy + Math.sin(a2) * (radius * 0.58);
    const i1x = cx + Math.cos(a1) * innerRadius;
    const i1y = cy + Math.sin(a1) * (innerRadius * 0.58);
    const i2x = cx + Math.cos(a2) * innerRadius;
    const i2y = cy + Math.sin(a2) * (innerRadius * 0.58);
    outerLines.push(`M${o1x.toFixed(1)},${o1y.toFixed(1)} L${o2x.toFixed(1)},${o2y.toFixed(1)}`);
    outerLines.push(`M${i1x.toFixed(1)},${i1y.toFixed(1)} L${i2x.toFixed(1)},${i2y.toFixed(1)}`);
    slotLines.push(`M${o1x.toFixed(1)},${o1y.toFixed(1)} L${i1x.toFixed(1)},${i1y.toFixed(1)}`);
  }

  // 패킷 점(Dot): 트랙과 결합된 슬롯 인덱스를 틱-틱-틱 스텝 전진하며 360도 전체를 끊김 없이 순회
  const slotIndex = Math.floor((angleDeg / 30)) % slots;
  const normalizedSlot = ((slotIndex % slots) + slots) % slots;
  const dotAngle = radOffset + (normalizedSlot / slots) * Math.PI * 2 + (Math.PI / slots);
  const midR = (radius + innerRadius) / 2;
  const activeDot = [
    cx + Math.cos(dotAngle) * midR,
    cy + Math.sin(dotAngle) * (midR * 0.58),
  ] as const;

  return {
    rings: outerLines.join(" "),
    slots: slotLines.join(" "),
    dot: activeDot,
  };
}

/**
 * 뷰포트 2 노드 03: WSS 게이트웨이 & Redis 인메모리 회전 육각 프리즘 타워
 */
function buildCacheStack(angleDeg: number, cx: number, cy: number) {
  // 상·중·하 3개 레이어가 입체적으로 회전하는 아이소메트릭 육각 프리즘
  const layers = [
    { y: cy - 18, rot: angleDeg * 1.2, r: 24 },
    { y: cy, rot: -angleDeg * 1.5, r: 26 },
    { y: cy + 18, rot: angleDeg * 1.2, r: 24 },
  ];

  const paths: string[] = [];
  layers.forEach((l) => {
    const pts: [number, number][] = [];
    for (let i = 0; i < 6; i++) {
      const a = ((l.rot + i * 60) * Math.PI) / 180;
      pts.push([
        cx + Math.cos(a) * l.r,
        l.y + Math.sin(a) * (l.r * 0.55),
      ]);
    }
    const poly = pts.map((p) => `${p[0].toFixed(1)},${p[1].toFixed(1)}`).join(" L ");
    paths.push(`M ${poly} Z`);
  });

  return paths.join(" ");
}

/**
 * 3D 팔면체 다이아몬드(Octahedron)
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
 * 뷰포트 3: 2-Tier 메인 틱 분리 아키텍처 모델 (상단: 20.0 TPS 메인 루프, 하단: 비동기 링버퍼 파이프라인, 수직 패킷 분기)
 */
function buildParallelArchitecture(phaseRad: number) {
  // 상단 평면 (Minecraft Main Server Tick - 20.0 TPS 안정 구역)
  const topLayer = "M 40,85 L 220,35 L 420,85 L 240,135 Z";
  // 하단 평면 (RuBeacon Async Disruptor & WSS Pipeline - 무잠금 비동기 구역)
  const botLayer = "M 40,215 L 220,165 L 420,215 L 240,265 Z";
  // 수직 분기 가이드라인 (상단 평면 하단부와 하단 평면 상단부 사이 내부 공간에만 위치)
  const dropLanes = [
    { x: 130, y1: 120, y2: 175 },
    { x: 230, y1: 140, y2: 195 },
    { x: 330, y1: 120, y2: 175 },
  ];

  // 실시간 단방향 하강하는 3개 패킷 좌표 (phaseRad 기반 위에서 아래로만 부드럽게 무한 순환)
  // phaseRad는 0에서 계속 증가하므로, 단방향 모듈로 연산으로 자연스럽게 상단에서 하단으로 낙하 순환
  const packets = dropLanes.map((lane, idx) => {
    const rawProgress = (phaseRad * 0.45 + idx * 0.33) % 1;
    const offsetProg = ((rawProgress % 1) + 1) % 1; // 0 -> 1 단방향 보장
    return {
      x: lane.x,
      y: lane.y1 + (lane.y2 - lane.y1) * offsetProg,
      opacity: Math.sin(offsetProg * Math.PI), // 상·하단 평면 경계에서 페이드인/페이드아웃으로 부드러운 전환
    };
  });

  return {
    topLayer,
    botLayer,
    dropLanes,
    packets,
  };
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
  const node2Dial = buildDisruptorDial(34, 18, 12, -modelAngle * 1.5, 310, 95);
  const node3Stack = buildCacheStack(modelAngle, 560, 215);
  const discordPulse = 1 + (Math.sin(phaseRad * 2.5) * 0.5 + 0.5) * 0.08;
  const clydeRotDeg = Math.sin(phaseRad * 1.5) * 14;

  const parallelArch = buildParallelArchitecture(phaseRad);

  // 뷰포트 4: 인게임 -> 디스코드 단방향 실시간 패킷 전송 및 디스코드 임베드 타이핑 반응
  const syncProgress = ((modelAngle * 3) % 360) / 360; // 0 -> 1 무한 루프
  const syncPacketX = 84 + syncProgress * (275 - 84);
  const syncPacketGlow = Math.sin(syncProgress * Math.PI); // 중앙에서 최대 발광
  const cubePulse = syncProgress < 0.15 ? 1 + (0.15 - syncProgress) * 0.8 : 1;
  const embedTyping = syncProgress > 0.82 ? (syncProgress - 0.82) / 0.18 : 1;

  return (
    <div
      ref={pageRef}
      className="min-h-screen flex flex-col bg-background text-foreground selection:bg-primary selection:text-primary-foreground font-sans break-keep"
    >
      {/* 상단 고정 헤더: 실선 경계 제거 + 부드러운 페이드 글래스모피즘 그라데이션 */}
      <header className="sticky top-0 z-50 bg-gradient-to-b from-background/95 via-background/70 to-transparent backdrop-blur-md transition-colors">
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
              {LANDING_COPY_KO.header.nav.map((item) => (
                <Link
                  key={item.href}
                  href={item.href}
                  className="relative py-1 text-sm font-medium text-muted-foreground hover:text-foreground transition-colors after:absolute after:bottom-0 after:left-0 after:right-0 after:h-[2px] after:bg-primary after:scale-x-0 hover:after:scale-x-100 after:transition-transform after:duration-200 after:origin-center"
                >
                  {item.label}
                </Link>
              ))}
            </nav>

            <div className="flex items-center gap-3">
              <ThemeToggle />
              <Link href="/app">
                <Button
                  size="sm"
                  className="h-9 px-4 text-xs sm:text-sm bg-primary hover:bg-primary/90 text-primary-foreground font-semibold gap-1.5 shadow-sm transition-all"
                >
                  {user ? LANDING_COPY_KO.header.ctaConsole : LANDING_COPY_KO.header.ctaStart}
                  <ArrowRight className="w-3.5 h-3.5" />
                </Button>
              </Link>
            </div>
          </div>
        </div>
      </header>

      {/* 뷰포트 0: 정육면체 비콘 피라미드 (스크롤 조립 인터랙션을 위해 자석 스냅 제외: snap-none) */}
      <div className="snap-none">
        <BeaconStage />
      </div>

      {/* 뷰포트 1: 좌측 헤드라인 / 우측 [3D 이벤트 파형 vs 20.0 TPS 수평 레이저] (소프트 앵커 스냅: snap-start) */}
      <section
        id="hero"
        data-viewport-section
        className="relative py-24 md:py-0 md:min-h-[calc(100dvh-3.5rem)] px-6 border-b border-border/60 flex flex-col justify-between overflow-hidden snap-start scroll-mt-14"
      >
        {/* 3D 파형 모델 영역 딥 루비 앰비언트 라이트 */}
        <div
          className="pointer-events-none absolute inset-0"
          style={{
            background:
              "radial-gradient(circle at 72% 46%, rgba(225, 29, 72, 0.05) 0%, transparent 68%)",
          }}
        />

        <div className="hidden md:block" />

        <div className="relative z-10 max-w-6xl mx-auto w-full grid grid-cols-1 lg:grid-cols-12 gap-12 items-center my-auto">
          <div className="lg:col-span-7 space-y-6" data-animate-item>
            <div className="text-xs sm:text-sm font-medium tracking-[0.18em] text-primary uppercase">
              {LANDING_COPY_KO.hero.category}
            </div>

            <h1 className="text-3xl sm:text-5xl md:text-[3.5rem] font-bold tracking-tight text-foreground leading-[1.15] text-balance">
              {LANDING_COPY_KO.hero.headline}
            </h1>

            <p className="text-base sm:text-lg font-normal text-muted-foreground leading-relaxed max-w-xl">
              {LANDING_COPY_KO.hero.description}
            </p>

            <div className="pt-3 flex flex-wrap items-center gap-3">
              <Link href="/app">
                <Button
                  size="lg"
                  className="h-11 px-6 text-xs sm:text-sm bg-primary hover:bg-primary/90 text-primary-foreground font-semibold gap-2"
                >
                  {LANDING_COPY_KO.hero.ctaPrimary}
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
                  {LANDING_COPY_KO.hero.ctaSecondary} ({release.tagName})
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
                {LANDING_COPY_KO.hero.waveLegends.tpsStable}
              </span>
              <span className="flex items-center gap-2">
                <span
                  className="w-3 h-[1.5px] inline-block"
                  style={{ backgroundColor: "hsl(var(--sand))" }}
                />
                {LANDING_COPY_KO.hero.waveLegends.eventSurge}
              </span>
            </div>
          </div>
        </div>

        {/* 하단: 3대 실측 지표 */}
        <div className="relative z-10 max-w-6xl mx-auto w-full pt-8 pb-10 border-t border-border/60 grid grid-cols-3 gap-6">
          {LANDING_COPY_KO.hero.metrics.map((metric, idx) => (
            <div
              key={metric.label}
              data-animate-item
              className={idx === 1 ? "text-center" : idx === 2 ? "text-right" : undefined}
            >
              <div
                className={`font-mono tabular-nums font-bold text-2xl sm:text-4xl tracking-tight ${
                  idx === 0 ? "text-primary" : "text-foreground"
                }`}
              >
                {metric.value}
              </div>
              <div className="mt-1.5 text-xs sm:text-sm font-normal tracking-[0.12em] text-muted-foreground">
                {metric.label}
              </div>
            </div>
          ))}
        </div>
      </section>

      {/* 뷰포트 2: 우측 정렬 헤더 + 4가지 3D 도형 궤적 */}
      <section
        id="pipeline"
        data-viewport-section
        className="relative py-24 md:py-0 md:min-h-[calc(100dvh-3.5rem)] px-6 border-b border-border/60 flex flex-col justify-center overflow-hidden snap-start scroll-mt-14"
      >
        {/* 4개 3D 파이프라인 도형 영역 배경 딥 루비 앰비언트 라이트 */}
        <div
          className="pointer-events-none absolute inset-0"
          style={{
            background:
              "radial-gradient(ellipse 65% 45% at 50% 50%, rgba(225, 29, 72, 0.05) 0%, transparent 68%)",
          }}
        />

        <div className="relative z-10 max-w-6xl mx-auto w-full space-y-12 md:space-y-16">
          <div className="ml-auto text-left md:text-right max-w-xl space-y-2.5" data-animate-item>
            <div className="text-xs sm:text-sm font-medium tracking-[0.18em] text-primary uppercase">
              {LANDING_COPY_KO.pipeline.tag}
            </div>
            <h2 className="text-2xl sm:text-4xl font-bold tracking-tight text-foreground">
              {LANDING_COPY_KO.pipeline.title}
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
                  <stop offset="0%" stopColor="#E11D48" stopOpacity="0.14" />
                  <stop offset="100%" stopColor="#E11D48" stopOpacity="0" />
                </radialGradient>
                <radialGradient id="nodeGlowSand" cx="50%" cy="50%" r="50%">
                  <stop offset="0%" stopColor="#CEB28A" stopOpacity="0.08" />
                  <stop offset="100%" stopColor="#CEB28A" stopOpacity="0" />
                </radialGradient>
              </defs>

              {/* 각 3D 모델 뒤편의 개별 후광 */}
              <circle cx="95" cy="195" r="70" fill="url(#nodeGlowRuby)" />
              <circle cx="310" cy="95" r="70" fill="url(#nodeGlowSand)" />
              <circle cx="560" cy="215" r="70" fill="url(#nodeGlowSand)" />
              <circle cx="795" cy="75" r="75" fill="url(#nodeGlowRuby)" />

              <path
                d="M 40,210 C 260,30 600,270 860,120"
                fill="none"
                stroke="hsl(var(--sand))"
                strokeOpacity="0.25"
                strokeWidth="1.1"
              />
              <path
                data-animate-curve
                d="M 40,195 C 260,15 600,255 860,105"
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

              {/* 노드 02: LMAX Disruptor (12슬롯 무잠금 회전 링버퍼 트랙) */}
              <g>
                <path d={node2Dial.rings} fill="none" stroke="hsl(var(--sand))" strokeOpacity="0.88" strokeWidth="1.2" />
                <path d={node2Dial.slots} fill="none" stroke="hsl(var(--sand))" strokeOpacity="0.45" strokeWidth="0.9" />
                {/* 실시간 큐잉 중인 이벤트 패킷 점 */}
                <circle cx={node2Dial.dot[0]} cy={node2Dial.dot[1]} r="4" fill="#E11D48" />
              </g>

              {/* 노드 03: WSS 게이트웨이 & Redis (3D 회전 육각 프리즘 타워) */}
              <g>
                <path d={node3Stack} fill="none" stroke="hsl(var(--sand))" strokeOpacity="0.85" strokeWidth="1.25" />
                {/* 타워 중심 인메모리 액티브 코어 */}
                <circle cx="560" cy="215" r="4.5" fill="#E11D48" />
                <circle cx="560" cy="215" r="9" fill="none" stroke="#E11D48" strokeOpacity="0.35" strokeWidth="1" />
              </g>

              {/* 노드 04: 디스코드 봇 서비스 (정규 Clyde 봇 헤드 마스크 - 테크니컬 라인 아트 규격 & 부드러운 오실레이션) */}
              <g
                transform={`translate(795, 75) rotate(${clydeRotDeg}) scale(${discordPulse * 0.44}) translate(-64, -48)`}
              >
                <path
                  d="M107.7,8.07A105.15,105.15,0,0,0,81.47,0a72.06,72.06,0,0,0-3.36,6.83A97.68,97.68,0,0,0,49,6.83,72.37,72.37,0,0,0,45.64,0,105.89,105.89,0,0,0,19.39,8.09C2.79,32.65-1.71,56.6.54,80.21h0A105.73,105.73,0,0,0,32.71,96.36,77.7,77.7,0,0,0,39.6,85.25a68.42,68.42,0,0,1-10.85-5.18c.91-.66,1.8-1.34,2.66-2a75.57,75.57,0,0,0,64.32,0c.87.71,1.76,1.39,2.66,2a68.68,68.68,0,0,1-10.87,5.19,77,77,0,0,0,6.89,11.1A105.25,105.25,0,0,0,126.6,80.22h0C129.24,52.84,122.09,29.11,107.7,8.07Z"
                  fill="none"
                  stroke="#E11D48"
                  strokeWidth="1.3"
                  strokeLinejoin="round"
                  strokeLinecap="round"
                />
                <ellipse cx="42.5" cy="53" rx="8" ry="10" fill="#E11D48" fillOpacity="0.85" />
                <ellipse cx="84.5" cy="53" rx="8" ry="10" fill="#E11D48" fillOpacity="0.85" />
              </g>
            </svg>

            <div className="grid grid-cols-2 md:grid-cols-4 gap-8 pt-4">
              {LANDING_COPY_KO.pipeline.steps.map((st, idx) => (
                <div
                  key={st.step}
                  data-animate-item
                  className={`space-y-1.5 ${
                    idx === 1 ? "md:pt-8" : idx === 3 ? "md:pt-8 md:text-right" : ""
                  }`}
                >
                  <div
                    className={`font-mono text-xs sm:text-sm font-semibold ${
                      idx === 0
                        ? "text-primary"
                        : idx === 3
                        ? "text-emerald-500"
                        : "text-muted-foreground"
                    }`}
                  >
                    {st.step} // {st.metric}
                  </div>
                  <h3 className="font-semibold text-base sm:text-lg text-foreground">{st.title}</h3>
                  <p className="text-sm font-normal text-muted-foreground">{st.desc}</p>
                </div>
              ))}
            </div>
          </div>
        </div>
      </section>

      {/* 뷰포트 3: 좌측 2-Tier 아키텍처 모델 + 우측 3대 기술 명세 */}
      <section
        id="architecture"
        data-viewport-section
        className="relative py-24 md:py-0 md:min-h-[calc(100dvh-3.5rem)] px-6 border-b border-border/60 flex flex-col justify-center overflow-hidden snap-start scroll-mt-14"
      >
        {/* 좌측 3D 모델 영역 딥 루비 단일 앰비언트 라이트 */}
        <div
          className="pointer-events-none absolute inset-0"
          style={{
            background:
              "radial-gradient(circle at 28% 52%, rgba(225, 29, 72, 0.05) 0%, transparent 64%)",
          }}
        />

        <div className="relative z-10 max-w-6xl mx-auto w-full grid grid-cols-1 lg:grid-cols-12 gap-12 items-center">
          <div className="lg:col-span-6 flex flex-col items-center lg:items-start" data-animate-model>
            <div className="space-y-2.5 mb-6" data-animate-item>
              <div className="text-xs sm:text-sm font-medium tracking-[0.18em] text-primary uppercase">
                {LANDING_COPY_KO.architecture.tag}
              </div>
              <h2 className="text-2xl sm:text-4xl font-bold tracking-tight text-foreground">
                {LANDING_COPY_KO.architecture.title}
              </h2>
            </div>

            <svg
              viewBox="0 0 460 320"
              className="w-full max-w-[440px] h-auto overflow-visible"
              aria-hidden="true"
            >
              <defs>
                <linearGradient id="layerGrad" x1="0" y1="0" x2="1" y2="0">
                  <stop offset="0%" stopColor="#E11D48" stopOpacity="0.04" />
                  <stop offset="50%" stopColor="hsl(var(--sand))" stopOpacity="0.03" />
                  <stop offset="100%" stopColor="#E11D48" stopOpacity="0.04" />
                </linearGradient>
              </defs>

              {/* 상단 레이어: 마인크래프트 메인 틱 (Tick Thread - 20.0 TPS 안정) */}
              <polygon points="40,85 220,35 420,85 240,135" fill="url(#layerGrad)" />
              <path
                d={parallelArch.topLayer}
                fill="none"
                stroke="#E11D48"
                strokeOpacity="0.9"
                strokeWidth="1.3"
              />

              {/* 하단 레이어: RuBeacon 비동기 링버퍼 & WSS 파이프라인 */}
              <polygon points="40,215 220,165 420,215 240,265" fill="url(#layerGrad)" />
              <path
                d={parallelArch.botLayer}
                fill="none"
                stroke="hsl(var(--sand))"
                strokeOpacity="0.65"
                strokeWidth="1.2"
              />

              {/* 수직 분기 격리 가이드 레일 (메인 틱 점유 시간 0.00ms 증명) */}
              {parallelArch.dropLanes.map((lane, idx) => (
                <line
                  key={`lane-${idx}`}
                  x1={lane.x}
                  y1={lane.y1}
                  x2={lane.x}
                  y2={lane.y2}
                  stroke="hsl(var(--sand))"
                  strokeOpacity="0.3"
                  strokeWidth="1"
                  strokeDasharray="4 4"
                />
              ))}

              {/* 실시간으로 메인 틱에서 비동기 파이프라인으로 무지연 낙하하는 이벤트 패킷들 */}
              {parallelArch.packets.map((pkt, idx) => (
                <g key={`pkt-${idx}`} opacity={pkt.opacity}>
                  <circle cx={pkt.x} cy={pkt.y} r="5" fill="#E11D48" />
                  <circle cx={pkt.x} cy={pkt.y} r="9" fill="none" stroke="#E11D48" strokeOpacity="0.4" strokeWidth="1" />
                </g>
              ))}
            </svg>
          </div>

          <div className="lg:col-span-6 divide-y divide-border/70 border-y border-border/70">
            {LANDING_COPY_KO.architecture.items.map((item, idx) => (
              <div
                key={item.tag}
                data-animate-item
                className={`py-7 space-y-2 ${idx === 1 ? "lg:pl-8" : idx === 2 ? "lg:pl-16" : ""}`}
              >
                <div className="text-xs sm:text-sm font-medium tracking-[0.16em] text-primary uppercase">
                  {item.tag}
                </div>
                <h3 className="font-semibold text-lg sm:text-xl text-foreground">{item.title}</h3>
                <p className="text-sm sm:text-base font-normal text-muted-foreground">{item.desc}</p>
              </div>
            ))}
          </div>
        </div>
      </section>

      {/* 뷰포트 4: 실시간 미러링 쇼케이스 + 직선 속도 비교 게이지 & 3단계 시작 */}
      <section
        id="comparison"
        data-viewport-section
        className="relative py-24 md:py-0 md:min-h-[calc(100dvh-3.5rem)] px-6 flex flex-col justify-between overflow-hidden snap-start scroll-mt-14"
      >
        {/* 상단 미러링 영역 주변 딥 루비 단일 앰비언트 라이트 */}
        <div
          className="pointer-events-none absolute inset-0"
          style={{
            background:
              "radial-gradient(circle at 50% 20%, rgba(225, 29, 72, 0.05) 0%, transparent 68%)",
          }}
        />

        <div className="relative z-10 flex flex-col items-center pt-2" data-animate-model>
          <svg
            viewBox="0 0 380 120"
            className="w-full max-w-[360px] h-24 overflow-visible"
            aria-hidden="true"
          >
            {/* 좌측: 마인크래프트 인게임 이벤트 큐브 노드 (이벤트 격발 시 펄스) */}
            <g transform={`translate(60, 60) scale(${cubePulse})`}>
              <rect x="-24" y="-24" width="48" height="48" rx="6" fill="#E11D48" fillOpacity="0.12" stroke="#E11D48" strokeWidth="1.4" />
              {/* 마인크래프트 검/블록 상징 대각선 라인 */}
              <line x1="-12" y1="12" x2="12" y2="-12" stroke="#E11D48" strokeWidth="1.6" strokeLinecap="round" />
              <text x="0" y="38" textAnchor="middle" fill="#E11D48" fontSize="10" fontFamily="monospace" fontWeight="600">
                IN-GAME
              </text>
            </g>

            {/* 중앙: 초고속 직결 미러링 통로 (42ms 양방향 전송 레이저 및 이동 패킷) */}
            <line
              data-animate-curve
              x1="90"
              y1="60"
              x2="275"
              y2="60"
              stroke="hsl(var(--sand))"
              strokeOpacity="0.35"
              strokeWidth="1.2"
              strokeDasharray="4 4"
            />
            {/* 실시간으로 인게임에서 디스코드로 질주하는 단방향 패킷 광선 */}
            <circle cx={syncPacketX} cy="60" r="4.5" fill="#E11D48" />
            <circle cx={syncPacketX} cy="60" r={8 + syncPacketGlow * 4} fill="none" stroke="#E11D48" strokeOpacity={0.25 + syncPacketGlow * 0.45} strokeWidth="1.2" />

            <text x="182" y="50" textAnchor="middle" fill="hsl(var(--sand))" fontSize="10" fontFamily="monospace" fontWeight="bold">
              ⚡ 42ms SYNC
            </text>

            {/* 우측: 디스코드 임베드 카드 노드 (패킷 도착 시 타이핑되는 라인) */}
            <g transform="translate(310, 60)">
              <rect x="-35" y="-26" width="70" height="52" rx="4" fill="hsl(var(--sand))" fillOpacity="0.08" stroke="hsl(var(--sand))" strokeWidth="1.3" />
              {/* 디스코드 임베드 루비 컬러 바 (패킷 도달 시 발광) */}
              <rect x="-31" y="-21" width="4" height="42" rx="1.5" fill="#E11D48" fillOpacity={0.6 + embedTyping * 0.4} />
              {/* 실시간 타이핑되는 임베드 텍스트 모의 라인 */}
              <line
                x1="-20"
                y1="-12"
                x2={-20 + 42 * Math.min(1, embedTyping * 1.5)}
                y2="-12"
                stroke="hsl(var(--sand))"
                strokeOpacity="0.85"
                strokeWidth="2.5"
                strokeLinecap="round"
              />
              <line
                x1="-20"
                y1="-2"
                x2={-20 + 32 * Math.max(0, Math.min(1, (embedTyping - 0.25) * 1.6))}
                y2="-2"
                stroke="hsl(var(--sand))"
                strokeOpacity="0.55"
                strokeWidth="2"
                strokeLinecap="round"
              />
              <line
                x1="-20"
                y1="8"
                x2={-20 + 38 * Math.max(0, Math.min(1, (embedTyping - 0.5) * 2))}
                y2="8"
                stroke="hsl(var(--sand))"
                strokeOpacity="0.4"
                strokeWidth="2"
                strokeLinecap="round"
              />
              <text x="0" y="38" textAnchor="middle" fill="hsl(var(--sand))" fontSize="10" fontFamily="monospace" fontWeight="600">
                DISCORD
              </text>
            </g>
          </svg>
        </div>

        <div className="relative z-10 max-w-4xl mx-auto w-full space-y-12 my-auto">
          <div className="space-y-6">
            <div className="text-center space-y-2" data-animate-item>
              <div className="text-xs sm:text-sm font-medium tracking-[0.18em] text-primary uppercase">
                {LANDING_COPY_KO.comparison.tag}
              </div>
              <h2 className="text-2xl sm:text-4xl font-bold tracking-tight text-foreground">
                {LANDING_COPY_KO.comparison.title}
              </h2>
              <p className="text-sm font-normal text-muted-foreground">
                {LANDING_COPY_KO.comparison.subtitle}
              </p>
            </div>

            <div
              data-animate-item
              className="max-w-2xl mx-auto space-y-5 border-y border-border/70 py-6"
            >
              <div className="space-y-2">
                <div className="flex items-center justify-between text-xs sm:text-sm">
                  <span className="font-semibold text-foreground">RuBeacon</span>
                  <span className="font-mono font-bold text-primary">
                    {LANDING_COPY_KO.comparison.rubeaconSpeed}
                  </span>
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
                  <span className="font-normal">{LANDING_COPY_KO.comparison.legacyTitle}</span>
                  <span className="font-mono">{LANDING_COPY_KO.comparison.legacySpeed}</span>
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
                {LANDING_COPY_KO.comparison.setupGuideTag}
              </span>
            </div>

            <div className="grid grid-cols-1 md:grid-cols-3 gap-6 text-center">
              {LANDING_COPY_KO.comparison.setupSteps.map((stp) => (
                <div key={stp.num} data-animate-item className="space-y-1.5 pt-4 border-t border-border/60">
                  <div className="font-mono text-xs sm:text-sm text-primary font-bold">{stp.num}</div>
                  <h3 className="font-semibold text-base text-foreground">{stp.title}</h3>
                  <p className="text-sm font-normal text-muted-foreground">{stp.desc}</p>
                </div>
              ))}
            </div>
          </div>

          <div data-animate-item className="text-center space-y-5 pt-2">
            <h3 className="text-xl sm:text-2xl font-bold tracking-tight text-foreground">
              {LANDING_COPY_KO.comparison.ctaHeadline}
            </h3>

            <div className="flex flex-wrap items-center justify-center gap-3">
              <a href={release.downloadUrl} target="_blank" rel="noopener noreferrer">
                <Button
                  variant="outline"
                  size="lg"
                  className="h-11 px-6 text-xs sm:text-sm border-border hover:bg-muted/40 font-medium gap-2"
                >
                  <Download className="w-4 h-4" />
                  {LANDING_COPY_KO.comparison.ctaPluginDownload} ({release.tagName})
                </Button>
              </a>
              <Link href="/app">
                <Button
                  size="lg"
                  className="h-11 px-7 text-xs sm:text-sm bg-primary hover:bg-primary/90 text-primary-foreground font-semibold gap-2"
                >
                  {LANDING_COPY_KO.comparison.ctaStart}
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
            {LANDING_COPY_KO.header.nav.map((item) => (
              <Link key={item.href} href={item.href} className="hover:text-foreground transition-colors">
                {item.label}
              </Link>
            ))}
            <Link href="/admin" className="hover:text-foreground transition-colors">
              관리 콘솔
            </Link>
          </div>
        </footer>
      </section>
    </div>
  );
}
