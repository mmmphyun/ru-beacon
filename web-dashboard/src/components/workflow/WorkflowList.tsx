"use client";

import React, { useEffect, useState } from "react";
import { WorkflowSummary } from "@/types/workflow";
import { fetchWorkflows, deployWorkflow } from "@/lib/api";
import { Card, CardHeader, CardTitle, CardDescription, CardContent } from "@/components/ui/card";
import { Button } from "@/components/ui/button";
import { Badge } from "@/components/ui/badge";
import { GitBranch, Play, Clock, CheckCircle } from "lucide-react";

interface WorkflowListProps {
  tenantId: string;
  refreshTrigger?: number;
  onCreateNew?: () => void;
}

export function WorkflowList({ tenantId, refreshTrigger, onCreateNew }: WorkflowListProps) {
  const [workflows, setWorkflows] = useState<WorkflowSummary[]>([]);
  const [loading, setLoading] = useState(true);

  const loadData = () => {
    setLoading(true);
    fetchWorkflows(tenantId)
      .then((data) => setWorkflows(data))
      .finally(() => setLoading(false));
  };

  useEffect(() => {
    loadData();
  }, [tenantId, refreshTrigger]);

  const handleQuickDeploy = async (id: string) => {
    await deployWorkflow(id);
    loadData();
  };

  return (
    <Card className="border border-border/60 bg-card/80 backdrop-blur">
      <CardHeader>
        <div className="flex items-center justify-between">
          <div>
            <CardTitle className="text-base font-semibold text-foreground">등록된 워크플로우 목록</CardTitle>
            <CardDescription className="text-xs text-muted-foreground mt-0.5">
              마인크래프트와 디스코드를 연결하는 실시간 자동화 규칙 목록입니다.
            </CardDescription>
          </div>
          <Button variant="outline" size="sm" onClick={loadData} disabled={loading} className="h-8 text-xs">
            새로고침
          </Button>
        </div>
      </CardHeader>
      <CardContent>
        {loading ? (
          <div className="p-8 text-center text-muted-foreground text-xs">워크플로우 목록을 불러오는 중...</div>
        ) : workflows.length === 0 ? (
          <div className="p-10 text-center border border-dashed border-border/70 rounded-md bg-muted/10 space-y-3">
            <p className="text-sm font-medium text-foreground">등록된 연동 규칙이 없습니다.</p>
            <p className="text-xs text-muted-foreground">
              게임 내 이벤트(레벨업, 발전과제) 발생 시 디스코드 알림을 보내는 규칙을 만들어보세요.
            </p>
            {onCreateNew && (
              <Button size="sm" onClick={onCreateNew} className="h-8 text-xs bg-primary hover:bg-primary/90 text-primary-foreground gap-1.5 mt-2">
                새 연동 규칙 만들기
              </Button>
            )}
          </div>
        ) : (
          <div className="space-y-3">
            {workflows.map((wf) => (
              <div
                key={wf.id}
                className="p-4 rounded-md border border-border/70 bg-card/60 hover:bg-card/90 transition-colors flex flex-col md:flex-row md:items-center justify-between gap-4"
              >
                <div className="space-y-1">
                  <div className="flex items-center gap-2">
                    <span className="font-semibold text-sm text-foreground">{wf.name}</span>
                    <Badge variant={wf.activeVersion ? "success" : "secondary"} className="text-[10px] font-mono">
                      {wf.activeVersion ? `v${wf.activeVersion} 적용 중` : "임시 저장"}
                    </Badge>
                  </div>
                  {wf.description && (
                    <p className="text-xs text-muted-foreground leading-relaxed">{wf.description}</p>
                  )}
                  <div className="flex items-center gap-4 text-xs text-muted-foreground font-mono pt-1">
                    <span>규칙 ID: {wf.id}</span>
                    <span className="flex items-center gap-1">
                      <Clock className="w-3 h-3" /> 최근 수정: {new Date(wf.updatedAt).toLocaleDateString()}
                    </span>
                  </div>
                </div>

                <div className="flex items-center gap-2">
                  <Button
                    size="sm"
                    variant="outline"
                    onClick={() => handleQuickDeploy(wf.id)}
                    className="text-xs border-border/70 hover:border-primary hover:text-primary transition-colors h-8"
                  >
                    <Play className="w-3.5 h-3.5 mr-1 text-primary" /> 즉시 적용
                  </Button>
                </div>
              </div>
            ))}
          </div>
        )}
      </CardContent>
    </Card>
  );
}
