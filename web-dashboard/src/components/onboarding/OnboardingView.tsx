"use client";

import React, { useState, useEffect } from "react";
import { TenantConfig, InstanceItem } from "@/types/workflow";
import { saveOnboarding, issueInstanceToken, fetchTenantDetail } from "@/lib/api";
import { Card, CardHeader, CardTitle, CardDescription, CardContent, CardFooter } from "@/components/ui/card";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Badge } from "@/components/ui/badge";
import { Shield, Key, Copy, Check, Server, RefreshCw, Download } from "lucide-react";

interface OnboardingViewProps {
  tenantId: string;
  isOwner?: boolean;
}

export function OnboardingView({ tenantId, isOwner = true }: OnboardingViewProps) {
  const [config, setConfig] = useState<TenantConfig>({
    tenantId,
    name: "루비콘 마인크래프트 커뮤니티",
    discordGuildId: "987654321098765432",
    policyMode: "MONITOR",
    authChannelId: "112233445566778899",
  });

  const [instances, setInstances] = useState<InstanceItem[]>([]);
  const [newInstanceName, setNewInstanceName] = useState("");
  const [newInstanceId, setNewInstanceId] = useState("");
  const [issuedToken, setIssuedToken] = useState<{ instanceId: string; token: string } | null>(null);
  const [copied, setCopied] = useState(false);
  const [loading, setLoading] = useState(false);
  const [savedMessage, setSavedMessage] = useState(false);

  useEffect(() => {
    fetchTenantDetail(tenantId).then((res) => {
      if (res) {
        setConfig({
          tenantId: res.tenantId,
          name: res.name,
          discordGuildId: res.discordGuildId,
          policyMode: res.policyMode,
          authChannelId: res.authChannelId,
        });
        setInstances(res.instances || []);
      }
    });
  }, [tenantId]);

  const handleSaveConfig = async () => {
    setLoading(true);
    try {
      await saveOnboarding(config);
      setSavedMessage(true);
      setTimeout(() => setSavedMessage(false), 3000);
    } finally {
      setLoading(false);
    }
  };

  const handleIssueToken = async () => {
    if (!newInstanceId.trim() || !newInstanceName.trim()) return;
    setLoading(true);
    try {
      const res = await issueInstanceToken({
        tenantId,
        instanceId: newInstanceId,
        name: newInstanceName,
        instanceType: "BACKEND",
      });
      setIssuedToken({ instanceId: res.instanceId, token: res.token });
      setInstances([
        ...instances,
        {
          id: res.instanceId,
          name: newInstanceName,
          instanceType: "BACKEND",
          status: "OFFLINE",
          lastHeartbeatAt: null,
        },
      ]);
      setNewInstanceId("");
      setNewInstanceName("");
    } finally {
      setLoading(false);
    }
  };

  const copyToClipboard = (text: string) => {
    navigator.clipboard.writeText(text);
    setCopied(true);
    setTimeout(() => setCopied(false), 2000);
  };

  return (
    <div className="space-y-6">
      {/* 테넌트 및 Discord 연동 카드 */}
      <Card>
        <CardHeader>
          <div className="flex items-center gap-2">
            <Shield className="w-5 h-5 text-blue-400" />
            <CardTitle>Discord 서버 & 보안 연동 설정</CardTitle>
          </div>
          <CardDescription>
            Ru-Beacon 봇이 활동할 Discord 길드 및 관리자 2FA 승인 보안 정책을 구성합니다.
          </CardDescription>
        </CardHeader>
        <CardContent className="space-y-4">
          <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
            <div>
              <label className="text-xs font-semibold text-slate-400">커뮤니티 / 서버명</label>
              <Input
                value={config.name}
                onChange={(e) => setConfig({ ...config, name: e.target.value })}
                className="mt-1"
              />
            </div>
            <div>
              <label className="text-xs font-semibold text-slate-400">Discord Guild ID (서버 ID)</label>
              <Input
                value={config.discordGuildId}
                onChange={(e) => setConfig({ ...config, discordGuildId: e.target.value })}
                className="mt-1"
              />
            </div>
          </div>

          <div className="grid grid-cols-1 md:grid-cols-2 gap-4 pt-2">
            <div>
              <label className="text-xs font-semibold text-slate-400">2FA 정책 모드 (Admin 2FA)</label>
              <select
                value={config.policyMode}
                onChange={(e) =>
                  setConfig({ ...config, policyMode: e.target.value as TenantConfig["policyMode"] })
                }
                className="mt-1 w-full rounded-md border border-slate-700 bg-slate-950 px-3 py-2 text-sm text-slate-100 focus:outline-none focus:ring-1 focus:ring-blue-500"
              >
                <option value="DISABLED">DISABLED (2FA 비활성화)</option>
                <option value="MONITOR">MONITOR (로그 감시 모드 - 기본)</option>
                <option value="ENFORCE">ENFORCE (미승인 시 서버 강제 퇴장)</option>
              </select>
            </div>
            <div>
              <label className="text-xs font-semibold text-slate-400">2FA 비공개 인증 채널 ID</label>
              <Input
                value={config.authChannelId || ""}
                onChange={(e) => setConfig({ ...config, authChannelId: e.target.value })}
                placeholder="비공개 채널 ID"
                className="mt-1"
              />
            </div>
          </div>
        </CardContent>
        <CardFooter className="flex justify-between border-t border-slate-800 pt-4">
          <span className="text-xs text-emerald-400 font-medium">
            {savedMessage && "연동 설정이 저장되었습니다."}
          </span>
          <Button size="sm" onClick={handleSaveConfig} disabled={loading || !isOwner}>
            {isOwner ? "설정 저장" : "저장 권한 없음 (OWNER 전용)"}
          </Button>
        </CardFooter>
      </Card>

      {/* 마인크래프트 인스턴스 및 인증 토큰 카드 */}
      <Card>
        <CardHeader className="flex flex-col sm:flex-row sm:items-center sm:justify-between gap-3">
          <div>
            <div className="flex items-center gap-2">
              <Key className="w-5 h-5 text-emerald-400" />
              <CardTitle>마인크래프트 인스턴스 인증 토큰 발급</CardTitle>
            </div>
            <CardDescription className="mt-1">
              Paper 플러그인이 API Service와 보안 WebSocket(WSS) 핸드셰이크를 수행할 때 사용하는 인증 토큰입니다.
            </CardDescription>
          </div>
          <a
            href="/api/download/plugin"
            target="_blank"
            rel="noopener noreferrer"
            className="inline-flex items-center justify-center px-3 py-1.5 text-xs font-medium rounded-md bg-emerald-600 hover:bg-emerald-500 text-white shadow transition-colors shrink-0"
          >
            <Download className="w-3.5 h-3.5 mr-1.5" />
            최신 플러그인 다운로드 (.jar)
          </a>
        </CardHeader>
        <CardContent className="space-y-4">
          {/* 새 인스턴스 토큰 생성 폼 */}
          <div className="p-4 rounded-md bg-slate-950 border border-slate-800 space-y-3">
            <h4 className="text-sm font-semibold text-slate-200">새 인스턴스 등록</h4>
            <div className="grid grid-cols-1 md:grid-cols-2 gap-3">
              <div>
                <label className="text-xs text-slate-400">인스턴스 고유 ID</label>
                <Input
                  value={newInstanceId}
                  onChange={(e) => setNewInstanceId(e.target.value)}
                  placeholder="예: inst_survival_01"
                  className="mt-1"
                />
              </div>
              <div>
                <label className="text-xs text-slate-400">인스턴스 이름</label>
                <Input
                  value={newInstanceName}
                  onChange={(e) => setNewInstanceName(e.target.value)}
                  placeholder="예: 야생 생존 1서버"
                  className="mt-1"
                />
              </div>
            </div>
            <div className="flex justify-end pt-1">
              <Button
                size="sm"
                onClick={handleIssueToken}
                disabled={loading || !newInstanceId || !newInstanceName || !isOwner}
              >
                {isOwner ? "토큰 발급 (Issue Token)" : "발급 권한 없음 (OWNER 전용)"}
              </Button>
            </div>
          </div>

          {/* 발급된 토큰 알림 (평문 1회 노출) */}
          {issuedToken && (
            <div className="p-4 rounded-md bg-emerald-950/40 border border-emerald-800 space-y-2">
              <div className="flex items-center justify-between">
                <span className="text-xs font-semibold text-emerald-300">
                  신규 발급된 보안 인증 토큰 (다시 표시되지 않으니 즉시 복사하세요)
                </span>
                <Button
                  variant="outline"
                  size="sm"
                  onClick={() => copyToClipboard(issuedToken.token)}
                  className="h-7 text-xs border-emerald-700 hover:bg-emerald-900/50"
                >
                  {copied ? <Check className="w-3.5 h-3.5 mr-1" /> : <Copy className="w-3.5 h-3.5 mr-1" />}
                  {copied ? "복사 완료" : "토큰 복사"}
                </Button>
              </div>
              <div className="p-2 rounded bg-slate-950 font-mono text-xs text-emerald-400 break-all select-all">
                {issuedToken.token}
              </div>
              <p className="text-xs text-slate-400">
                플러그인의 <code>config.yml</code>에 <code>instance-id: {issuedToken.instanceId}</code> 및 해당 토큰을 기입하세요.
              </p>
            </div>
          )}

          {/* 등록된 인스턴스 목록 */}
          <div className="space-y-2">
            <h4 className="text-xs font-semibold text-slate-400">연결된 마인크래프트 인스턴스 목록</h4>
            {instances.length === 0 ? (
              <div className="text-center p-6 border border-dashed border-slate-800 text-slate-500 text-sm rounded-md">
                등록된 인스턴스가 없습니다. 위에서 새 인스턴스를 발급하세요.
              </div>
            ) : (
              <div className="divide-y divide-slate-800 rounded-md border border-slate-800 bg-slate-950">
                {instances.map((inst) => (
                  <div key={inst.id} className="p-3 flex items-center justify-between text-sm">
                    <div className="flex items-center gap-3">
                      <Server className="w-4 h-4 text-slate-400" />
                      <div>
                        <div className="font-medium text-white">{inst.name}</div>
                        <div className="text-xs text-slate-500 font-mono">{inst.id} ({inst.instanceType})</div>
                      </div>
                    </div>
                    <div className="flex items-center gap-2">
                      <Badge variant={inst.status === "ONLINE" ? "success" : "secondary"}>
                        {inst.status}
                      </Badge>
                    </div>
                  </div>
                ))}
              </div>
            )}
          </div>
        </CardContent>
      </Card>

      {/* 플러그인 다운로드 및 퀵스타트 연동 가이드 */}
      <Card>
        <CardHeader>
          <div className="flex items-center gap-2">
            <Download className="w-5 h-5 text-emerald-400" />
            <CardTitle>Paper 1.20.4 플러그인 다운로드 & 퀵스타트</CardTitle>
          </div>
          <CardDescription>
            빌드된 Ru-Beacon Paper 전용 플러그인을 다운로드하고 서버의 <code>plugins/</code> 폴더에 배치하여 연동합니다.
          </CardDescription>
        </CardHeader>
        <CardContent className="space-y-4">
          <div className="flex flex-col sm:flex-row items-start sm:items-center justify-between gap-4 p-4 rounded-lg bg-slate-900 border border-slate-800">
            <div>
              <div className="font-semibold text-white text-sm">ru-beacon-plugin.jar (v0.1.0)</div>
              <div className="text-xs text-slate-400 mt-0.5">Paper 1.20.4 지원 / Ktor 비동기 WSS 클라이언트 / Fat JAR 번들링</div>
            </div>
            <a
              href="https://github.com/mmmphyun/ru-beacon/releases"
              target="_blank"
              rel="noreferrer"
              className="inline-flex items-center gap-1.5 px-3.5 py-2 rounded-md bg-emerald-600 hover:bg-emerald-500 text-white font-medium text-xs transition-colors"
            >
              <Download className="w-3.5 h-3.5" /> 플러그인 다운로드 (.jar)
            </a>
          </div>

          <div className="space-y-2">
            <div className="flex items-center justify-between">
              <label className="text-xs font-semibold text-slate-400">
                <code>plugins/RuBeaconPlugin/config.yml</code> 연동 설정 템플릿
              </label>
              <Button
                variant="outline"
                size="sm"
                onClick={() =>
                  copyToClipboard(
`server-url: "ws://api.127.0.0.1.nip.io/ws/minecraft/v1"
tenant-id: "${config.tenantId}"
network-id: "net_${config.tenantId}"
instance-id: "${issuedToken?.instanceId || (instances[0]?.id || 'paper_main_01')}"
instance-token: "${issuedToken?.token || '발급받은_보안_토큰'}"
ping-interval-ms: 30000
reconnect-initial-delay-ms: 1000
reconnect-max-delay-ms: 60000
enabled: true`
                  )
                }
                className="h-7 text-xs"
              >
                {copied ? <Check className="w-3.5 h-3.5 mr-1" /> : <Copy className="w-3.5 h-3.5 mr-1" />}
                설정 복사
              </Button>
            </div>
            <pre className="p-3 rounded-md bg-slate-950 font-mono text-xs text-slate-300 border border-slate-800 overflow-x-auto">
{`server-url: "ws://api.127.0.0.1.nip.io/ws/minecraft/v1"
tenant-id: "${config.tenantId}"
network-id: "net_${config.tenantId}"
instance-id: "${issuedToken?.instanceId || (instances[0]?.id || 'paper_main_01')}"
instance-token: "${issuedToken?.token || '발급받은_보안_토큰'}"
ping-interval-ms: 30000
reconnect-initial-delay-ms: 1000
reconnect-max-delay-ms: 60000
enabled: true`}
            </pre>
          </div>
        </CardContent>
      </Card>
    </div>
  );
}
