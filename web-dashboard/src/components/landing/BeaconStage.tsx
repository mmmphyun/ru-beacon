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

function buildSlabPaths(
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

  // 상단 4개 꼭짓점: (-half,-half), (half,-half), (half,half), (-half,half)
  const topCorners = [
    projectIso(-half, yTop, -half, angleDeg, cx, cy),
    projectIso(half, yTop, -half, angleDeg, cx, cy),
    projectIso(half, yTop, half, angleDeg, cx, cy),
    projectIso(-half, yTop, half, angleDeg, cx, cy),
  ];

  const botCorners = [
    projectIso(-half, yBot, -half, angleDeg, cx, cy),
    projectIso(half, yBot, -half, angleDeg, cx, cy),
    projectIso(half, yBot, half, angleDeg, cx, cy),
    projectIso(-half, yBot, half, angleDeg, cx, cy),
  ];

  const topPolygon = topCorners.map((p) => `${p[0].toFixed(1)},${p[1].toFixed(1)}`).join(" ");

  // 전면 좌/우 측면 폴리곤 (시점 기준 y값이 큰 하단 3개 꼭짓점 연결)
  const leftFace = [topCorners[3], topCorners[2], botCorners[2], botCorners[3]]
    .map((p) => `${p[0].toFixed(1)},${p[1].toFixed(1)}`)
    .join(" ");
  const rightFace = [topCorners[1], topCorners[2], botCorners[2], botCorners[1]]
    .map((p) => `${p[0].toFixed(1)},${p[1].toFixed(1)}`)
    .join(" ");

  // 내부 격자선 (N x N 블록 경계)
  const gridLines: string[] = [];
  for (let i = 1; i < size; i++) {
    const pos = -half + i * unit;
    const [x1, y1] = projectIso(pos, yTop, -half, angleDeg, cx, cy);
    const [x2, y2] = projectIso(pos, yTop, half, angleDeg, cx, cy);
    gridLines.push(`M${x1.toFixed(1)},${y1.toFixed(1)} L${x2.toFixed(1)},${y2.toFixed(1)}`);

    const [zx1, zy1] = projectIso(-half, yTop, pos, angleDeg, cx, cy);
    const [zx2, zy2] = projectIso(half, yTop, pos, angleDeg, cx, cy);
    gridLines.push(`M${zx1.toFixed(1)},${zy1.toFixed(1)} L${zx2.toFixed(1)},${zy2.toFixed(1)}`);
  }

  // 외곽 테두리 및 수직 모서리
  const edges: string[] = [];
  for (let i = 0; i < 4; i++) {
    const next = (i + 1) % 4;
    edges.push(
      `M${topCorners[i][0].toFixed(1)},${topCorners[i][1].toFixed(1)} L${topCorners[next][0].toFixed(1)},${topCorners[next][1].toFixed(1)}`
    );
    edges.push(
      `M${topCorners[i][0].toFixed(1)},${topCorners[i][1].toFixed(1)} L${botCorners[i][0].toFixed(1)},${botCorners[i][1].toFixed(1)}`
    );
  }
  // 하단 전면 테두리
  edges.push(
    `M${botCorners[3][0].toFixed(1)},${botCorners[3][1].toFixed(1)} L${botCorners[2][0].toFixed(1)},${botCorners[2][1].toFixed(1)} L${botCorners[1][0].toFixed(1)},${botCorners[1][1].toFixed(1)}`
  );

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
      .catch(() => {
        // 네트워크 차단 및 API 한도 초과 시 기본 릴리스 경로 유지
      });
    return () => {
      mounted = false;
    };
  }, []);

  return release;
}

export function BeaconStage() {
  const [tiers, setTiers] = useState<{
    t7: TierState;
    t5: TierState;
    t3: TierState;
    beacon: TierState;
    beamScale: number;
    glowOpacity: number;
  }>({
    t7: { size: 7, baseY: -36, offsetY: -14, angleDeg: -12, height: 14 },
    t5: { size: 5, baseY: -18, offsetY: -6, angleDeg: 16, height: 14 },
    t3: { size: 3, baseY: 0, offsetY: 6, angleDeg: -20, height: 14 },
    beacon: { size: 1, baseY: 18, offsetY: 44, angleDeg: 45, height: 18 },
    beamScale: 0,
    glowOpacity: 0,
  });

  const [scrollFade, setScrollFade] = useState(1);
  const hasDockedRef = useRef(false);
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
  });

  const triggerDockingSequence = () => {
    if (hasDockedRef.current) return;
    hasDockedRef.current = true;

    const state = animStateRef.current;
    const syncState = () => {
      setTiers({
        t7: { size: 7, baseY: -36, offsetY: state.t7Offset, angleDeg: state.t7Angle, height: 14 },
        t5: { size: 5, baseY: -18, offsetY: state.t5Offset, angleDeg: state.t5Angle, height: 14 },
        t3: { size: 3, baseY: 0, offsetY: state.t3Offset, angleDeg: state.t3Angle, height: 14 },
        beacon: {
          size: 1,
          baseY: 18,
          offsetY: state.beaconOffset,
          angleDeg: state.beaconAngle,
          height: 18,
        },
        beamScale: state.beamScale,
        glowOpacity: state.glowOpacity,
      });
    };

    const tl = anime.timeline({
      update: syncState,
    });

    // 1단계: 회전 정렬 및 비콘 하강 결합
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
    })
      // 2단계: 자석 결합 반발력 (위에서 아래로 시간차 압축 후 탄성 복원)
      .add({
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
        t7: { size: 7, baseY: -36, offsetY: 0, angleDeg: 0, height: 14 },
        t5: { size: 5, baseY: -18, offsetY: 0, angleDeg: 0, height: 14 },
        t3: { size: 3, baseY: 0, offsetY: 0, angleDeg: 0, height: 14 },
        beacon: { size: 1, baseY: 18, offsetY: 0, angleDeg: 0, height: 18 },
        beamScale: 1,
        glowOpacity: 1,
      });
      return;
    }

    // 첫 진입 후 350ms 뒤 결합 및 반발 시퀀스 실행
    const timer = setTimeout(() => {
      triggerDockingSequence();
    }, 350);

    const onScroll = () => {
      const y = window.scrollY;
      if (y > 10 && !hasDockedRef.current) {
        triggerDockingSequence();
      }
      const vh = window.innerHeight || 800;
      const ratio = Math.max(0, Math.min(1, 1 - y / (vh * 0.85)));
      setScrollFade(ratio);
    };

    window.addEventListener("scroll", onScroll, { passive: true });
    return () => {
      clearTimeout(timer);
      window.removeEventListener("scroll", onScroll);
    };
  }, []);

  const cx = 300;
  const cy = 280;
  const unit = 22;

  const s7 = buildSlabPaths(
    7,
    unit,
    tiers.t7.baseY + tiers.t7.offsetY,
    tiers.t7.height,
    tiers.t7.angleDeg,
    cx,
    cy
  );
  const s5 = buildSlabPaths(
    5,
    unit,
    tiers.t5.baseY + tiers.t5.offsetY,
    tiers.t5.height,
    tiers.t5.angleDeg,
    cx,
    cy
  );
  const s3 = buildSlabPaths(
    3,
    unit,
    tiers.t3.baseY + tiers.t3.offsetY,
    tiers.t3.height,
    tiers.t3.angleDeg,
    cx,
    cy
  );
  const sBeacon = buildSlabPaths(
    1,
    unit,
    tiers.beacon.baseY + tiers.beacon.offsetY,
    tiers.beacon.height,
    tiers.beacon.angleDeg,
    cx,
    cy
  );

  // 비콘 상단 정점 좌표 (광선 시작점)
  const [, beaconTopY] = projectIso(
    0,
    tiers.beacon.baseY + tiers.beacon.offsetY,
    0,
    tiers.beacon.angleDeg,
    cx,
    cy
  );

  const scrollToHero = () => {
    const el = document.getElementById("hero");
    if (el && typeof el.scrollIntoView === "function") {
      el.scrollIntoView({ behavior: "smooth" });
    }
  };

  return (
    <section
      aria-label="RuBeacon Signal Stage"
      className="relative w-full h-[calc(100dvh-3.5rem)] min-h-[540px] flex flex-col items-center justify-between overflow-hidden select-none border-b border-border/60"
    >
      {/* 배경 은은한 루비 앰비언트 반사광 (라이트/다크 자동 대응) */}
      <div
        className="pointer-events-none absolute inset-0 transition-opacity duration-700"
        style={{
          opacity: tiers.glowOpacity * scrollFade * 0.85,
          background:
            "radial-gradient(circle at 50% 42%, rgba(225, 29, 72, 0.14) 0%, rgba(225, 29, 72, 0.04) 35%, transparent 70%)",
        }}
      />

      {/* 상단 여백 균형용 빈 슬롯 */}
      <div className="h-12" />

      {/* 중앙 등각 투영 7x7, 5x5, 3x3 피라미드 & 비콘 광선 SVG */}
      <div
        className="relative w-full max-w-[600px] aspect-[6/5] flex items-center justify-center px-4"
        style={{
          opacity: Math.max(0.08, scrollFade),
          transform: `translateY(${(1 - scrollFade) * -28}px)`,
        }}
      >
        <svg
          viewBox="0 0 600 460"
          className="w-full h-full overflow-visible"
          role="img"
          aria-label="7x7, 5x5, 3x3 루비 비콘 피라미드"
        >
          <defs>
            <linearGradient id="rubyBeamGrad" x1="0" y1="1" x2="0" y2="0">
              <stop offset="0%" stopColor="#E11D48" stopOpacity="0.55" />
              <stop offset="45%" stopColor="#E11D48" stopOpacity="0.18" />
              <stop offset="100%" stopColor="#E11D48" stopOpacity="0" />
            </linearGradient>
            <linearGradient id="rubyCoreLine" x1="0" y1="1" x2="0" y2="0">
              <stop offset="0%" stopColor="#F43F5E" stopOpacity="0.95" />
              <stop offset="70%" stopColor="#E11D48" stopOpacity="0.45" />
              <stop offset="100%" stopColor="#E11D48" stopOpacity="0" />
            </linearGradient>
          </defs>

          {/* 1. 7x7 하단 광석 베이스 층 */}
          <g className="text-foreground">
            <polygon points={s7.leftFace} fill="currentColor" fillOpacity="0.03" />
            <polygon points={s7.rightFace} fill="currentColor" fillOpacity="0.06" />
            <polygon points={s7.topPolygon} fill="currentColor" fillOpacity="0.02" />
            <path
              d={s7.gridPath}
              stroke="currentColor"
              strokeOpacity={0.12 + tiers.glowOpacity * 0.08}
              strokeWidth="0.75"
              fill="none"
            />
            <path
              d={s7.edgePath}
              stroke={tiers.glowOpacity > 0.5 ? "#E11D48" : "currentColor"}
              strokeOpacity={0.28 + tiers.glowOpacity * 0.22}
              strokeWidth="1"
              fill="none"
            />
          </g>

          {/* 2. 5x5 중단 광석 층 */}
          <g className="text-foreground">
            <polygon points={s5.leftFace} fill="currentColor" fillOpacity="0.04" />
            <polygon points={s5.rightFace} fill="currentColor" fillOpacity="0.08" />
            <polygon points={s5.topPolygon} fill="currentColor" fillOpacity="0.03" />
            <path
              d={s5.gridPath}
              stroke="currentColor"
              strokeOpacity={0.15 + tiers.glowOpacity * 0.1}
              strokeWidth="0.75"
              fill="none"
            />
            <path
              d={s5.edgePath}
              stroke={tiers.glowOpacity > 0.5 ? "#E11D48" : "currentColor"}
              strokeOpacity={0.35 + tiers.glowOpacity * 0.3}
              strokeWidth="1.1"
              fill="none"
            />
          </g>

          {/* 3. 3x3 상단 광석 층 */}
          <g className="text-foreground">
            <polygon points={s3.leftFace} fill="currentColor" fillOpacity="0.05" />
            <polygon points={s3.rightFace} fill="currentColor" fillOpacity="0.1" />
            <polygon points={s3.topPolygon} fill="currentColor" fillOpacity="0.04" />
            <path
              d={s3.gridPath}
              stroke="#E11D48"
              strokeOpacity={0.2 + tiers.glowOpacity * 0.25}
              strokeWidth="0.8"
              fill="none"
            />
            <path
              d={s3.edgePath}
              stroke="#E11D48"
              strokeOpacity={0.45 + tiers.glowOpacity * 0.4}
              strokeWidth="1.2"
              fill="none"
            />
          </g>

          {/* 4. 수직 루비 비콘 광선 (비콘 결합 시 상단으로 사출) */}
          {tiers.beamScale > 0.01 && (
            <g>
              <rect
                x={cx - 11}
                y={beaconTopY - 320 * tiers.beamScale}
                width={22}
                height={320 * tiers.beamScale}
                fill="url(#rubyBeamGrad)"
              />
              <line
                x1={cx}
                y1={beaconTopY}
                x2={cx}
                y2={beaconTopY - 340 * tiers.beamScale}
                stroke="url(#rubyCoreLine)"
                strokeWidth="1.5"
              />
            </g>
          )}

          {/* 5. 최상단 1x1 루비 비콘 코어 블록 */}
          <g>
            <polygon points={sBeacon.leftFace} fill="#E11D48" fillOpacity={0.22 + tiers.glowOpacity * 0.25} />
            <polygon points={sBeacon.rightFace} fill="#BE123C" fillOpacity={0.32 + tiers.glowOpacity * 0.28} />
            <polygon points={sBeacon.topPolygon} fill="#F43F5E" fillOpacity={0.28 + tiers.glowOpacity * 0.35} />
            <path
              d={sBeacon.edgePath}
              stroke="#F43F5E"
              strokeOpacity={0.85}
              strokeWidth="1.35"
              fill="none"
            />
          </g>
        </svg>
      </div>

      {/* 하단 미니멀 스크롤 유도 버튼 (오직 Scroll 텍스트와 아래 꺽쇄만 배치) */}
      <button
        type="button"
        onClick={scrollToHero}
        className="mb-10 flex flex-col items-center gap-1.5 text-muted-foreground hover:text-foreground transition-colors group focus:outline-none"
        aria-label="아래로 스크롤"
      >
        <span className="text-[11px] font-mono tracking-[0.24em] uppercase opacity-75 group-hover:opacity-100 transition-opacity">
          Scroll
        </span>
        <ChevronDown className="w-4 h-4 opacity-75 group-hover:translate-y-0.5 transition-transform" />
      </button>
    </section>
  );
}
