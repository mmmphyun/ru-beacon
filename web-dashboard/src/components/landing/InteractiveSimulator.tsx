"use client";

import React, { useState } from "react";
import { Button } from "@/components/ui/button";
import { Badge } from "@/components/ui/badge";
import { ArrowRight, Sparkles, Terminal, MessageSquare, Check, ShieldCheck, Zap } from "lucide-react";

export function InteractiveSimulator() {
  const [activeScenario, setActiveScenario] = useState<"d2m" | "m2d">("d2m");
  const [isSimulating, setIsSimulating] = useState(false);
  const [step, setStep] = useState<number>(0);

  const runSimulation = () => {
    setIsSimulating(true);
    setStep(1);
    setTimeout(() => setStep(2), 500);
    setTimeout(() => setStep(3), 1100);
    setTimeout(() => {
      setIsSimulating(false);
    }, 1800);
  };

  const handleScenarioChange = (scenario: "d2m" | "m2d") => {
    setActiveScenario(scenario);
    setStep(0);
    setIsSimulating(false);
  };

  return (
    <div className="w-full max-w-4xl mx-auto rounded border border-border/60 bg-card/60 backdrop-blur p-5 md:p-6 space-y-6">
      <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-4 border-b border-border/40 pb-4">
        <div>
          <div className="flex items-center gap-2">
            <Sparkles className="w-4 h-4 text-primary" />
            <h3 className="font-semibold text-sm tracking-tight text-foreground">
              실시간 양방향 파이프라인 시뮬레이터
            </h3>
            <span className="text-[10px] font-mono px-1.5 py-0.5 rounded bg-primary/10 text-primary border border-primary/20">
              Live Demo
            </span>
          </div>
          <p className="text-xs text-muted-foreground mt-0.5">
            마인크래프트 서버와 디스코드 간의 0ms Fast-fail 패킷 흐름을 직접 격발해 보세요.
          </p>
        </div>

        {/* 시나리오 스위처 */}
        <div className="inline-flex rounded border border-border/60 bg-background/80 p-0.5 text-xs">
          <button
            onClick={() => handleScenarioChange("d2m")}
            className={`px-3 py-1.5 rounded transition-all font-medium ${
              activeScenario === "d2m"
                ? "bg-card text-foreground shadow-sm font-semibold"
                : "text-muted-foreground hover:text-foreground"
            }`}
          >
            Discord → Minecraft
          </button>
          <button
            onClick={() => handleScenarioChange("m2d")}
            className={`px-3 py-1.5 rounded transition-all font-medium ${
              activeScenario === "m2d"
                ? "bg-card text-foreground shadow-sm font-semibold"
                : "text-muted-foreground hover:text-foreground"
            }`}
          >
            Minecraft → Discord
          </button>
        </div>
      </div>

      {activeScenario === "d2m" ? (
        /* Discord -> Minecraft 시나리오 */
        <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
          {/* Discord 측: 인터랙션 발원지 */}
          <div className="rounded border border-border/40 bg-background/40 p-4 space-y-3">
            <div className="flex items-center justify-between text-xs text-muted-foreground">
              <span className="flex items-center gap-1.5 font-mono">
                <MessageSquare className="w-3.5 h-3.5 text-primary" /> #출석체크 채널
              </span>
              <Badge variant="outline" className="text-[10px] font-mono">
                Trigger
              </Badge>
            </div>

            <div className="p-3 rounded bg-card/80 border border-border/40 text-xs space-y-2">
              <div className="font-semibold text-foreground flex items-center gap-2">
                <span className="w-2 h-2 rounded-full bg-emerald-500"></span>
                루비콘 일일 출석체크 이벤트
              </div>
              <p className="text-muted-foreground text-[11px] leading-relaxed">
                디스코드 계정과 연동된 인게임 캐릭터로 일일 보상(다이아몬드 3개)을 수령합니다.
              </p>
              <Button
                size="sm"
                onClick={runSimulation}
                disabled={isSimulating}
                className="w-full text-xs h-8 bg-primary hover:bg-primary/90 text-primary-foreground font-medium gap-1.5 mt-2"
              >
                <Zap className="w-3.5 h-3.5" />
                {isSimulating ? "동기화 진행 중..." : "[출석 보상 수령하기] 클릭"}
              </Button>
            </div>

            <div className="text-[11px] text-muted-foreground font-mono flex items-center gap-1">
              <span>이벤트 판정 지연:</span>
              <span className="text-emerald-500 font-semibold">0.12ms (서버 렉 없음)</span>
            </div>
          </div>

          {/* Minecraft 콘솔 측: 실행 결과 */}
          <div className="rounded border border-border/40 bg-background/40 p-4 space-y-3">
            <div className="flex items-center justify-between text-xs text-muted-foreground">
              <span className="flex items-center gap-1.5 font-mono">
                <Terminal className="w-3.5 h-3.5 text-primary" /> Paper Console Stream
              </span>
              <Badge variant="outline" className="text-[10px] font-mono">
                Action: Dispatch
              </Badge>
            </div>

            <div className="h-28 rounded bg-black/70 p-3 font-mono text-[11px] text-emerald-400 space-y-1 overflow-y-auto border border-border/20">
              <div className="text-muted-foreground/60">[SYSTEM] Ru-Beacon Agent v1.0.0 Ready (WSS Connected)</div>
              {step >= 1 && (
                <div className="text-blue-400 animate-pulse">
                  [RECV] DISCORD_BUTTON_CLICK (User: Alex_KR, customId: btn_daily_reward)
                </div>
              )}
              {step >= 2 && (
                <div className="text-amber-400">
                  [WORKER] Condition evaluated: True (Quota remaining: 1/1)
                </div>
              )}
              {step >= 3 && (
                <div className="text-emerald-300 font-semibold">
                  [EXEC] give Alex_KR minecraft:diamond 3 & title Alex_KR subtitle &quot;출석 완료!&quot;
                </div>
              )}
            </div>

            <div className="flex items-center justify-between text-[11px] text-muted-foreground">
              <span>인게임 TPS 영향:</span>
              <span className="text-emerald-500 font-semibold font-mono">0.00% (메인 스레드 멈춤 없음)</span>
            </div>
          </div>
        </div>
      ) : (
        /* Minecraft -> Discord 시나리오 */
        <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
          {/* Minecraft 측: 이벤트 발원지 */}
          <div className="rounded border border-border/40 bg-background/40 p-4 space-y-3">
            <div className="flex items-center justify-between text-xs text-muted-foreground">
              <span className="flex items-center gap-1.5 font-mono">
                <Terminal className="w-3.5 h-3.5 text-primary" /> Minecraft In-game Event
              </span>
              <Badge variant="outline" className="text-[10px] font-mono">
                Trigger
              </Badge>
            </div>

            <div className="p-3 rounded bg-card/80 border border-border/40 text-xs space-y-2">
              <div className="font-semibold text-foreground flex items-center gap-2">
                <span className="w-2 h-2 rounded-full bg-amber-500"></span>
                플레이어 50레벨 달성 이벤트
              </div>
              <p className="text-muted-foreground text-[11px] leading-relaxed">
                인게임 플레이어가 몬스터 사냥 및 경험치 획득으로 50레벨에 도달했습니다.
              </p>
              <Button
                size="sm"
                onClick={runSimulation}
                disabled={isSimulating}
                className="w-full text-xs h-8 bg-primary hover:bg-primary/90 text-primary-foreground font-medium gap-1.5 mt-2"
              >
                <Zap className="w-3.5 h-3.5" />
                {isSimulating ? "이벤트 전파 중..." : "[플레이어 50레벨 달성] 시뮬레이션"}
              </Button>
            </div>

            <div className="text-[11px] text-muted-foreground font-mono flex items-center gap-1">
              <span>서버 이벤트 감지:</span>
              <span className="text-emerald-500 font-semibold">0.03ms (메모리 누수 없음)</span>
            </div>
          </div>

          {/* Discord 임베드 측: 실행 결과 */}
          <div className="rounded border border-border/40 bg-background/40 p-4 space-y-3">
            <div className="flex items-center justify-between text-xs text-muted-foreground">
              <span className="flex items-center gap-1.5 font-mono">
                <MessageSquare className="w-3.5 h-3.5 text-primary" /> #서버-공지 채널
              </span>
              <Badge variant="outline" className="text-[10px] font-mono">
                Action: Embed & Role
              </Badge>
            </div>

            <div className="h-28 rounded bg-black/50 p-3 text-xs space-y-2 overflow-y-auto border border-border/20">
              {step === 0 && (
                <div className="text-muted-foreground/60 text-[11px] text-center pt-8">
                  좌측 버튼을 눌러 레벨업 이벤트를 발생시키세요.
                </div>
              )}
              {step >= 1 && (
                <div className="text-[11px] font-mono text-blue-400 animate-pulse">
                  [INGRESS] MINECRAFT_LEVEL_UP (Player: &quot;Steve_KR&quot;, Level: 50)
                </div>
              )}
              {step >= 2 && (
                <div className="p-2 rounded bg-card/90 border-l-2 border-primary text-[11px] space-y-1">
                  <div className="font-semibold text-primary flex items-center gap-1">
                    <Sparkles className="w-3 h-3" /> 레벨 50 도달 달성 공지!
                  </div>
                  <div className="text-foreground/90">
                    축하합니다! Steve_KR 님이 서버 최단 시간 50레벨을 돌파했습니다!
                  </div>
                </div>
              )}
              {step >= 3 && (
                <div className="text-emerald-400 font-mono text-[11px] flex items-center gap-1">
                  <ShieldCheck className="w-3.5 h-3.5" /> [BOT] Steve_KR에게 @베테랑 역할 부여 완료
                </div>
              )}
            </div>

            <div className="flex items-center justify-between text-[11px] text-muted-foreground">
              <span>디스코드 알림 및 역할:</span>
              <span className="text-emerald-500 font-semibold font-mono">실시간 지급 완료</span>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
