"use client";

import React, { useEffect, useState } from "react";
import { AuditLogItem } from "@/types/workflow";
import { fetchAuditLogs } from "@/lib/api";
import { Button } from "@/components/ui/button";
import { Badge } from "@/components/ui/badge";
import { Card, CardHeader, CardTitle, CardDescription, CardContent } from "@/components/ui/card";
import { Shield, RefreshCw, ChevronDown, CheckCircle2, XCircle } from "lucide-react";

interface AuditLogTableProps {
  tenantId?: string;
  adminSecret?: string;
  title?: string;
  description?: string;
}

export function AuditLogTable({
  tenantId,
  adminSecret,
  title = "비즈니스 감사 로그 (Audit Trail)",
  description = "ISMS-P 기준을 준수하는 테넌트 내 주요 운영 및 자동화 실행 이력입니다.",
}: AuditLogTableProps) {
  const [logs, setLogs] = useState<AuditLogItem[]>([]);
  const [nextCursor, setNextCursor] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);
  const [loadingMore, setLoadingMore] = useState(false);

  const loadInitialLogs = async () => {
    setLoading(true);
    try {
      const res = await fetchAuditLogs({ tenantId, limit: 15, adminSecret });
      setLogs(res.items);
      setNextCursor(res.nextCursor);
    } finally {
      setLoading(false);
    }
  };

  const loadMoreLogs = async () => {
    if (!nextCursor || loadingMore) return;
    setLoadingMore(true);
    try {
      const res = await fetchAuditLogs({ tenantId, cursor: nextCursor, limit: 15, adminSecret });
      setLogs((prev) => [...prev, ...res.items]);
      setNextCursor(res.nextCursor);
    } finally {
      setLoadingMore(false);
    }
  };

  useEffect(() => {
    loadInitialLogs();
  }, [tenantId, adminSecret]);

  return (
    <Card className="border border-border/60 bg-card/80 backdrop-blur">
      <CardHeader className="flex flex-row items-center justify-between pb-4">
        <div>
          <CardTitle className="text-base font-semibold flex items-center gap-2">
            <Shield className="w-4 h-4 text-primary" />
            {title}
          </CardTitle>
          <CardDescription className="text-xs text-muted-foreground mt-0.5">
            {description}
          </CardDescription>
        </div>
        <Button
          variant="outline"
          size="sm"
          onClick={loadInitialLogs}
          disabled={loading}
          className="h-8 text-xs gap-1.5"
        >
          <RefreshCw className={`w-3.5 h-3.5 ${loading ? "animate-spin" : ""}`} />
          새로고침
        </Button>
      </CardHeader>

      <CardContent>
        {loading ? (
          <div className="py-12 text-center text-xs text-muted-foreground">감사 로그를 불러오는 중...</div>
        ) : logs.length === 0 ? (
          <div className="py-12 text-center text-xs text-muted-foreground border border-dashed border-border/60 rounded">
            기록된 감사 로그가 없습니다.
          </div>
        ) : (
          <div className="space-y-3">
            <div className="overflow-x-auto border border-border/40 rounded">
              <table className="w-full text-xs text-left">
                <thead className="bg-muted/40 text-muted-foreground border-b border-border/40 font-mono text-[11px] uppercase tracking-wider">
                  <tr>
                    <th className="py-2.5 px-3">발생 일시</th>
                    <th className="py-2.5 px-3">행위자</th>
                    <th className="py-2.5 px-3">액션</th>
                    <th className="py-2.5 px-3">대상</th>
                    <th className="py-2.5 px-3">상태</th>
                    <th className="py-2.5 px-3">IP (비식별화)</th>
                    <th className="py-2.5 px-3">상세 내용</th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-border/20 font-sans">
                  {logs.map((log) => (
                    <tr key={log.id} className="hover:bg-muted/20 transition-colors">
                      <td className="py-2.5 px-3 whitespace-nowrap text-muted-foreground font-mono text-[11px]">
                        {new Date(log.createdAt).toLocaleString("ko-KR", {
                          month: "2-digit",
                          day: "2-digit",
                          hour: "2-digit",
                          minute: "2-digit",
                          second: "2-digit",
                        })}
                      </td>
                      <td className="py-2.5 px-3 whitespace-nowrap">
                        <span className="font-mono text-[11px] px-1.5 py-0.5 rounded bg-muted text-foreground border border-border/30">
                          {log.actorType === "DISCORD_USER" ? "Discord" : log.actorType}: {log.actorId}
                        </span>
                      </td>
                      <td className="py-2.5 px-3 whitespace-nowrap">
                        <Badge variant="outline" className="font-mono text-[10px] uppercase font-medium">
                          {log.action}
                        </Badge>
                      </td>
                      <td className="py-2.5 px-3 whitespace-nowrap text-muted-foreground font-mono text-[11px]">
                        {log.targetType ? `${log.targetType}:${log.targetId || "-"}` : "-"}
                      </td>
                      <td className="py-2.5 px-3 whitespace-nowrap">
                        {log.status === "SUCCESS" ? (
                          <span className="inline-flex items-center gap-1 text-emerald-500 font-medium text-[11px]">
                            <CheckCircle2 className="w-3.5 h-3.5" /> 성공
                          </span>
                        ) : (
                          <span className="inline-flex items-center gap-1 text-destructive font-medium text-[11px]">
                            <XCircle className="w-3.5 h-3.5" /> 실패
                          </span>
                        )}
                      </td>
                      <td className="py-2.5 px-3 whitespace-nowrap text-muted-foreground font-mono text-[11px]">
                        {log.ipAddress || "-"}
                      </td>
                      <td className="py-2.5 px-3 text-foreground/90 max-w-xs truncate" title={log.details}>
                        {log.details}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>

            {nextCursor && (
              <div className="pt-2 text-center">
                <Button
                  variant="outline"
                  size="sm"
                  onClick={loadMoreLogs}
                  disabled={loadingMore}
                  className="w-full text-xs gap-1 h-8"
                >
                  <ChevronDown className="w-3.5 h-3.5" />
                  {loadingMore ? "불러오는 중..." : "이전 로그 더 불러오기"}
                </Button>
              </div>
            )}
          </div>
        )}
      </CardContent>
    </Card>
  );
}
