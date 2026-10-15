"use client";

import React, { useState } from "react";
import { Button } from "@/components/ui/button";
import { Badge } from "@/components/ui/badge";
import { Radio, Activity, Check, MessageSquare, Zap, Clock } from "lucide-react";

interface EventItem {
  id: string;
  name: string;
  tag: string;
  player: string;
  detail: string;
}

const EVENTS: EventItem[] = [
  { id: "diamond", name: "다이아몬드 채굴", tag: "BLOCK_BREAK", player: "Steve", detail: "y:-58 심층암 다이아몬드 원석 채굴" },
  { id: "level", name: "플레이어 레벨업", tag: "LEVEL_UP", player: "Alex", detail: "레벨 30 달성" },
  { id: "boss", name: "보스 처치", tag: "BOSS_KILL", player: "Steve", detail: "엔더 드래곤 토벌 성공" },
];

export function InteractiveSimulator() {
  const [selectedEvent, setSelectedEvent] = useState<EventItem>(EVENTS[0]);
  const [stage, setStage] = useState<"idle" | "beacon" | "buffer" | "delivered">("idle");
  const [toastMessage, setToastMessage] = useState<string | null>(null);

  const isSimulating = stage !== "idle";

  const handleTrigger = () => {
    if (isSimulating) return;
    setToastMessage(null);
    setStage("beacon");

    setTimeout(() => {
      setStage("buffer");
    }, 150);

    setTimeout(() => {
      setStage("delivered");
      setToastMessage("메인 틱 점유 0.00ms, 평균 45ms 만에 디스코드 임베드가 발송되었습니다.");
    }, 350);

    setTimeout(() => {
      setStage("idle");
    }, 2400);
  };

  return (
    <div className="w-full max-w-5xl mx-auto rounded-lg border border-border bg-card p-5 md:p-6 space-y-6">
      {/* 쇼케이스 상단 헤더 */}
      <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-3 border-b border-border pb-4">
        <div>
          <div className="flex items-center gap-2">
            <Radio className="w-4 h-4 text-primary" />
            <h3 className="font-semibold text-sm tracking-tight text-foreground">
              실시간 제품 텔레메트리 파이프라인
            </h3>
            <Badge variant="outline" className="text-[10px] font-mono border-primary/40 text-primary">
              Live Mockup
            </Badge>
          </div>
          <p className="text-xs text-muted-foreground mt-1">
            마인크래프트 비콘 신호 감지부터 LMAX 링버퍼를 거쳐 디스코드 임베드로 안착하는 45ms 흐름입니다.
          </p>
        </div>

        {/* 이벤트 선택 버튼군 */}
        <div className="inline-flex rounded border border-border bg-background p-1 text-xs gap-1">
          {EVENTS.map((evt) => (
            <button
              key={evt.id}
              onClick={() => setSelectedEvent(evt)}
              disabled={isSimulating}
              className={`px-2.5 py-1 rounded transition-colors text-[11px] font-mono ${
                selectedEvent.id === evt.id
                  ? "bg-primary text-primary-foreground font-semibold"
                  : "text-muted-foreground hover:text-foreground"
              }`}
            >
              {evt.name}
            </button>
          ))}
        </div>
      </div>

      {/* 3대 텔레메트리 쇼케이스 그리드 (루비-샌드 1px 헤어라인 카드) */}
      <div className="grid grid-cols-1 md:grid-cols-3 gap-4">
        {/* 좌측: 루비 신호기 (비콘) 노드 */}
        <div
          className={`rounded border p-4 space-y-3 transition-colors duration-200 ${
            stage === "beacon"
              ? "border-primary bg-primary/5"
              : "border-border bg-surface"
          }`}
        >
          <div className="flex items-center justify-between text-xs">
            <span className="font-semibold text-foreground flex items-center gap-1.5">
              <span className={`w-2 h-2 rounded-full ${stage === "beacon" ? "bg-primary animate-ping" : "bg-primary"}`} />
              루비 신호기 (비콘)
            </span>
            <span className="text-[10px] font-mono text-muted-foreground">IN-GAME</span>
          </div>

          <div className="rounded border border-border bg-card p-3 space-y-2 text-xs">
            <div className="flex items-center justify-between font-mono text-[11px]">
              <span className="text-muted-foreground">상태</span>
              <span className="text-foreground font-medium">
                {stage === "beacon" ? "이벤트 격발 중" : "이벤트 대기 중"}
              </span>
            </div>
            <div className="flex items-center justify-between font-mono text-[11px]">
              <span className="text-muted-foreground">태그</span>
              <span className="text-primary font-bold">{selectedEvent.tag}</span>
            </div>
            <div className="flex items-center justify-between font-mono text-[11px]">
              <span className="text-muted-foreground">대상자</span>
              <span className="text-foreground">{selectedEvent.player}</span>
            </div>
          </div>

          <p className="text-[11px] text-muted-foreground leading-normal">
            마인크래프트 비동기 이벤트 핸들러가 메인 스레드 간섭 없이 원석 채굴 이벤트를 포착합니다.
          </p>
        </div>

        {/* 중앙: LMAX Disruptor 링버퍼 코어 */}
        <div
          className={`rounded border p-4 space-y-3 transition-colors duration-200 ${
            stage === "buffer"
              ? "border-primary bg-primary/5"
              : "border-border bg-surface"
          }`}
        >
          <div className="flex items-center justify-between text-xs">
            <span className="font-semibold text-foreground flex items-center gap-1.5">
              <Activity className="w-3.5 h-3.5 text-primary" />
              LMAX Disruptor 링버퍼
            </span>
            <span className="text-[10px] font-mono text-emerald-400">20.0 TPS 안정</span>
          </div>

          <div className="rounded border border-border bg-card p-3 space-y-2 text-xs">
            <div className="flex items-center justify-between font-mono text-[11px]">
              <span className="text-muted-foreground">버퍼링 지연</span>
              <span className="text-foreground font-semibold">0.08ms</span>
            </div>
            <div className="flex items-center justify-between font-mono text-[11px]">
              <span className="text-muted-foreground">메인 틱 점유</span>
              <span className="text-emerald-400 font-semibold">0.00ms (완전 비동기)</span>
            </div>
            <div className="flex items-center justify-between font-mono text-[11px]">
              <span className="text-muted-foreground">시퀀스 큐</span>
              <span className="text-muted-foreground">4096 / Lock-free</span>
            </div>
          </div>

          <p className="text-[11px] text-muted-foreground leading-normal">
            메모리 할당 없는 원형 링버퍼 구조로 초당 1,000건 폭증 상황에서도 서버 틱 스파이크를 차단합니다.
          </p>
        </div>

        {/* 우측: 디스코드 게이트웨이 노드 */}
        <div
          className={`rounded border p-4 space-y-3 transition-colors duration-200 ${
            stage === "delivered"
              ? "border-primary bg-primary/5"
              : "border-border bg-surface"
          }`}
        >
          <div className="flex items-center justify-between text-xs">
            <span className="font-semibold text-foreground flex items-center gap-1.5">
              <MessageSquare className="w-3.5 h-3.5 text-primary" />
              디스코드 게이트웨이
            </span>
            <span className="text-[10px] font-mono text-primary">평균 45ms 도착</span>
          </div>

          {/* 디스코드 다크 스타일 규격 임베드 카드 */}
          <div className="rounded border border-border bg-secondary p-3 space-y-2 text-xs border-l-4 border-l-primary">
            <div className="flex items-center justify-between text-[11px]">
              <span className="font-semibold text-foreground flex items-center gap-1">
                Ru-Beacon
                <span className="text-[9px] bg-primary text-primary-foreground px-1 py-0.2 rounded font-mono">
                  BOT
                </span>
              </span>
              <span className="text-[10px] text-muted-foreground font-mono">#서버-알림</span>
            </div>
            <div className="font-medium text-foreground text-[12px]">
              [{selectedEvent.name}] {selectedEvent.player}
            </div>
            <p className="text-[11px] text-muted-foreground leading-relaxed">
              {selectedEvent.detail}
            </p>
          </div>

          <p className="text-[11px] text-muted-foreground leading-normal">
            중계 레이어 없이 디스코드 WebSocket API로 웹훅 임베드를 즉시 푸시합니다.
          </p>
        </div>
      </div>

      {/* 액션 컨트롤 & 실시간 피드백 토스트 */}
      <div className="pt-2 flex flex-col sm:flex-row items-center justify-between gap-3 border-t border-border">
        <Button
          onClick={handleTrigger}
          disabled={isSimulating}
          className="w-full sm:w-auto h-9 text-xs bg-primary hover:bg-primary/90 text-primary-foreground font-semibold px-4 gap-2"
        >
          <Zap className="w-3.5 h-3.5" />
          {isSimulating ? "파이프라인 통과 중..." : "루비 신호기 이벤트 격발 테스트"}
        </Button>

        {toastMessage ? (
          <div
            role="status"
            className="flex items-center gap-2 text-xs font-mono text-emerald-400 bg-emerald-500/10 border border-emerald-500/20 px-3 py-1.5 rounded transition-opacity"
          >
            <Check className="w-3.5 h-3.5 text-emerald-400 shrink-0" />
            <span>{toastMessage}</span>
          </div>
        ) : (
          <div className="flex items-center gap-2 text-xs font-mono text-muted-foreground">
            <Clock className="w-3.5 h-3.5" />
            <span>엔드투엔드 파이프라인 평균 지연: 45ms</span>
          </div>
        )}
      </div>
    </div>
  );
}
