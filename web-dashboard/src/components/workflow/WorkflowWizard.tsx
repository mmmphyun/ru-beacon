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
import { CheckCircle2, AlertCircle, ArrowRight, ArrowLeft, Send, RotateCcw, Sparkles } from "lucide-react";

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

  // 실시간 AST 생성 및 유효성 검증
  const ast = useMemo(() => generateWorkflowAst(formState, activeVersion ?? 1), [formState, activeVersion]);
  const validation = useMemo(() => validateWorkflowState(formState), [formState]);

  const handleApplyPreset = (index: number) => {
    setFormState(WORKFLOW_PRESETS[index].state);
    setStatusMessage({ type: "success", text: `프리셋 '${WORKFLOW_PRESETS[index].label}'을 불러왔습니다.` });
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
    } catch (e: unknown) {
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
    } catch (e: unknown) {
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
    } catch (e: unknown) {
      setStatusMessage({ type: "error", text: "롤백 중 오류가 발생했습니다." });
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="space-y-6">
      {/* 상단 프리셋 바 */}
      <div className="flex flex-wrap items-center justify-between gap-4 p-4 rounded-lg bg-slate-900 border border-slate-800">
        <div className="flex items-center gap-2 text-slate-300 text-sm">
          <Sparkles className="w-4 h-4 text-blue-400" />
          <span className="font-semibold text-white">빠른 템플릿 프리셋:</span>
        </div>
        <div className="flex flex-wrap gap-2">
          {WORKFLOW_PRESETS.map((p, idx) => (
            <Button
              key={idx}
              variant="outline"
              size="sm"
              onClick={() => handleApplyPreset(idx)}
              className="text-xs border-slate-700 hover:border-blue-500"
            >
              {p.label}
            </Button>
          ))}
        </div>
      </div>

      {/* 알림 메시지 */}
      {statusMessage && (
        <div
          className={`p-4 rounded-md border text-sm flex items-center gap-2 ${
            statusMessage.type === "success"
              ? "bg-emerald-950/40 border-emerald-800 text-emerald-300"
              : "bg-rose-950/40 border-rose-800 text-rose-300"
          }`}
        >
          {statusMessage.type === "success" ? <CheckCircle2 className="w-4 h-4" /> : <AlertCircle className="w-4 h-4" />}
          <span>{statusMessage.text}</span>
        </div>
      )}

      {/* 스텝 인디케이터 */}
      <div className="grid grid-cols-3 gap-2 text-center text-sm font-medium">
        <div
          onClick={() => setStep(1)}
          className={`cursor-pointer p-3 rounded-md border transition-all ${
            step === 1
              ? "bg-blue-600/20 border-blue-500 text-white"
              : "bg-slate-900/50 border-slate-800 text-slate-400"
          }`}
        >
          <span className="font-bold mr-2">1.</span> 트리거 선택
        </div>
        <div
          onClick={() => setStep(2)}
          className={`cursor-pointer p-3 rounded-md border transition-all ${
            step === 2
              ? "bg-blue-600/20 border-blue-500 text-white"
              : "bg-slate-900/50 border-slate-800 text-slate-400"
          }`}
        >
          <span className="font-bold mr-2">2.</span> 조건 필터링
        </div>
        <div
          onClick={() => setStep(3)}
          className={`cursor-pointer p-3 rounded-md border transition-all ${
            step === 3
              ? "bg-blue-600/20 border-blue-500 text-white"
              : "bg-slate-900/50 border-slate-800 text-slate-400"
          }`}
        >
          <span className="font-bold mr-2">3.</span> 실행 액션
        </div>
      </div>

      <div className="grid grid-cols-1 lg:grid-cols-12 gap-6">
        {/* 좌측: 위저드 단계별 카드 폼 */}
        <div className="lg:col-span-7 space-y-4">
          <Card>
            <CardHeader>
              <div className="flex items-center justify-between">
                <div>
                  <CardTitle>
                    {step === 1 && "1단계: 시작 트리거 이벤트 선택"}
                    {step === 2 && "2단계: 조건부 필터 설정 (선택)"}
                    {step === 3 && "3단계: 실행할 액션 지정"}
                  </CardTitle>
                  <CardDescription>
                    {step === 1 && "마인크래프트 서버 또는 Discord에서 발생할 이벤트를 선택합니다."}
                    {step === 2 && "특정 레벨 이상 또는 특정 조건일 때만 액션이 실행되도록 필터링합니다."}
                    {step === 3 && "조건을 충족했을 때 전송할 Discord 메시지나 서버 명령어를 구성합니다."}
                  </CardDescription>
                </div>
                <Badge variant={validation.valid ? "success" : "destructive"}>
                  {validation.valid ? "검증 통과" : "입력 필요"}
                </Badge>
              </div>
            </CardHeader>
            <CardContent className="space-y-4">
              {/* 워크플로우 기본 정보 (항상 노출) */}
              <div className="grid grid-cols-2 gap-3 pb-3 border-b border-slate-800">
                <div>
                  <label className="text-xs font-semibold text-slate-400">워크플로우 명칭</label>
                  <Input
                    value={formState.workflowName}
                    onChange={(e) => setFormState({ ...formState, workflowName: e.target.value })}
                    placeholder="예: 레벨업 축하 공지"
                    className="mt-1"
                  />
                </div>
                <div>
                  <label className="text-xs font-semibold text-slate-400">설명</label>
                  <Input
                    value={formState.description}
                    onChange={(e) => setFormState({ ...formState, description: e.target.value })}
                    placeholder="워크플로우의 용도를 간략히 기술하세요"
                    className="mt-1"
                  />
                </div>
              </div>

              {/* Step 1: Trigger */}
              {step === 1 && (
                <div className="space-y-3">
                  <label className="text-xs font-semibold text-slate-400">이벤트 트리거 유형</label>
                  <div className="grid grid-cols-2 gap-2">
                    {[
                      { type: "MINECRAFT_LEVEL_UP", title: "플레이어 레벨업", desc: "플레이어의 레벨이 상승했을 때" },
                      { type: "MINECRAFT_ADVANCEMENT_DONE", title: "발전과제 달성", desc: "마인크래프트 발전과제를 완수했을 때" },
                      { type: "DISCORD_BUTTON_CLICK", title: "Discord 버튼 클릭", desc: "임베드 메시지의 버튼을 눌렀을 때" },
                      { type: "DISCORD_SLASH_COMMAND", title: "Discord 슬래시 명령", desc: "/명령어 실행 인터랙션" },
                    ].map((item) => (
                      <div
                        key={item.type}
                        onClick={() =>
                          setFormState({
                            ...formState,
                            triggerType: item.type as WizardFormState["triggerType"],
                          })
                        }
                        className={`cursor-pointer p-3 rounded-md border transition-all ${
                          formState.triggerType === item.type
                            ? "border-blue-500 bg-blue-950/30 text-white"
                            : "border-slate-800 hover:border-slate-700 bg-slate-950 text-slate-300"
                        }`}
                      >
                        <div className="font-medium text-sm">{item.title}</div>
                        <div className="text-xs text-slate-500 mt-1">{item.desc}</div>
                      </div>
                    ))}
                  </div>

                  {formState.triggerType === "DISCORD_BUTTON_CLICK" && (
                    <div className="pt-2">
                      <label className="text-xs font-semibold text-slate-400">Button Custom ID</label>
                      <Input
                        value={formState.triggerParams.customId || ""}
                        onChange={(e) =>
                          setFormState({
                            ...formState,
                            triggerParams: { ...formState.triggerParams, customId: e.target.value },
                          })
                        }
                        placeholder="btn_daily_attendance"
                        className="mt-1"
                      />
                    </div>
                  )}
                </div>
              )}

              {/* Step 2: Condition */}
              {step === 2 && (
                <div className="space-y-4">
                  <div className="flex items-center gap-2">
                    <input
                      type="checkbox"
                      id="enableCondition"
                      checked={formState.enableCondition}
                      onChange={(e) => setFormState({ ...formState, enableCondition: e.target.checked })}
                      className="rounded border-slate-700 text-blue-600 focus:ring-blue-500 h-4 w-4 bg-slate-950"
                    />
                    <label htmlFor="enableCondition" className="text-sm font-medium text-slate-200 cursor-pointer">
                      조건부 분기 노드 (CONDITION_BRANCH) 활성화
                    </label>
                  </div>

                  {formState.enableCondition ? (
                    <div className="p-4 rounded-md border border-slate-800 bg-slate-950 space-y-3">
                      <div>
                        <label className="text-xs font-semibold text-slate-400">검사 대상 필드</label>
                        <Input
                          value={formState.conditionField}
                          onChange={(e) => setFormState({ ...formState, conditionField: e.target.value })}
                          placeholder="new_level 또는 role_id"
                          className="mt-1"
                        />
                      </div>

                      <div className="grid grid-cols-2 gap-3">
                        <div>
                          <label className="text-xs font-semibold text-slate-400">비교 연산자</label>
                          <select
                            value={formState.conditionOperator}
                            onChange={(e) =>
                              setFormState({
                                ...formState,
                                conditionOperator: e.target.value as WizardFormState["conditionOperator"],
                              })
                            }
                            className="mt-1 w-full rounded-md border border-slate-700 bg-slate-950 px-3 py-2 text-sm text-slate-100 focus:outline-none focus:ring-1 focus:ring-blue-500"
                          >
                            <option value="GREATER_OR_EQUAL">&gt;= (크거나 같음)</option>
                            <option value="EQUALS">== (일치)</option>
                            <option value="CONTAINS">포함 (Contains)</option>
                          </select>
                        </div>
                        <div>
                          <label className="text-xs font-semibold text-slate-400">비교 기준값</label>
                          <Input
                            value={formState.conditionValue}
                            onChange={(e) => setFormState({ ...formState, conditionValue: e.target.value })}
                            placeholder="30"
                            className="mt-1"
                          />
                        </div>
                      </div>
                    </div>
                  ) : (
                    <div className="p-6 rounded-md border border-dashed border-slate-800 text-center text-slate-500 text-sm">
                      조건 필터를 사용하지 않고 트리거 발생 시 즉시 3단계 액션을 실행합니다.
                    </div>
                  )}
                </div>
              )}

              {/* Step 3: Action */}
              {step === 3 && (
                <div className="space-y-4">
                  <label className="text-xs font-semibold text-slate-400">실행할 액션 유형</label>
                  <div className="grid grid-cols-2 gap-2">
                    {[
                      { type: "DISCORD_SEND_MESSAGE", title: "Discord 메시지 전송", desc: "채널에 알림 메시지를 발송합니다." },
                      { type: "DISCORD_ADD_ROLE", title: "Discord 역할 부여", desc: "사용자에게 역할을 자동 부여합니다." },
                      { type: "MINECRAFT_DISPATCH_COMMAND", title: "Minecraft 명령 실행", desc: "서버 콘솔 명령어를 실행합니다." },
                      { type: "ATTENDANCE_RESERVATION", title: "선착순 출석 예약", desc: "2-Tier Redis 한정 수량 출석을 처리합니다." },
                    ].map((item) => (
                      <div
                        key={item.type}
                        onClick={() =>
                          setFormState({
                            ...formState,
                            actionType: item.type as WizardFormState["actionType"],
                          })
                        }
                        className={`cursor-pointer p-3 rounded-md border transition-all ${
                          formState.actionType === item.type
                            ? "border-blue-500 bg-blue-950/30 text-white"
                            : "border-slate-800 hover:border-slate-700 bg-slate-950 text-slate-300"
                        }`}
                      >
                        <div className="font-medium text-sm">{item.title}</div>
                        <div className="text-xs text-slate-500 mt-1">{item.desc}</div>
                      </div>
                    ))}
                  </div>

                  {/* 세부 액션 입력 폼 */}
                  <div className="p-4 rounded-md border border-slate-800 bg-slate-950 space-y-3">
                    {formState.actionType === "DISCORD_SEND_MESSAGE" && (
                      <>
                        <div>
                          <label className="text-xs font-semibold text-slate-400">전송 대상 채널 ID</label>
                          <Input
                            value={formState.actionParams.channelId || ""}
                            onChange={(e) =>
                              setFormState({
                                ...formState,
                                actionParams: { ...formState.actionParams, channelId: e.target.value },
                              })
                            }
                            placeholder="123456789012345678"
                            className="mt-1"
                          />
                        </div>
                        <div>
                          <label className="text-xs font-semibold text-slate-400">메시지 내용</label>
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
                            className="mt-1 w-full rounded-md border border-slate-700 bg-slate-950 px-3 py-2 text-sm text-slate-100 focus:outline-none focus:ring-1 focus:ring-blue-500"
                          />
                          <div className="text-xs text-slate-500 mt-1">
                            사용 가능 변수: {ALLOWED_VARIABLES.map((v) => `{${v}}`).join(", ")}
                          </div>
                        </div>
                      </>
                    )}

                    {formState.actionType === "DISCORD_ADD_ROLE" && (
                      <div>
                        <label className="text-xs font-semibold text-slate-400">부여할 역할 ID</label>
                        <Input
                          value={formState.actionParams.roleId || ""}
                          onChange={(e) =>
                            setFormState({
                              ...formState,
                              actionParams: { ...formState.actionParams, roleId: e.target.value },
                            })
                          }
                          placeholder="987654321012345678"
                          className="mt-1"
                        />
                      </div>
                    )}

                    {formState.actionType === "MINECRAFT_DISPATCH_COMMAND" && (
                      <div>
                        <label className="text-xs font-semibold text-slate-400">콘솔 실행 명령어</label>
                        <Input
                          value={formState.actionParams.command || ""}
                          onChange={(e) =>
                            setFormState({
                              ...formState,
                              actionParams: { ...formState.actionParams, command: e.target.value },
                            })
                          }
                          placeholder="give {User_Nickname} diamond 1"
                          className="mt-1"
                        />
                      </div>
                    )}

                    {formState.actionType === "ATTENDANCE_RESERVATION" && (
                      <div>
                        <label className="text-xs font-semibold text-slate-400">일일 선착순 정원 (N명)</label>
                        <Input
                          type="number"
                          value={formState.actionParams.totalLimit ?? 100}
                          onChange={(e) =>
                            setFormState({
                              ...formState,
                              actionParams: {
                                ...formState.actionParams,
                                totalLimit: parseInt(e.target.value, 10) || 0,
                              },
                            })
                          }
                          className="mt-1"
                        />
                      </div>
                    )}
                  </div>
                </div>
              )}
            </CardContent>
            <CardFooter className="flex justify-between border-t border-slate-800 pt-4">
              <Button
                variant="outline"
                size="sm"
                disabled={step === 1}
                onClick={() => setStep((step - 1) as 1 | 2 | 3)}
              >
                <ArrowLeft className="w-4 h-4 mr-1" /> 이전
              </Button>
              <div className="flex gap-2">
                {step < 3 ? (
                  <Button size="sm" onClick={() => setStep((step + 1) as 1 | 2 | 3)}>
                    다음 <ArrowRight className="w-4 h-4 ml-1" />
                  </Button>
                ) : (
                  <>
                    <Button
                      variant="secondary"
                      size="sm"
                      disabled={loading || !validation.valid}
                      onClick={handleSaveDraft}
                    >
                      초안 저장
                    </Button>
                    <Button
                      size="sm"
                      disabled={loading || !validation.valid}
                      onClick={handleDeploy}
                      className="bg-emerald-600 hover:bg-emerald-700"
                    >
                      <Send className="w-4 h-4 mr-1" /> 배포(Publish)
                    </Button>
                    {createdWorkflowId && (
                      <Button variant="outline" size="sm" onClick={handleRollback}>
                        <RotateCcw className="w-4 h-4 mr-1" /> 롤백
                      </Button>
                    )}
                  </>
                )}
              </div>
            </CardFooter>
          </Card>
        </div>

        {/* 우측: 실시간 AST JSON & 검증 오류 패널 */}
        <div className="lg:col-span-5 space-y-4">
          <Card className="bg-slate-950">
            <CardHeader className="pb-3">
              <div className="flex items-center justify-between">
                <CardTitle className="text-sm font-mono text-slate-300">Workflow AST (JSONB)</CardTitle>
                <span className="text-xs text-slate-500 font-mono">
                  {activeVersion ? `v${activeVersion} ACTIVE` : "v1 DRAFT"}
                </span>
              </div>
            </CardHeader>
            <CardContent>
              <pre className="p-3 rounded bg-slate-900 border border-slate-800 text-xs font-mono text-emerald-400 overflow-x-auto max-h-[380px]">
                {JSON.stringify(ast, null, 2)}
              </pre>

              {/* 검증 상태 안내 */}
              {!validation.valid && (
                <div className="mt-3 p-3 rounded bg-rose-950/50 border border-rose-900 text-xs text-rose-300 space-y-1">
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
            </CardContent>
          </Card>
        </div>
      </div>
    </div>
  );
}
