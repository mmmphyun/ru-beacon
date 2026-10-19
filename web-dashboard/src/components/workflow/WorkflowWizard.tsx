"use client";

import React, { useState, useMemo } from "react";
import { WizardFormState } from "@/types/workflow";
import {
  generateWorkflowAst,
  validateWorkflowState,
  WORKFLOW_PRESETS,
  ALLOWED_VARIABLES,
} from "@/lib/workflow-generator";
import { createWorkflow, deployWorkflow, rollbackWorkflow } from "@/lib/api";
import { Card, CardHeader, CardTitle, CardDescription, CardContent, CardFooter } from "@/components/ui/card";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Badge } from "@/components/ui/badge";
import {
  CheckCircle2,
  AlertCircle,
  ArrowRight,
  ArrowLeft,
  Send,
  RotateCcw,
  Sparkles,
  Bot,
  Loader2,
  HelpCircle,
  AlertTriangle,
  ChevronDown,
  Terminal,
  ArrowDown,
  Zap,
  Filter,
  Check,
} from "lucide-react";

interface WorkflowWizardProps {
  tenantId: string;
  onWorkflowSaved?: (workflowId: string) => void;
}

export function WorkflowWizard({ tenantId, onWorkflowSaved }: WorkflowWizardProps) {
  const [step, setStep] = useState<1 | 2 | 3>(1);
  const [formState, setFormState] = useState<WizardFormState>(WORKFLOW_PRESETS[0].state);
  const [createdWorkflowId, setCreatedWorkflowId] = useState<string | null>(null);
  const [activeVersion, setActiveVersion] = useState<number | null>(null);
  const [loading, setLoading] = useState(false);
  const [statusMessage, setStatusMessage] = useState<{ type: "success" | "error"; text: string } | null>(null);

  // 자연어 에이전트 커맨드 바 상태
  const [agentPrompt, setAgentPrompt] = useState("");
  const [agentLoading, setAgentLoading] = useState(false);
  const [agentResult, setAgentResult] = useState<{
    type: "success" | "ambiguous" | "error" | "rate_limited";
    message: string;
    confidence?: number;
    candidateState?: WizardFormState;
  } | null>(null);

  // 실시간 AST 생성 및 유효성 검증
  const ast = useMemo(() => generateWorkflowAst(formState, activeVersion ?? 1), [formState, activeVersion]);
  const validation = useMemo(() => validateWorkflowState(formState), [formState]);

  const handleApplyPreset = (index: number) => {
    setFormState(WORKFLOW_PRESETS[index].state);
    setStatusMessage({ type: "success", text: `프리셋 '${WORKFLOW_PRESETS[index].label}'을 불러왔습니다.` });
  };

  const handleAgentGenerate = async () => {
    const trimmed = agentPrompt.trim();
    if (!trimmed) return;

    setAgentLoading(true);
    setAgentResult(null);

    try {
      const res = await fetch("/api/agent/generate", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ prompt: trimmed, tenantId }),
      });

      if (res.status === 429) {
        const data = await res.json().catch(() => ({}));
        setAgentResult({
          type: "rate_limited",
          message: data.message || "요청 한도를 초과했습니다 (분당 2회, 일일 5회 제한). 잠시 후 다시 시도해주세요.",
        });
        return;
      }

      if (res.status === 400) {
        const data = await res.json().catch(() => ({}));
        setAgentResult({
          type: "error",
          message: data.message || "지원하지 않는 워크플로우 요구사항입니다. 마인크래프트 레벨업, 발전과제, 또는 Discord 버튼 이벤트만 지원됩니다.",
          confidence: 0.0,
        });
        return;
      }

      if (!res.ok) {
        setAgentResult({
          type: "error",
          message: "에이전트 생성 처리 중 일시적인 오류가 발생했습니다.",
        });
        return;
      }

      const data = await res.json();
      const confidence = typeof data.confidence === "number" ? data.confidence : 0;

      if (confidence >= 0.7) {
        setFormState(data.formState);
        setAgentResult({
          type: "success",
          message: `${data.explanation} (확신도: ${Math.round(confidence * 100)}%)`,
          confidence,
        });
        setTimeout(() => {
          setStep(2);
        }, 500);
      } else if (confidence > 0.0) {
        setAgentResult({
          type: "ambiguous",
          message: `의도가 모호합니다: 혹시 '${data.explanation}'을(를) 원하셨습니까? (확신도: ${Math.round(confidence * 100)}%)`,
          confidence,
          candidateState: data.formState,
        });
      } else {
        setAgentResult({
          type: "error",
          message: data.message || "지원하지 않는 워크플로우 요구사항입니다. 마인크래프트 레벨업, 발전과제, 또는 Discord 버튼 이벤트만 지원됩니다.",
          confidence: 0.0,
        });
      }
    } catch {
      setAgentResult({
        type: "error",
        message: "에이전트 서비스 통신 중 오류가 발생했습니다.",
      });
    } finally {
      setAgentLoading(false);
    }
  };

  const handleApplyCandidate = () => {
    if (agentResult?.candidateState) {
      setFormState(agentResult.candidateState);
      setAgentResult({
        type: "success",
        message: "추천 설정을 적용했습니다.",
        confidence: agentResult.confidence,
      });
      setTimeout(() => {
        setStep(2);
      }, 500);
    }
  };

  const handleSaveDraft = async () => {
    if (!validation.valid) {
      setStatusMessage({ type: "error", text: "입력값 검증에 실패했습니다. 오류 목록을 확인하세요." });
      return;
    }
    setLoading(true);
    setStatusMessage(null);
    try {
      const res = await createWorkflow({
        tenantId,
        name: formState.workflowName,
        description: formState.description,
        definition: JSON.stringify(ast, null, 2),
      });
      setCreatedWorkflowId(res.workflowId);
      setStatusMessage({ type: "success", text: `워크플로우 초안이 저장되었습니다 (ID: ${res.workflowId}, Version: ${res.version})` });
      onWorkflowSaved?.(res.workflowId);
    } catch {
      setStatusMessage({ type: "error", text: "워크플로우 저장 중 오류가 발생했습니다." });
    } finally {
      setLoading(false);
    }
  };

  const handleDeploy = async () => {
    if (!validation.valid) {
      setStatusMessage({ type: "error", text: "배포 전 유효성 검증 오류를 해결해야 합니다." });
      return;
    }
    setLoading(true);
    setStatusMessage(null);
    try {
      let wfId = createdWorkflowId;
      if (!wfId) {
        const saved = await createWorkflow({
          tenantId,
          name: formState.workflowName,
          description: formState.description,
          definition: JSON.stringify(ast, null, 2),
        });
        wfId = saved.workflowId;
        setCreatedWorkflowId(wfId);
      }

      const res = await deployWorkflow(wfId);
      setActiveVersion(res.activeVersion);
      setStatusMessage({ type: "success", text: `버전 ${res.activeVersion}이 성공적으로 배포(ACTIVE)되었습니다!` });
      onWorkflowSaved?.(wfId);
    } catch {
      setStatusMessage({ type: "error", text: "워크플로우 배포 중 오류가 발생했습니다." });
    } finally {
      setLoading(false);
    }
  };

  const handleRollback = async () => {
    if (!createdWorkflowId) {
      setStatusMessage({ type: "error", text: "저장된 워크플로우가 없습니다." });
      return;
    }
    setLoading(true);
    try {
      const res = await rollbackWorkflow(createdWorkflowId, 1);
      setActiveVersion(res.activeVersion);
      setStatusMessage({ type: "success", text: `버전 ${res.activeVersion}로 롤백 완료되었습니다.` });
    } catch {
      setStatusMessage({ type: "error", text: "롤백 중 오류가 발생했습니다." });
    } finally {
      setLoading(false);
    }
  };

  // 헬퍼: 현재 트리거 및 액션의 직관적 라벨
  const triggerLabel = useMemo(() => {
    switch (formState.triggerType) {
      case "MINECRAFT_LEVEL_UP":
        return "마인크래프트 플레이어 레벨업";
      case "MINECRAFT_ADVANCEMENT_DONE":
        return "마인크래프트 발전과제 달성";
      case "DISCORD_BUTTON_CLICK":
        return `Discord 버튼 클릭 (${formState.triggerParams?.customId || "지정 안 됨"})`;
      case "DISCORD_SLASH_COMMAND":
        return "Discord 슬래시 명령";
      default:
        return formState.triggerType;
    }
  }, [formState.triggerType, formState.triggerParams?.customId]);

  const actionLabel = useMemo(() => {
    switch (formState.actionType) {
      case "DISCORD_SEND_MESSAGE":
        return "Discord 알림 메시지 발송";
      case "MINECRAFT_DISPATCH_COMMAND":
        return "마인크래프트 서버 콘솔 명령어 실행";
      case "COMMAND_AND_DISCORD":
        return "마인크래프트 명령 실행 + Discord 알림";
      case "ATTENDANCE_REWARD_PIPELINE":
        return `선착순 출석 보상 파이프라인 (정원: ${formState.actionParams?.totalLimit ?? 100}명)`;
      default:
        return formState.actionType;
    }
  }, [formState.actionType, formState.actionParams?.totalLimit]);

  return (
    <div className="max-w-4xl mx-auto space-y-6">
      {/* 1. Ru-Beacon 에이전트 커맨드 바 (Raycast / Linear 스타일 단일 입력 캔버스) */}
      <div className="rounded-xl border border-border bg-card p-5 shadow-sm space-y-4">
        <div className="flex flex-wrap items-center justify-between gap-2">
          <div className="flex items-center gap-2.5">
            <div className="p-1.5 rounded-md bg-primary/10 text-primary border border-primary/20">
              <Bot className="w-4 h-4" />
            </div>
            <div>
              <div className="flex items-center gap-2">
                <span className="font-semibold text-foreground text-sm tracking-tight">Ru-Beacon 에이전트</span>
                <Badge variant="outline" className="text-[11px] py-0 px-1.5 border-primary/30 text-primary bg-primary/5">
                  자연어 자동 생성
                </Badge>
              </div>
            </div>
          </div>
          <span className="text-xs text-muted-foreground font-mono">
            Enter ↵ 키로 즉시 추론
          </span>
        </div>

        <div className="flex gap-2.5">
          <Input
            value={agentPrompt}
            onChange={(e) => setAgentPrompt(e.target.value)}
            onKeyDown={(e) => {
              if (e.key === "Enter" && !agentLoading && agentPrompt.trim()) {
                handleAgentGenerate();
              }
            }}
            placeholder="예: 플레이어가 50레벨을 달성하면 축하 임베드 메시지를 Discord에 전송해줘"
            disabled={agentLoading}
            className="text-sm bg-background border-border text-foreground placeholder:text-muted-foreground h-10 shadow-none focus-visible:ring-1 focus-visible:ring-primary"
          />
          <Button
            onClick={handleAgentGenerate}
            disabled={agentLoading || !agentPrompt.trim()}
            className="shrink-0 gap-1.5 bg-primary hover:bg-primary/90 text-primary-foreground transition-colors h-10 px-4 font-medium"
          >
            {agentLoading ? (
              <>
                <Loader2 className="w-4 h-4 animate-spin" />
                <span>추론 중...</span>
              </>
            ) : (
              <>
                <Sparkles className="w-4 h-4" />
                <span>생성</span>
              </>
            )}
          </Button>
        </div>

        {/* 빠른 템플릿 프리셋 칩 목록 */}
        <div className="flex flex-wrap items-center gap-1.5 pt-1">
          <span className="text-xs text-muted-foreground mr-1 flex items-center gap-1 font-mono">
            <Terminal className="w-3 h-3 text-muted-foreground" /> 추천 프리셋:
          </span>
          {WORKFLOW_PRESETS.map((p, idx) => (
            <button
              key={idx}
              type="button"
              onClick={() => handleApplyPreset(idx)}
              className="text-xs px-2.5 py-1 rounded-md bg-secondary text-secondary-foreground hover:bg-secondary/80 border border-border/50 transition-colors"
            >
              {p.label}
            </button>
          ))}
        </div>

        {/* 에이전트 피드백 배너 (확신도 3단계) */}
        {agentResult && (
          <div
            className={`p-3.5 rounded-lg border text-sm flex items-start justify-between gap-3 ${
              agentResult.type === "success"
                ? "bg-emerald-950/20 border-emerald-800/40 text-emerald-400 dark:text-emerald-300"
                : agentResult.type === "ambiguous"
                ? "bg-amber-950/20 border-amber-800/40 text-amber-400 dark:text-amber-300"
                : agentResult.type === "rate_limited"
                ? "bg-orange-950/20 border-orange-800/40 text-orange-400 dark:text-orange-300"
                : "bg-rose-950/20 border-rose-800/40 text-rose-400 dark:text-rose-300"
            }`}
          >
            <div className="flex items-start gap-2.5">
              {agentResult.type === "success" && <CheckCircle2 className="w-4 h-4 mt-0.5 shrink-0" />}
              {agentResult.type === "ambiguous" && <HelpCircle className="w-4 h-4 mt-0.5 shrink-0" />}
              {agentResult.type === "rate_limited" && <AlertTriangle className="w-4 h-4 mt-0.5 shrink-0" />}
              {agentResult.type === "error" && <AlertCircle className="w-4 h-4 mt-0.5 shrink-0" />}
              <span className="text-xs md:text-sm font-medium">{agentResult.message}</span>
            </div>
            {agentResult.type === "ambiguous" && agentResult.candidateState && (
              <Button
                size="sm"
                variant="outline"
                onClick={handleApplyCandidate}
                className="shrink-0 text-xs h-7 border-amber-700/60 hover:bg-amber-950/40 text-amber-300"
              >
                추천 설정 적용하기
              </Button>
            )}
          </div>
        )}
      </div>

      {/* 상태 알림 메시지 */}
      {statusMessage && (
        <div
          className={`p-3.5 rounded-lg border text-sm flex items-center gap-2.5 ${
            statusMessage.type === "success"
              ? "bg-emerald-950/20 border-emerald-800/40 text-emerald-400 dark:text-emerald-300"
              : "bg-rose-950/20 border-rose-800/40 text-rose-400 dark:text-rose-300"
          }`}
        >
          {statusMessage.type === "success" ? <CheckCircle2 className="w-4 h-4 shrink-0" /> : <AlertCircle className="w-4 h-4 shrink-0" />}
          <span className="text-xs md:text-sm">{statusMessage.text}</span>
        </div>
      )}

      {/* 워크플로우 기본 메타 정보 바 */}
      <div className="rounded-lg border border-border bg-card p-4 grid grid-cols-1 md:grid-cols-2 gap-4">
        <div>
          <label className="text-xs font-semibold text-muted-foreground">워크플로우 명칭</label>
          <Input
            value={formState.workflowName}
            onChange={(e) => setFormState({ ...formState, workflowName: e.target.value })}
            placeholder="예: 레벨업 축하 공지"
            className="mt-1.5 h-9 bg-background border-border text-foreground text-sm"
          />
        </div>
        <div>
          <label className="text-xs font-semibold text-muted-foreground">설명</label>
          <Input
            value={formState.description}
            onChange={(e) => setFormState({ ...formState, description: e.target.value })}
            placeholder="워크플로우의 용도를 간략히 기술하세요"
            className="mt-1.5 h-9 bg-background border-border text-foreground text-sm"
          />
        </div>
      </div>

      {/* 2. 세로 파이프라인 노드 네비게이터 (Linear 세로 노드형) */}
      <div className="flex items-center justify-between border-b border-border pb-3 px-1">
        <div className="flex items-center gap-3 text-sm">
          <button
            type="button"
            onClick={() => setStep(1)}
            className={`flex items-center gap-2 px-3 py-1.5 rounded-md text-xs font-medium transition-colors ${
              step === 1
                ? "bg-primary text-primary-foreground shadow-sm"
                : "text-muted-foreground hover:text-foreground bg-secondary/50"
            }`}
          >
            <span className="font-bold">1.</span> 트리거 선택
          </button>
          <ArrowRight className="w-3.5 h-3.5 text-muted-foreground" />
          <button
            type="button"
            onClick={() => setStep(2)}
            className={`flex items-center gap-2 px-3 py-1.5 rounded-md text-xs font-medium transition-colors ${
              step === 2
                ? "bg-primary text-primary-foreground shadow-sm"
                : "text-muted-foreground hover:text-foreground bg-secondary/50"
            }`}
          >
            <span className="font-bold">2.</span> 조건 필터링
          </button>
          <ArrowRight className="w-3.5 h-3.5 text-muted-foreground" />
          <button
            type="button"
            onClick={() => setStep(3)}
            className={`flex items-center gap-2 px-3 py-1.5 rounded-md text-xs font-medium transition-colors ${
              step === 3
                ? "bg-primary text-primary-foreground shadow-sm"
                : "text-muted-foreground hover:text-foreground bg-secondary/50"
            }`}
          >
            <span className="font-bold">3.</span> 실행 액션
          </button>
        </div>

        <Badge variant={validation.valid ? "success" : "destructive"} className="text-xs">
          {validation.valid ? "검증 통과" : "입력 필요"}
        </Badge>
      </div>

      {/* 3. 메인 인터랙티브 파이프라인 카드 */}
      <Card className="border border-border bg-card shadow-sm">
        <CardHeader className="pb-4 border-b border-border">
          <div className="flex items-center justify-between">
            <div>
              <CardTitle className="text-base text-foreground font-semibold flex items-center gap-2">
                {step === 1 && (
                  <>
                    <Zap className="w-4 h-4 text-primary" />
                    <span>1단계: 시작 트리거 이벤트 선택</span>
                  </>
                )}
                {step === 2 && (
                  <>
                    <Filter className="w-4 h-4 text-primary" />
                    <span>2단계: 조건부 필터 설정 (선택)</span>
                  </>
                )}
                {step === 3 && (
                  <>
                    <Send className="w-4 h-4 text-primary" />
                    <span>3단계: 실행할 액션 지정</span>
                  </>
                )}
              </CardTitle>
              <CardDescription className="text-xs text-muted-foreground mt-1">
                {step === 1 && "마인크래프트 서버 또는 Discord에서 발생할 이벤트를 선택합니다."}
                {step === 2 && "특정 레벨 이상 또는 특정 조건일 때만 액션이 실행되도록 필터링합니다."}
                {step === 3 && "조건을 충족했을 때 전송할 Discord 메시지나 서버 명령어를 구성합니다."}
              </CardDescription>
            </div>
          </div>
        </CardHeader>

        <CardContent className="pt-5 space-y-5">
          {/* Step 1: Trigger */}
          {step === 1 && (
            <div className="space-y-4">
              <label className="text-xs font-semibold text-muted-foreground">이벤트 트리거 유형</label>
              <div className="grid grid-cols-1 md:grid-cols-2 gap-3">
                {[
                  { type: "MINECRAFT_LEVEL_UP", title: "플레이어 레벨업", desc: "플레이어의 레벨이 상승했을 때" },
                  { type: "MINECRAFT_ADVANCEMENT_DONE", title: "발전과제 달성", desc: "마인크래프트 발전과제를 완수했을 때" },
                  { type: "DISCORD_BUTTON_CLICK", title: "Discord 버튼 클릭", desc: "임베드 메시지의 버튼을 눌렀을 때" },
                  { type: "DISCORD_SLASH_COMMAND", title: "Discord 슬래시 명령", desc: "/명령어 실행 인터랙션" },
                ].map((item) => {
                  const isSelected = formState.triggerType === item.type;
                  return (
                    <div
                      key={item.type}
                      onClick={() =>
                        setFormState({
                          ...formState,
                          triggerType: item.type as WizardFormState["triggerType"],
                        })
                      }
                      className={`cursor-pointer p-4 rounded-lg border transition-colors ${
                        isSelected
                          ? "border-primary bg-primary/5 text-foreground ring-1 ring-primary/40"
                          : "border-border hover:border-border/80 bg-background/50 text-muted-foreground"
                      }`}
                    >
                      <div className="flex items-center justify-between">
                        <span className="font-semibold text-sm text-foreground">{item.title}</span>
                        {isSelected && <Check className="w-4 h-4 text-primary" />}
                      </div>
                      <div className="text-xs text-muted-foreground mt-1">{item.desc}</div>
                    </div>
                  );
                })}
              </div>

              {formState.triggerType === "DISCORD_BUTTON_CLICK" && (
                <div className="pt-2 border-t border-border mt-3">
                  <label className="text-xs font-semibold text-muted-foreground">Button Custom ID</label>
                  <Input
                    value={formState.triggerParams.customId || ""}
                    onChange={(e) =>
                      setFormState({
                        ...formState,
                        triggerParams: { ...formState.triggerParams, customId: e.target.value },
                      })
                    }
                    placeholder="btn_daily_attendance"
                    className="mt-1.5 h-9 bg-background border-border text-foreground text-sm"
                  />
                </div>
              )}
            </div>
          )}

          {/* Step 2: Condition */}
          {step === 2 && (
            <div className="space-y-4">
              <div className="flex items-center gap-2.5 p-3 rounded-lg border border-border bg-background/50">
                <input
                  type="checkbox"
                  id="enableCondition"
                  checked={formState.enableCondition}
                  onChange={(e) => setFormState({ ...formState, enableCondition: e.target.checked })}
                  className="rounded border-border text-primary focus:ring-primary h-4 w-4 bg-background"
                />
                <label htmlFor="enableCondition" className="text-sm font-medium text-foreground cursor-pointer">
                  특정 조건일 때만 실행하기
                </label>
              </div>

              {formState.enableCondition ? (
                <div className="p-4 rounded-lg border border-border bg-card space-y-3.5">
                  <div>
                    <label className="text-xs font-semibold text-muted-foreground">검사할 항목</label>
                    <Input
                      value={formState.conditionField}
                      onChange={(e) => setFormState({ ...formState, conditionField: e.target.value })}
                      placeholder="new_level 또는 role_id"
                      className="mt-1.5 h-9 bg-background border-border text-foreground text-sm"
                    />
                  </div>

                  <div className="grid grid-cols-1 md:grid-cols-2 gap-3.5">
                    <div>
                      <label className="text-xs font-semibold text-muted-foreground">비교 방식</label>
                      <select
                        value={formState.conditionOperator}
                        onChange={(e) =>
                          setFormState({
                            ...formState,
                            conditionOperator: e.target.value as WizardFormState["conditionOperator"],
                          })
                        }
                        className="mt-1.5 w-full rounded-md border border-border bg-background px-3 h-9 text-sm text-foreground focus:outline-none focus:ring-1 focus:ring-primary"
                      >
                        <option value="GREATER_OR_EQUAL">&gt;= (이상 / 크거나 같음)</option>
                        <option value="EQUALS">== (완전 일치)</option>
                        <option value="CONTAINS">포함</option>
                      </select>
                    </div>
                    <div>
                      <label className="text-xs font-semibold text-muted-foreground">기준값</label>
                      <Input
                        value={formState.conditionValue}
                        onChange={(e) => setFormState({ ...formState, conditionValue: e.target.value })}
                        placeholder="예: 30"
                        className="mt-1.5 h-9 bg-background border-border text-foreground text-sm"
                      />
                    </div>
                  </div>
                </div>
              ) : (
                <div className="p-6 rounded-lg border border-dashed border-border text-center text-muted-foreground text-sm">
                  조건 없이 이벤트가 일어날 때마다 항상 실행합니다.
                </div>
              )}
            </div>
          )}

          {/* Step 3: Action */}
          {step === 3 && (
            <div className="space-y-4">
              <label className="text-xs font-semibold text-muted-foreground">실행할 액션 유형</label>
              <div className="grid grid-cols-1 md:grid-cols-2 gap-3">
                {[
                  { type: "DISCORD_SEND_MESSAGE", title: "Discord 메시지 전송", desc: "채널에 알림 메시지를 발송합니다." },
                  { type: "MINECRAFT_DISPATCH_COMMAND", title: "Minecraft 명령 실행", desc: "서버 콘솔 명령어를 실행합니다." },
                  { type: "COMMAND_AND_DISCORD", title: "명령 실행 + Discord 알림", desc: "서버 명령 실행 후 디스코드 채널로 알림을 동시 전송합니다." },
                  { type: "ATTENDANCE_REWARD_PIPELINE", title: "선착순 출석 보상 파이프라인", desc: "중복 체크, 선착순 정원 검증 후 인게임 보상과 디스코드 알림을 일괄 처리합니다." },
                ].map((item) => {
                  const isSelected = formState.actionType === item.type;
                  return (
                    <div key={item.type} onClick={() => setFormState({ ...formState, actionType: item.type as WizardFormState["actionType"] })} className={`cursor-pointer p-4 rounded-lg border transition-colors ${isSelected ? "border-primary bg-primary/5 text-foreground ring-1 ring-primary/40" : "border-border hover:border-border/80 bg-background/50 text-muted-foreground"}`}>
                      <div className="flex items-center justify-between"><span className="font-semibold text-sm text-foreground">{item.title}</span>{isSelected && <Check className="w-4 h-4 text-primary" />}</div>
                      <div className="text-xs text-muted-foreground mt-1">{item.desc}</div>
                    </div>
                  );
                })}
              </div>

              {/* 세부 액션 입력 폼 */}
              <div className="p-4 rounded-lg border border-border bg-card space-y-3.5 mt-3">
                {formState.actionType === "DISCORD_SEND_MESSAGE" && (
                  <>
                    <div>
                      <label className="text-xs font-semibold text-muted-foreground">전송 대상 채널 ID</label>
                      <Input
                        value={formState.actionParams.channelId || ""}
                        onChange={(e) =>
                          setFormState({
                            ...formState,
                            actionParams: { ...formState.actionParams, channelId: e.target.value },
                          })
                        }
                        placeholder="123456789012345678"
                        className="mt-1.5 h-9 bg-background border-border text-foreground text-sm"
                      />
                    </div>
                    <div>
                      <label className="text-xs font-semibold text-muted-foreground">메시지 내용</label>
                      <textarea
                        value={formState.actionParams.message || ""}
                        onChange={(e) =>
                          setFormState({
                            ...formState,
                            actionParams: { ...formState.actionParams, message: e.target.value },
                          })
                        }
                        rows={3}
                        placeholder="축하합니다! {User_Nickname}님이 {Level}레벨에 도달했습니다!"
                        className="mt-1.5 w-full rounded-md border border-border bg-background p-3 text-sm text-foreground focus:outline-none focus:ring-1 focus:ring-primary"
                      />
                      <div className="text-xs text-muted-foreground mt-1">
                        사용 가능 변수: {ALLOWED_VARIABLES.map((v) => `{${v}}`).join(", ")}
                      </div>
                    </div>
                  </>
                )}

                {formState.actionType === "MINECRAFT_DISPATCH_COMMAND" && (
                  <div>
                    <label className="text-xs font-semibold text-muted-foreground">콘솔 실행 명령어</label>
                    <Input
                      value={formState.actionParams.command || ""}
                      onChange={(e) =>
                        setFormState({
                          ...formState,
                          actionParams: { ...formState.actionParams, command: e.target.value },
                        })
                      }
                      placeholder="give {User_Nickname} diamond 1"
                      className="mt-1.5 h-9 bg-background border-border text-foreground text-sm font-mono"
                    />
                  </div>
                )}

                {formState.actionType === "COMMAND_AND_DISCORD" && (
                  <>
                    <div>
                      <label className="text-xs font-semibold text-muted-foreground">콘솔 실행 명령어</label>
                      <Input value={formState.actionParams.command || ""} onChange={(e) => setFormState({ ...formState, actionParams: { ...formState.actionParams, command: e.target.value } })} placeholder="give {User_Nickname} diamond 1" className="mt-1.5 h-9 bg-background border-border text-foreground text-sm font-mono" />
                    </div>
                    <div>
                      <label className="text-xs font-semibold text-muted-foreground">알림 전송 채널 ID</label>
                      <Input value={formState.actionParams.channelId || ""} onChange={(e) => setFormState({ ...formState, actionParams: { ...formState.actionParams, channelId: e.target.value } })} placeholder="123456789012345678" className="mt-1.5 h-9 bg-background border-border text-foreground text-sm" />
                    </div>
                    <div>
                      <label className="text-xs font-semibold text-muted-foreground">메시지 내용</label>
                      <textarea value={formState.actionParams.message || ""} onChange={(e) => setFormState({ ...formState, actionParams: { ...formState.actionParams, message: e.target.value } })} rows={3} placeholder="축하합니다! {User_Nickname}님에게 다이아몬드가 지급되었습니다!" className="mt-1.5 w-full rounded-md border border-border bg-background p-3 text-sm text-foreground focus:outline-none focus:ring-1 focus:ring-primary" />
                      <div className="text-xs text-muted-foreground mt-1">사용 가능 변수: {ALLOWED_VARIABLES.map((v) => `{${v}}`).join(", ")}</div>
                    </div>
                  </>
                )}

                {formState.actionType === "ATTENDANCE_REWARD_PIPELINE" && (
                  <>
                    <div>
                      <label className="text-xs font-semibold text-muted-foreground">일일 선착순 정원 (N명)</label>
                      <Input type="number" value={formState.actionParams.totalLimit ?? 100} onChange={(e) => setFormState({ ...formState, actionParams: { ...formState.actionParams, totalLimit: parseInt(e.target.value, 10) || 0 } })} className="mt-1.5 h-9 bg-background border-border text-foreground text-sm" />
                    </div>
                    <div>
                      <label className="text-xs font-semibold text-muted-foreground">출석 완료 시 지급 명령어</label>
                      <Input value={formState.actionParams.command || ""} onChange={(e) => setFormState({ ...formState, actionParams: { ...formState.actionParams, command: e.target.value } })} placeholder="give {User_Nickname} iron_ingot 5" className="mt-1.5 h-9 bg-background border-border text-foreground text-sm font-mono" />
                    </div>
                    <div>
                      <label className="text-xs font-semibold text-muted-foreground">결과 안내 채널 ID (선택)</label>
                      <Input value={formState.actionParams.channelId || ""} onChange={(e) => setFormState({ ...formState, actionParams: { ...formState.actionParams, channelId: e.target.value } })} placeholder="123456789012345678" className="mt-1.5 h-9 bg-background border-border text-foreground text-sm" />
                    </div>
                    <div>
                      <label className="text-xs font-semibold text-muted-foreground">안내 메시지 내용 (선택)</label>
                      <textarea value={formState.actionParams.message || ""} onChange={(e) => setFormState({ ...formState, actionParams: { ...formState.actionParams, message: e.target.value } })} rows={2} placeholder="{User_Nickname}님이 오늘 출석 체크를 완료했습니다!" className="mt-1.5 w-full rounded-md border border-border bg-background p-3 text-sm text-foreground focus:outline-none focus:ring-1 focus:ring-primary" />
                    </div>
                  </>
                )}
              </div>
            </div>
          )}
        </CardContent>

        <CardFooter className="flex justify-between border-t border-border pt-4">
          <Button
            variant="outline"
            size="sm"
            disabled={step === 1}
            onClick={() => setStep((step - 1) as 1 | 2 | 3)}
            className="text-xs border-border"
          >
            <ArrowLeft className="w-3.5 h-3.5 mr-1" /> 이전
          </Button>

          <div className="flex gap-2">
            {step < 3 ? (
              <Button size="sm" onClick={() => setStep((step + 1) as 1 | 2 | 3)} className="text-xs gap-1">
                다음 <ArrowRight className="w-3.5 h-3.5 ml-1" />
              </Button>
            ) : (
              <>
                <Button
                  variant="secondary"
                  size="sm"
                  disabled={loading || !validation.valid}
                  onClick={handleSaveDraft}
                  className="text-xs"
                >
                  임시 저장
                </Button>
                <Button
                  size="sm"
                  disabled={loading || !validation.valid}
                  onClick={handleDeploy}
                  className="text-xs bg-primary hover:bg-primary/90 text-primary-foreground gap-1.5"
                >
                  <Send className="w-3.5 h-3.5" /> 실서버 적용하기
                </Button>
                {createdWorkflowId && (
                  <Button variant="outline" size="sm" onClick={handleRollback} className="text-xs">
                    <RotateCcw className="w-3.5 h-3.5 mr-1" /> 이전 버전 복원
                  </Button>
                )}
              </>
            )}
          </div>
        </CardFooter>
      </Card>

      {/* 4. 실시간 시각적 파이프라인 플로우 요약 바 (Linear 스타일) */}
      <div className="rounded-lg border border-border bg-card p-4 space-y-3">
        <div className="flex items-center justify-between text-xs font-semibold text-muted-foreground uppercase tracking-wider">
          <span>실시간 파이프라인 흐름</span>
          <span className="font-mono lowercase text-[11px] text-muted-foreground/80">
            {activeVersion ? `v${activeVersion} ACTIVE` : "v1 DRAFT"}
          </span>
        </div>

        <div className="flex flex-wrap items-center gap-2 text-xs">
          <span className="px-2.5 py-1 rounded bg-secondary text-secondary-foreground font-medium border border-border">
            {triggerLabel}
          </span>
          <ArrowRight className="w-3.5 h-3.5 text-muted-foreground shrink-0" />
          <span className="px-2.5 py-1 rounded bg-secondary text-secondary-foreground font-medium border border-border">
            {formState.enableCondition
              ? `조건: ${formState.conditionField || "field"} ${formState.conditionOperator} ${formState.conditionValue || "value"}`
              : "무조건 실행 (필터 없음)"}
          </span>
          <ArrowRight className="w-3.5 h-3.5 text-muted-foreground shrink-0" />
          <span className="px-2.5 py-1 rounded bg-primary/10 text-primary font-medium border border-primary/20">
            {actionLabel}
          </span>
        </div>

        {/* 유효성 검증 오류 안내 */}
        {!validation.valid && (
          <div className="p-3 rounded bg-rose-950/20 border border-rose-800/40 text-xs text-rose-400 dark:text-rose-300 space-y-1">
            <div className="font-semibold flex items-center gap-1">
              <AlertCircle className="w-3.5 h-3.5" /> 입력 필요 항목:
            </div>
            <ul className="list-disc list-inside space-y-0.5">
              {validation.errors.map((err, i) => (
                <li key={i}>{err}</li>
              ))}
            </ul>
          </div>
        )}

        {/* 고급 사용자용: JSON 데이터 구조 확인 아코디언 */}
        <details className="pt-2 text-xs text-muted-foreground group">
          <summary className="cursor-pointer hover:text-foreground flex items-center gap-1 select-none text-xs">
            <ChevronDown className="w-3.5 h-3.5 transition-transform group-open:rotate-180" />
            <span>연동 규칙 데이터 구조 확인 (JSON)</span>
          </summary>
          <pre className="mt-2 p-3 rounded-lg bg-background border border-border text-[11px] font-mono text-emerald-600 dark:text-emerald-400 overflow-x-auto max-h-[220px]">
            {JSON.stringify(ast, null, 2)}
          </pre>
        </details>
      </div>
    </div>
  );
}
