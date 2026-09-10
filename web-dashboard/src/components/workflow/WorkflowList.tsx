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
}

export function WorkflowList({ tenantId, refreshTrigger }: WorkflowListProps) {
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
    <Card>
      <CardHeader>
        <div className="flex items-center justify-between">
          <div>
            <CardTitle>등록된 워크플로우 목록</CardTitle>
            <CardDescription>
              현재 테넌트에 구성되어 있는 자동화 규칙과 배포 버전 현황입니다.
            </CardDescription>
          </div>
          <Button variant="outline" size="sm" onClick={loadData} disabled={loading}>
            새로고침
          </Button>
        </div>
      </CardHeader>
      <CardContent>
        {loading ? (
          <div className="p-8 text-center text-slate-500 text-sm">워크플로우 목록 불러오는 중...</div>
        ) : workflows.length === 0 ? (
          <div className="p-8 text-center text-slate-500 text-sm border border-dashed border-slate-800 rounded-md">
            등록된 워크플로우가 없습니다. 위저드 빌더 탭에서 새 워크플로우를 생성하세요.
          </div>
        ) : (
          <div className="space-y-3">
            {workflows.map((wf) => (
              <div
                key={wf.id}
                className="p-4 rounded-md border border-slate-800 bg-slate-950 flex flex-col md:flex-row md:items-center justify-between gap-4"
              >
                <div className="space-y-1">
                  <div className="flex items-center gap-2">
                    <span className="font-semibold text-white">{wf.name}</span>
                    <Badge variant={wf.activeVersion ? "success" : "secondary"}>
                      {wf.activeVersion ? `v${wf.activeVersion} ACTIVE` : "DRAFT"}
                    </Badge>
                  </div>
                  {wf.description && (
                    <p className="text-xs text-slate-400">{wf.description}</p>
                  )}
                  <div className="flex items-center gap-4 text-xs text-slate-500 font-mono pt-1">
                    <span>ID: {wf.id}</span>
                    <span className="flex items-center gap-1">
                      <Clock className="w-3 h-3" /> {new Date(wf.updatedAt).toLocaleDateString()}
                    </span>
                  </div>
                </div>

                <div className="flex items-center gap-2">
                  <Button
                    size="sm"
                    variant="outline"
                    onClick={() => handleQuickDeploy(wf.id)}
                    className="text-xs border-slate-700 hover:border-emerald-500"
                  >
                    <Play className="w-3.5 h-3.5 mr-1 text-emerald-400" /> 즉시 배포
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
