"use client";

import React, { useEffect, useRef, useState } from "react";
import anime from "animejs";
import { ChevronDown } from "lucide-react";

const COS30 = Math.cos(Math.PI / 6);
const SIN30 = Math.sin(Math.PI / 6);

interface TierState {
  size: number;
  baseY: number;
  offsetY: number;
  angleDeg: number;
  height: number;
}

function projectIso(
  x: number,
  y: number,
  z: number,
  angleDeg: number,
  cx: number,
  cy: number
): [number, number] {
  const rad = (angleDeg * Math.PI) / 180;
  const cosA = Math.cos(rad);
  const sinA = Math.sin(rad);
  const rx = x * cosA - z * sinA;
  const rz = x * sinA + z * cosA;
  const screenX = cx + (rx - rz) * COS30;
  const screenY = cy + (rx + rz) * SIN30 - y;
  return [screenX, screenY];
}

/**
 * 1:1:1 정육면체(Cube) 비율을 유지하는 등각 투영 피라미드 층 경로 생성.
 * 상단 격자뿐만 아니라 좌·우 측면의 수직 블록 경계선도 함께 계산하여 정육면체 입체감을 보장한다.
 */
function buildCubeTierPaths(
  size: number,
  unit: number,
  yTop: number,
  thickness: number,
  angleDeg: number,
  cx: number,
  cy: number
) {
  const half = (size * unit) / 2;
  const yBot = yTop - thickness;

  // 4개 상단 코너 (0: -X,-Z / 1: +X,-Z / 2: +X,+Z / 3: -X,+Z)
  const topCorners = [
    projectIso(-half, yTop, -half, angleDeg, cx, cy),
    projectIso(half, yTop, -half, angleDeg, cx, cy),
    projectIso(half, yTop, half, angleDeg, cx, cy),
    projectIso(-half, yTop, half, angleDeg, cx, cy),
  ];

  // 4개 하단 코너
  const botCorners = [
    projectIso(-half, yBot, -half, angleDeg, cx, cy),
    projectIso(half, yBot, -half, angleDeg, cx, cy),
    projectIso(half, yBot, half, angleDeg, cx, cy),
    projectIso(-half, yBot, half, angleDeg, cx, cy),
  ];

  const topPolygon = topCorners.map((p) => `${p[0].toFixed(1)},${p[1].toFixed(1)}`).join(" ");

  // 4개 측면의 정규 법선 벡터 정의 및 회전 후 카메라 가시성(Backface Culling) 판별
  // 등각 투영(Isometric)에서 카메라는 (+X, +Z) 위쪽에서 원점을 바라봄.
  // 따라서 Y축 회전 후 법선 벡터의 (rx + rz)가 양수이면 관측자를 향하는 전면(Front face)임.
  const rad = (angleDeg * Math.PI) / 180;
  const cosA = Math.cos(rad);
  const sinA = Math.sin(rad);

  const sideDefs = [
    {
      axis: "X+",
      poly: [topCorners[1], topCorners[2], botCorners[2], botCorners[1]],
      // 법선 (1, 0): 회전 후 rx = cosA, rz = sinA
      camDot: cosA + sinA,
      screenXDir: cosA - sinA,
      gridGen: (pos: number) => {
        const [tA, tB] = projectIso(half, yTop, pos, angleDeg, cx, cy);
        const [bA, bB] = projectIso(half, yBot, pos, angleDeg, cx, cy);
        return `M${tA.toFixed(1)},${tB.toFixed(1)} L${bA.toFixed(1)},${bB.toFixed(1)}`;
      },
    },
    {
      axis: "Z+",
      poly: [topCorners[2], topCorners[3], botCorners[3], botCorners[2]],
      // 법선 (0, 1): 회전 후 rx = -sinA, rz = cosA
      camDot: -sinA + cosA,
      screenXDir: -sinA - cosA,
      gridGen: (pos: number) => {
        const [tA, tB] = projectIso(pos, yTop, half, angleDeg, cx, cy);
        const [bA, bB] = projectIso(pos, yBot, half, angleDeg, cx, cy);
        return `M${tA.toFixed(1)},${tB.toFixed(1)} L${bA.toFixed(1)},${bB.toFixed(1)}`;
      },
    },
    {
      axis: "X-",
      poly: [topCorners[3], topCorners[0], botCorners[0], botCorners[3]],
      // 법선 (-1, 0): 회전 후 rx = -cosA, rz = -sinA
      camDot: -cosA - sinA,
      screenXDir: -cosA + sinA,
      gridGen: (pos: number) => {
        const [tA, tB] = projectIso(-half, yTop, pos, angleDeg, cx, cy);
        const [bA, bB] = projectIso(-half, yBot, pos, angleDeg, cx, cy);
        return `M${tA.toFixed(1)},${tB.toFixed(1)} L${bA.toFixed(1)},${bB.toFixed(1)}`;
      },
    },
    {
      axis: "Z-",
      poly: [topCorners[0], topCorners[1], botCorners[1], botCorners[0]],
      // 법선 (0, -1): 회전 후 rx = sinA, rz = -cosA
      camDot: sinA - cosA,
      screenXDir: sinA + cosA,
      gridGen: (pos: number) => {
        const [tA, tB] = projectIso(pos, yTop, -half, angleDeg, cx, cy);
        const [bA, bB] = projectIso(pos, yBot, -half, angleDeg, cx, cy);
        return `M${tA.toFixed(1)},${tB.toFixed(1)} L${bA.toFixed(1)},${bB.toFixed(1)}`;
      },
    },
  ];

  // 관측자 방향을 향하는 가시 전면들 (camDot >= -1e-4) 추출 후 화면 X 좌표 기준으로 정렬 (좌측 -> 우측)
  const visibleSides = sideDefs
    .filter((s) => s.camDot > -1e-4)
    .sort((a, b) => a.screenXDir - b.screenXDir);

  const leftSide = visibleSides[0] || sideDefs[1];
  const rightSide = visibleSides[visibleSides.length - 1] || sideDefs[0];

  const leftFace = leftSide.poly.map((p) => `${p[0].toFixed(1)},${p[1].toFixed(1)}`).join(" ");
  const rightFace = rightSide.poly.map((p) => `${p[0].toFixed(1)},${p[1].toFixed(1)}`).join(" ");

  // 상단 면 N x N 격자선 + 카메라를 향하는 가시 측면의 수직 분할선 동적 계산
  const gridLines: string[] = [];
  for (let i = 1; i < size; i++) {
    const pos = -half + i * unit;

    // 상단 면 내부 격자선
    const [x1, y1] = projectIso(pos, yTop, -half, angleDeg, cx, cy);
    const [x2, y2] = projectIso(pos, yTop, half, angleDeg, cx, cy);
    gridLines.push(`M${x1.toFixed(1)},${y1.toFixed(1)} L${x2.toFixed(1)},${y2.toFixed(1)}`);

    const [zx1, zy1] = projectIso(-half, yTop, pos, angleDeg, cx, cy);
    const [zx2, zy2] = projectIso(half, yTop, pos, angleDeg, cx, cy);
    gridLines.push(`M${zx1.toFixed(1)},${zy1.toFixed(1)} L${zx2.toFixed(1)},${zy2.toFixed(1)}`);

    // 가시적인 전면 측면에만 수직 블록 분할선 동적 투영
    visibleSides.forEach((s) => {
      gridLines.push(s.gridGen(pos));
    });
  }

  // 상단 4개 모서리 외곽선
  const edges: string[] = [];
  for (let i = 0; i < 4; i++) {
    const next = (i + 1) % 4;
    edges.push(
      `M${topCorners[i][0].toFixed(1)},${topCorners[i][1].toFixed(1)} L${topCorners[next][0].toFixed(1)},${topCorners[next][1].toFixed(1)}`
    );
  }

  // 가시 전면의 수직 모서리선 및 밑변선만 외곽선으로 추가 (뒤쪽 숨은선 제거)
  visibleSides.forEach((s) => {
    const p = s.poly;
    edges.push(`M${p[0][0].toFixed(1)},${p[0][1].toFixed(1)} L${p[3][0].toFixed(1)},${p[3][1].toFixed(1)}`);
    edges.push(`M${p[1][0].toFixed(1)},${p[1][1].toFixed(1)} L${p[2][0].toFixed(1)},${p[2][1].toFixed(1)}`);
    edges.push(`M${p[3][0].toFixed(1)},${p[3][1].toFixed(1)} L${p[2][0].toFixed(1)},${p[2][1].toFixed(1)}`);
  });

  return {
    topPolygon,
    leftFace,
    rightFace,
    gridPath: gridLines.join(" "),
    edgePath: edges.join(" "),
  };
}

export interface ReleaseInfo {
  tagName: string;
  downloadUrl: string;
}

export function useLatestRelease(): ReleaseInfo {
  const [release, setRelease] = useState<ReleaseInfo>({
    tagName: "v1.0.0",
    downloadUrl: "https://github.com/mmmphyun/ru-beacon/releases/latest",
  });

  useEffect(() => {
    let mounted = true;
    fetch("https://api.github.com/repos/mmmphyun/ru-beacon/releases/latest")
      .then((res) => (res.ok ? res.json() : null))
      .then((data) => {
        if (!mounted || !data) return;
        const tag = typeof data.tag_name === "string" ? data.tag_name : "v1.0.0";
        const jarAsset = Array.isArray(data.assets)
          ? data.assets.find(
              (a: { name?: string; browser_download_url?: string }) =>
                typeof a.name === "string" && a.name.endsWith(".jar")
            )
          : null;
        setRelease({
          tagName: tag,
          downloadUrl:
            jarAsset?.browser_download_url ||
            data.html_url ||
            "https://github.com/mmmphyun/ru-beacon/releases/latest",
        });
      })
      .catch(() => {});
    return () => {
      mounted = false;
    };
  }, []);

  return release;
}

export function BeaconStage() {
  const UNIT = 20; // 가로:세로:높이 = 20:20:20 정육면체(1:1:1) 비율 고정
  const containerRef = useRef<HTMLElement>(null);

  const [tiers, setTiers] = useState<{
    t7: TierState;
    t5: TierState;
    t3: TierState;
    beacon: TierState;
    beamScale: number;
    glowOpacity: number;
    heartbeat: number;
  }>({
    t7: { size: 7, baseY: -UNIT * 2, offsetY: -14, angleDeg: -12, height: UNIT },
    t5: { size: 5, baseY: -UNIT, offsetY: -6, angleDeg: 16, height: UNIT },
    t3: { size: 3, baseY: 0, offsetY: 6, angleDeg: -20, height: UNIT },
    beacon: { size: 1, baseY: UNIT, offsetY: 44, angleDeg: 45, height: UNIT },
    beamScale: 0,
    glowOpacity: 0,
    heartbeat: 0,
  });

  // 스크롤 진행률 (0: 초기 90도 수직 헤더 상태 -> 1: 비콘과 헤더가 동반 회전하여 0도 수평 정렬 완료)
  const [scrollProgress, setScrollProgress] = useState(0);
  const hasDockedRef = useRef(false);
  const heartbeatAnimRef = useRef<anime.AnimeInstance | null>(null);

  const animStateRef = useRef({
    t7Offset: -14,
    t7Angle: -12,
    t5Offset: -6,
    t5Angle: 16,
    t3Offset: 6,
    t3Angle: -20,
    beaconOffset: 44,
    beaconAngle: 45,
    beamScale: 0,
    glowOpacity: 0,
    heartbeat: 0,
  });

  const syncState = () => {
    const state = animStateRef.current;
    setTiers({
      t7: { size: 7, baseY: -UNIT * 2, offsetY: state.t7Offset, angleDeg: state.t7Angle, height: UNIT },
      t5: { size: 5, baseY: -UNIT, offsetY: state.t5Offset, angleDeg: state.t5Angle, height: UNIT },
      t3: { size: 3, baseY: 0, offsetY: state.t3Offset, angleDeg: state.t3Angle, height: UNIT },
      beacon: {
        size: 1,
        baseY: UNIT,
        offsetY: state.beaconOffset,
        angleDeg: state.beaconAngle,
        height: UNIT,
      },
      beamScale: state.beamScale,
      glowOpacity: state.glowOpacity,
      heartbeat: state.heartbeat,
    });
  };

  const startHeartbeatLoop = () => {
    if (heartbeatAnimRef.current) return;
    const state = animStateRef.current;
    // 비콘 점화 후 은은한 심장박동(Lub-Dub) 루프
    heartbeatAnimRef.current = anime({
      targets: state,
      heartbeat: [
        { value: 0.45, duration: 160, easing: "easeOutQuad" },
        { value: 0.1, duration: 130, easing: "easeInQuad" },
        { value: 0.9, duration: 190, easing: "easeOutCubic" },
        { value: 0, duration: 750, easing: "easeOutSine" },
      ],
      endDelay: 950,
      loop: true,
      update: syncState,
    });
  };

  const triggerDockingSequence = () => {
    if (hasDockedRef.current) return;
    hasDockedRef.current = true;

    const state = animStateRef.current;
    const tl = anime.timeline({
      update: syncState,
      complete: () => {
        startHeartbeatLoop();
      },
    });

    tl.add({
      targets: state,
      t7Angle: 0,
      t5Angle: 0,
      t3Angle: 0,
      beaconAngle: 0,
      t7Offset: 0,
      t5Offset: 0,
      t3Offset: 0,
      beaconOffset: 0,
      duration: 850,
      easing: "easeInOutQuart",
    }).add({
      targets: state,
      beaconOffset: [
        { value: -11, duration: 110, easing: "easeOutQuad" },
        { value: 0, duration: 650, easing: "easeOutElastic(1, .5)" },
      ],
      t3Offset: [
        { value: -10, duration: 120, delay: 20, easing: "easeOutQuad" },
        { value: 0, duration: 650, easing: "easeOutElastic(1, .5)" },
      ],
      t5Offset: [
        { value: -8, duration: 130, delay: 50, easing: "easeOutQuad" },
        { value: 0, duration: 650, easing: "easeOutElastic(1, .55)" },
      ],
      t7Offset: [
        { value: -5, duration: 140, delay: 80, easing: "easeOutQuad" },
        { value: 0, duration: 650, easing: "easeOutElastic(1, .6)" },
      ],
      beamScale: [{ value: 1, duration: 700, delay: 90, easing: "easeOutExpo" }],
      glowOpacity: [{ value: 1, duration: 600, delay: 90, easing: "easeOutQuad" }],
    });
  };

  useEffect(() => {
    const prefersReduced =
      typeof window !== "undefined" &&
      window.matchMedia &&
      window.matchMedia("(prefers-reduced-motion: reduce)").matches;

    if (prefersReduced) {
      hasDockedRef.current = true;
      setTiers({
        t7: { size: 7, baseY: -UNIT * 2, offsetY: 0, angleDeg: 0, height: UNIT },
        t5: { size: 5, baseY: -UNIT, offsetY: 0, angleDeg: 0, height: UNIT },
        t3: { size: 3, baseY: 0, offsetY: 0, angleDeg: 0, height: UNIT },
        beacon: { size: 1, baseY: UNIT, offsetY: 0, angleDeg: 0, height: UNIT },
        beamScale: 1,
        glowOpacity: 1,
        heartbeat: 0.3,
      });
      return;
    }

    const timer = setTimeout(() => {
      triggerDockingSequence();
    }, 300);

    const onScroll = () => {
      const y = window.scrollY;
      if (y > 5 && !hasDockedRef.current) {
        triggerDockingSequence();
      }
      const vh = window.innerHeight || 800;
      // 스크롤 0 ~ 0.65vh 구간 동안 광선 옆 -90도 헤더와 비콘이 함께 90도 회전하여 0도 수평 정렬됨
      const rotProgress = Math.max(0, Math.min(1, y / (vh * 0.65)));
      setScrollProgress(rotProgress);
    };

    window.addEventListener("scroll", onScroll, { passive: true });
    return () => {
      clearTimeout(timer);
      window.removeEventListener("scroll", onScroll);
      if (heartbeatAnimRef.current) {
        heartbeatAnimRef.current.pause();
        heartbeatAnimRef.current = null;
      }
    };
  }, []);

  const cx = 300;
  const cy = 285;

  // 스크롤에 따라 비콘 피라미드 전체가 헤더와 함께 동반 회전 (0deg -> 90deg)
  const scrollRotationDeg = scrollProgress * 90;
  // 광선 옆에 -90도로 서 있던 헤더가 스크롤에 따라 사람이 읽기 편한 0도 수평으로 회전 (-90deg -> 0deg)
  const headerRotationDeg = -90 + scrollProgress * 90;

  // 한글 서브 슬로건: 애초에 정방향(0deg 수평) 유지
  // 로고가 -90도(수직)에서 회전하여 6시 방향 공간이 완전히 비어나는 시점(0.50)부터 알파값을 0 -> 1로 점진적으로 높여 노출
  const SLOGAN_START_THRESHOLD = 0.5;
  const sloganProgress = Math.max(
    0,
    Math.min(1, (scrollProgress - SLOGAN_START_THRESHOLD) / (1 - SLOGAN_START_THRESHOLD))
  );
  const sloganOpacity = sloganProgress;
  const sloganLiftY = (1 - sloganProgress) * 18;

  const s7 = buildCubeTierPaths(
    7,
    UNIT,
    tiers.t7.baseY + tiers.t7.offsetY,
    tiers.t7.height,
    tiers.t7.angleDeg + scrollRotationDeg,
    cx,
    cy
  );
  const s5 = buildCubeTierPaths(
    5,
    UNIT,
    tiers.t5.baseY + tiers.t5.offsetY,
    tiers.t5.height,
    tiers.t5.angleDeg + scrollRotationDeg,
    cx,
    cy
  );
  const s3 = buildCubeTierPaths(
    3,
    UNIT,
    tiers.t3.baseY + tiers.t3.offsetY,
    tiers.t3.height,
    tiers.t3.angleDeg + scrollRotationDeg,
    cx,
    cy
  );
  const sBeacon = buildCubeTierPaths(
    1,
    UNIT,
    tiers.beacon.baseY + tiers.beacon.offsetY,
    tiers.beacon.height,
    tiers.beacon.angleDeg + scrollRotationDeg,
    cx,
    cy
  );

  const [, beaconTopY] = projectIso(
    0,
    tiers.beacon.baseY + tiers.beacon.offsetY,
    0,
    tiers.beacon.angleDeg + scrollRotationDeg,
    cx,
    cy
  );

  // 심장박동(Lub-Dub) 수치가 반영된 광선 폭 및 발광 강도
  const pulseBoost = tiers.heartbeat;
  const activeGlow = Math.min(1, tiers.glowOpacity + pulseBoost * 0.35);
  const beamHalfWidth = 10 + pulseBoost * 6;

  const scrollToNext = () => {
    const el = document.getElementById("hero");
    if (el && typeof el.scrollIntoView === "function") {
      el.scrollIntoView({ behavior: "smooth" });
    }
  };

  return (
    <section
      ref={containerRef}
      aria-label="RuBeacon Signal Stage"
      className="relative w-full h-[165dvh] border-b border-border/60 select-none"
    >
      <div className="sticky top-14 w-full h-[calc(100dvh-3.5rem)] min-h-[560px] flex flex-col items-center justify-between overflow-hidden">
        {/* 심장박동과 연동되는 정제된 딥 루비 앰비언트 반사광 (탁한 샌드 혼합 배제) */}
        <div
          className="pointer-events-none absolute inset-0"
          style={{
            opacity: activeGlow * 0.9,
            transform: `scale(${1 + pulseBoost * 0.05})`,
            background:
              "radial-gradient(circle at 50% 45%, rgba(225, 29, 72, 0.08) 0%, rgba(225, 29, 72, 0.02) 42%, transparent 70%)",
          }}
        />

        {/* 네 모서리 코너 프레이밍 (안티앨리어싱 깨짐 방지를 위해 크기 및 굵기 상향) */}
        <div className="w-full max-w-6xl mx-auto px-6 pt-6 flex items-center justify-between text-xs sm:text-[13px] font-normal tracking-[0.22em] text-muted-foreground uppercase z-20">
          <span>0.00ms Tick Occupancy</span>
          <span>LMAX Disruptor Core</span>
        </div>

        {/* 중앙 무대: 광선 축 90도 회전 타이포그래피(배경/오버랩) + 정중앙 정육면체 루비 비콘 피라미드(전경) */}
        <div className="relative w-full max-w-5xl flex-1 flex items-center justify-center px-4">
          {/*
            1) 광선 축 회전 브랜드 워드마크 (z-0: 비콘 블록 뒤편에 입체적으로 오버랩됨)
          */}
          <div
            className="pointer-events-none absolute inset-0 flex items-center justify-center z-0"
            style={{
              transform: `rotate(${headerRotationDeg}deg) translateY(${(1 - scrollProgress) * -22}px)`,
              transformOrigin: "50% 48%",
            }}
          >
            <div
              aria-label="Ru-Beacon Brand Headline"
              className="headline italic text-5xl sm:text-7xl md:text-[6.5rem] leading-none tracking-tight text-foreground/90 whitespace-nowrap"
            >
              <em>
                <span>Ru</span>
                <span className="relative inline-flex items-center mx-1 sm:mx-1.5">
                  <span className="opacity-25 font-normal">by</span>
                  {/* by 위를 가로지르며 Ru-Beacon의 하이픈(-) 역할을 겸하는 굵고 선명한 루비 하이픈 스트라이크 */}
                  <span
                    className="absolute left-[-10%] right-[-10%] top-1/2 -translate-y-1/2 h-[4px] sm:h-[6px] bg-primary rounded-xs -rotate-6 shadow-[0_0_16px_rgba(225,29,72,0.9)]"
                    aria-hidden="true"
                  />
                </span>
                <span>Beacon</span>
              </em>
            </div>
          </div>

          {/*
            2) 정방향 유지 한글 서브 슬로건: 로고 회전 공간 확보 시점부터 알파값 상승 페이드인
          */}
          <div
            className="pointer-events-none absolute inset-0 flex items-center justify-center z-20"
            style={{
              transform: `translateY(${sloganLiftY}px)`,
              opacity: sloganOpacity,
              transition: "opacity 120ms ease-out, transform 120ms ease-out",
            }}
          >
            <p className="translate-y-[170px] sm:translate-y-[195px] md:translate-y-[218px] text-sm sm:text-base font-medium tracking-[0.22em] text-foreground/90 whitespace-nowrap">
              마인크래프트 · 디스코드 실시간 연동 서비스
            </p>
          </div>

          {/* 정중앙 정육면체(1:1:1) 7x7, 5x5, 3x3 피라미드 & 심장박동 광선 SVG (전경 z-10 배치로 타이포와 입체적 오버랩) */}
          <div className="relative z-10 w-full max-w-[580px] aspect-[6/5] flex items-center justify-center pointer-events-none">
            <svg
              viewBox="0 0 600 460"
              className="w-full h-full overflow-visible"
              role="img"
              aria-label="7x7, 5x5, 3x3 루비 비콘 피라미드"
            >
              <defs>
                <linearGradient id="rubyBeamGrad" x1="0" y1="1" x2="0" y2="0">
                  <stop offset="0%" stopColor="#E11D48" stopOpacity={0.55 + pulseBoost * 0.25} />
                  <stop offset="50%" stopColor="#E11D48" stopOpacity={0.18 + pulseBoost * 0.12} />
                  <stop offset="100%" stopColor="#E11D48" stopOpacity="0" />
                </linearGradient>
                <linearGradient id="rubyCoreLine" x1="0" y1="1" x2="0" y2="0">
                  <stop offset="0%" stopColor="#F43F5E" stopOpacity="1" />
                  <stop offset="75%" stopColor="#E11D48" stopOpacity={0.55 + pulseBoost * 0.25} />
                  <stop offset="100%" stopColor="#E11D48" stopOpacity="0" />
                </linearGradient>
              </defs>

              {/* 1. 7x7 하단 정육면체 샌드(Sand) 광석 베이스 층 */}
              <g className="text-foreground">
                <polygon points={s7.leftFace} fill="hsl(var(--background))" fillOpacity="0.84" />
                <polygon points={s7.rightFace} fill="hsl(var(--background))" fillOpacity="0.9" />
                <polygon points={s7.topPolygon} fill="hsl(var(--background))" fillOpacity="0.8" />
                <polygon points={s7.leftFace} fill="hsl(var(--sand))" fillOpacity="0.12" />
                <polygon points={s7.rightFace} fill="hsl(var(--sand))" fillOpacity="0.18" />
                <polygon points={s7.topPolygon} fill="hsl(var(--sand))" fillOpacity="0.08" />
                <path
                  d={s7.gridPath}
                  stroke="hsl(var(--sand))"
                  strokeOpacity={0.32 + activeGlow * 0.12}
                  strokeWidth="0.8"
                  fill="none"
                />
                <path
                  d={s7.edgePath}
                  stroke="hsl(var(--sand))"
                  strokeOpacity={0.55 + activeGlow * 0.2}
                  strokeWidth="1.1"
                  fill="none"
                />
              </g>

              {/* 2. 5x5 중단 정육면체 샌드-루비 브릿지 광석 층 */}
              <g className="text-foreground">
                <polygon points={s5.leftFace} fill="hsl(var(--background))" fillOpacity="0.86" />
                <polygon points={s5.rightFace} fill="hsl(var(--background))" fillOpacity="0.92" />
                <polygon points={s5.topPolygon} fill="hsl(var(--background))" fillOpacity="0.84" />
                <polygon points={s5.leftFace} fill="hsl(var(--sand))" fillOpacity="0.16" />
                <polygon points={s5.rightFace} fill="hsl(var(--sand))" fillOpacity="0.24" />
                <polygon points={s5.topPolygon} fill="hsl(var(--sand))" fillOpacity="0.1" />
                <path
                  d={s5.gridPath}
                  stroke="hsl(var(--sand))"
                  strokeOpacity={0.38 + activeGlow * 0.15}
                  strokeWidth="0.85"
                  fill="none"
                />
                <path
                  d={s5.edgePath}
                  stroke={tiers.glowOpacity > 0.5 ? "#E11D48" : "hsl(var(--sand))"}
                  strokeOpacity={0.48 + activeGlow * 0.3}
                  strokeWidth="1.15"
                  fill="none"
                />
              </g>

              {/* 3. 3x3 상단 정육면체 루비 광석 층 */}
              <g className="text-foreground">
                <polygon points={s3.leftFace} fill="hsl(var(--background))" fillOpacity="0.88" />
                <polygon points={s3.rightFace} fill="hsl(var(--background))" fillOpacity="0.92" />
                <polygon points={s3.topPolygon} fill="hsl(var(--background))" fillOpacity="0.85" />
                <polygon points={s3.leftFace} fill="#E11D48" fillOpacity="0.08" />
                <polygon points={s3.rightFace} fill="#E11D48" fillOpacity="0.14" />
                <polygon points={s3.topPolygon} fill="#E11D48" fillOpacity="0.06" />
                <path
                  d={s3.gridPath}
                  stroke="#E11D48"
                  strokeOpacity={0.28 + activeGlow * 0.28}
                  strokeWidth="0.85"
                  fill="none"
                />
                <path
                  d={s3.edgePath}
                  stroke="#E11D48"
                  strokeOpacity={0.55 + activeGlow * 0.4}
                  strokeWidth="1.25"
                  fill="none"
                />
              </g>

              {/* 4. 최상단 1x1 정육면체 루비 비콘 코어 블록 (광선의 발원체이므로 광선보다 먼저/뒤편에 입체 렌더링) */}
              <g>
                <polygon
                  points={sBeacon.leftFace}
                  fill="#E11D48"
                  fillOpacity={0.28 + activeGlow * 0.32}
                />
                <polygon
                  points={sBeacon.rightFace}
                  fill="#BE123C"
                  fillOpacity={0.38 + activeGlow * 0.35}
                />
                <polygon
                  points={sBeacon.topPolygon}
                  fill="#F43F5E"
                  fillOpacity={0.35 + activeGlow * 0.42}
                />
                <path
                  d={sBeacon.edgePath}
                  stroke="#F43F5E"
                  strokeOpacity={0.9}
                  strokeWidth={1.4 + pulseBoost * 0.4}
                  fill="none"
                />
              </g>

              {/* 5. 비콘 블록 상단 면에서 뿜어져 나오는 수직 루비 비콘 광선 */}
              {tiers.beamScale > 0.01 && (
                <g>
                  <rect
                    x={cx - beamHalfWidth}
                    y={beaconTopY - 340 * tiers.beamScale}
                    width={beamHalfWidth * 2}
                    height={340 * tiers.beamScale}
                    fill="url(#rubyBeamGrad)"
                  />
                  <line
                    x1={cx}
                    y1={beaconTopY}
                    x2={cx}
                    y2={beaconTopY - 360 * tiers.beamScale}
                    stroke="url(#rubyCoreLine)"
                    strokeWidth={1.5 + pulseBoost * 1.1}
                  />
                </g>
              )}
            </svg>
          </div>
        </div>

        {/* 하단 코너 프레이밍 & 중앙 Scroll 꺽쇄 */}
        <div className="w-full max-w-6xl mx-auto px-6 pb-8 flex items-end justify-between z-20">
          <span className="hidden sm:inline-block text-xs sm:text-[13px] font-normal tracking-[0.22em] text-muted-foreground uppercase">
            Minecraft ↔ Discord
          </span>

          <button
            type="button"
            onClick={scrollToNext}
            className="mx-auto sm:mx-0 flex flex-col items-center gap-1.5 text-muted-foreground hover:text-foreground transition-colors group focus:outline-none"
            aria-label="아래로 스크롤"
          >
            <span className="text-xs font-normal tracking-[0.24em] uppercase opacity-80 group-hover:opacity-100 transition-opacity">
              Scroll
            </span>
            <ChevronDown className="w-4 h-4 opacity-80 group-hover:translate-y-0.5 transition-transform" />
          </button>

          <span className="hidden sm:inline-block text-xs sm:text-[13px] font-normal tracking-[0.22em] text-muted-foreground uppercase">
            WSS Single-Hop Bridge
          </span>
        </div>
      </div>
    </section>
  );
}
