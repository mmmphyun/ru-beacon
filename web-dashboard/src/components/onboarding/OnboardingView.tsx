"use client";

import React, { useState, useEffect, useRef } from "react";
import { TenantConfig, InstanceItem } from "@/types/workflow";
import { saveOnboarding, issueInstanceToken, fetchTenantDetail, fetchUserProfile, IS_DEV_MOCK } from "@/lib/api";
import { Card, CardHeader, CardTitle, CardDescription, CardContent, CardFooter } from "@/components/ui/card";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Badge } from "@/components/ui/badge";
import {
  Bot,
  Key,
  Radio,
  Copy,
  Check,
  ExternalLink,
  ArrowRight,
  ChevronLeft,
  Activity,
  Server,
  Zap,
  HelpCircle,
} from "lucide-react";

interface TenantConfigWithAdmin extends TenantConfig {
  adminRoleId?: string;
}

interface OnboardingViewProps {
  tenantId: string;
  isOwner?: boolean;
  ownedGuilds?: { id: string; name: string }[];
  onComplete?: () => void;
}

export function OnboardingView({ tenantId, isOwner = true, ownedGuilds, onComplete }: OnboardingViewProps) {
  const [currentStep, setCurrentStep] = useState<1 | 2 | 3>(1);
  const [guildsList, setGuildsList] = useState<{ id: string; name: string }[]>(ownedGuilds || []);

  const [config, setConfig] = useState<TenantConfigWithAdmin>({
    tenantId,
    name: "Ru-Beacon 공식 마인크래프트 서버",
    discordGuildId: "",
    adminRoleId: "",
    policyMode: "MONITOR",
    authChannelId: "112233445566778899",
  });

  const [newInstanceId, setNewInstanceId] = useState("survival-node-01");
  const [newInstanceName, setNewInstanceName] = useState("야생 1번 서버");
  const [issuedToken, setIssuedToken] = useState<{ instanceId: string; token: string } | null>(null);
  const [copied, setCopied] = useState(false);
  const [loading, setLoading] = useState(false);

  // Step 3 핑 감지 상태
  const [isOnline, setIsOnline] = useState(false);
  const [lastPingTime, setLastPingTime] = useState<string | null>(null);
  const pollingRef = useRef<NodeJS.Timeout | null>(null);

  const discordClientId = process.env.NEXT_PUBLIC_DISCORD_CLIENT_ID || "";
  const discordInviteUrl = discordClientId
    ? `https://discord.com/oauth2/authorize?client_id=${discordClientId}&permissions=8&scope=bot%20applications.commands`
    : "#";

  useEffect(() => {
    if (ownedGuilds && ownedGuilds.length > 0) {
      setGuildsList(ownedGuilds);
    } else {
      fetchUserProfile().then((u) => {
        if (u?.ownedGuilds && u.ownedGuilds.length > 0) {
          setGuildsList(u.ownedGuilds);
        }
      });
    }
  }, [ownedGuilds]);

  useEffect(() => {
    fetchTenantDetail(tenantId).then((res) => {
      if (res) {
        setConfig((prev) => ({
          ...prev,
          tenantId: res.tenantId,
          name: res.name || prev.name,
          discordGuildId: res.discordGuildId || prev.discordGuildId,
          policyMode: res.policyMode || prev.policyMode,
          authChannelId: res.authChannelId || prev.authChannelId,
        }));
        if (res.instances && res.instances.length > 0) {
          const firstInst = res.instances[0];
          setNewInstanceId(firstInst.id);
          setNewInstanceName(firstInst.name);
          if (firstInst.status === "ONLINE") {
            setIsOnline(true);
          }
        }
      }
    });
  }, [tenantId]);

  // Step 3 진입 시 2초 간격 폴링
  useEffect(() => {
    if (currentStep === 3 && !isOnline) {
      pollingRef.current = setInterval(async () => {
        try {
          const detail = await fetchTenantDetail(tenantId);
          if (detail && detail.instances && detail.instances.length > 0) {
            const onlineInst = detail.instances.find((i) => i.status === "ONLINE");
            if (onlineInst) {
              setIsOnline(true);
              setLastPingTime(new Date().toLocaleTimeString());
              if (pollingRef.current) clearInterval(pollingRef.current);
            }
          }
        } catch {
          // ignore polling error
        }
      }, 2000);
    }

    return () => {
      if (pollingRef.current) clearInterval(pollingRef.current);
    };
  }, [currentStep, isOnline, tenantId]);

  const handleStep1Submit = async () => {
    if (!config.discordGuildId.trim() || !config.name.trim()) return;
    setLoading(true);
    try {
      await saveOnboarding(config);
      setCurrentStep(2);
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
    } finally {
      setLoading(false);
    }
  };

  const generatedYaml = `# Ru-Beacon Plugin Configuration
server:
  gateway_url: "wss://api.rubeacon.com/ws/minecraft/v1"
tenant:
  id: "${config.tenantId}"
instance:
  id: "${issuedToken?.instanceId || newInstanceId}"
  token: "${issuedToken?.token || "발급_후_자동_생성됩니다"}"
ring_buffer_size: 4096
`;

  const copyToClipboard = (text: string) => {
    navigator.clipboard.writeText(text);
    setCopied(true);
    setTimeout(() => setCopied(false), 2000);
  };

  // 모의 테스트용 핑 시뮬레이션
  const simulatePing = () => {
    setIsOnline(true);
    setLastPingTime(new Date().toLocaleTimeString());
  };

  return (
    <div className="max-w-4xl mx-auto space-y-6">
      {/* 2.1 상단 안내 헤더 */}
      <div className="border-b border-border pb-4 space-y-1">
        <h2 className="text-xl font-bold tracking-tight text-foreground">
          Ru-Beacon 서버 관리 콘솔 온보딩
        </h2>
        <p className="text-xs text-muted-foreground">
          마인크래프트 서버와 디스코드 길드를 연결하는 3단계 설정입니다.
        </p>
      </div>

      {/* 3단계 스텝 인디케이터 */}
      <div className="grid grid-cols-3 gap-2">
        <div
          className={`p-3 rounded border text-xs transition-colors ${
            currentStep === 1
              ? "border-primary bg-primary/10 text-foreground"
              : currentStep > 1
              ? "border-border bg-card text-muted-foreground"
              : "border-border/60 bg-surface text-muted-foreground/60"
          }`}
        >
          <div className="flex items-center gap-2 font-mono font-semibold">
            <Bot className="w-3.5 h-3.5 text-primary" />
            <span>Step 1. 디스코드 서버 연결</span>
          </div>
        </div>

        <div
          className={`p-3 rounded border text-xs transition-colors ${
            currentStep === 2
              ? "border-primary bg-primary/10 text-foreground"
              : currentStep > 2
              ? "border-border bg-card text-muted-foreground"
              : "border-border/60 bg-surface text-muted-foreground/60"
          }`}
        >
          <div className="flex items-center gap-2 font-mono font-semibold">
            <Key className="w-3.5 h-3.5 text-primary" />
            <span>Step 2. 인스턴스 토큰 발급</span>
          </div>
        </div>

        <div
          className={`p-3 rounded border text-xs transition-colors ${
            currentStep === 3
              ? "border-primary bg-primary/10 text-foreground"
              : "border-border/60 bg-surface text-muted-foreground/60"
          }`}
        >
          <div className="flex items-center gap-2 font-mono font-semibold">
            <Radio className="w-3.5 h-3.5 text-primary" />
            <span>Step 3. 연결 상태 실시간 확인</span>
          </div>
        </div>
      </div>

      {/* 2.2 Step 1: 디스코드 서버 연결 */}
      {currentStep === 1 && (
        <Card className="border-border bg-card">
          <CardHeader>
            <div className="flex items-center gap-2">
              <Bot className="w-4 h-4 text-primary" />
              <CardTitle>Step 1. 디스코드 서버 연결</CardTitle>
            </div>
            <CardDescription>
              알림을 수신하고 권한을 동기화할 디스코드 서버를 등록합니다.
            </CardDescription>
          </CardHeader>
          <CardContent className="space-y-4">
            <div className="p-3.5 rounded border border-border bg-surface flex flex-col sm:flex-row sm:items-center justify-between gap-3 text-xs">
              <div className="space-y-0.5">
                <span className="font-semibold text-foreground">디스코드 봇 초대</span>
                <p className="text-muted-foreground text-[11px]">
                  알림 수신 및 채널 제어를 위해 전용 봇을 먼저 서버에 초대하십시오.
                </p>
              </div>
              <a
                href={discordInviteUrl}
                target="_blank"
                rel="noreferrer"
                className="inline-flex items-center justify-center gap-1.5 px-3 py-1.5 rounded border border-primary/40 bg-primary/10 text-primary hover:bg-primary/20 text-xs font-medium transition-colors shrink-0"
              >
                <ExternalLink className="w-3.5 h-3.5" />
                디스코드 봇 내 서버로 초대하기
              </a>
            </div>

            {guildsList.length > 0 && (
              <div className="space-y-1.5 p-3 rounded border border-border/80 bg-surface/50">
                <label className="text-xs font-semibold text-foreground flex items-center justify-between">
                  <span>소유한 Discord 서버 빠른 선택</span>
                  <span className="text-[10px] text-muted-foreground font-normal">자동 완성</span>
                </label>
                <select
                  aria-label="소유한 Discord 서버 선택"
                  className="w-full text-xs rounded border border-input bg-background px-3 py-2 text-foreground focus:outline-none focus:ring-1 focus:ring-primary h-9"
                  defaultValue=""
                  onChange={(e) => {
                    const selected = guildsList.find((g) => g.id === e.target.value);
                    if (selected) {
                      setConfig((prev) => ({
                        ...prev,
                        discordGuildId: selected.id,
                        name: selected.name,
                        tenantId: `tenant_${selected.id.slice(-6)}`,
                      }));
                    }
                  }}
                >
                  <option value="" disabled>
                    연동할 Discord 서버를 선택하세요
                  </option>
                  {guildsList.map((guild) => (
                    <option key={guild.id} value={guild.id}>
                      {guild.name} ({guild.id})
                    </option>
                  ))}
                </select>
              </div>
            )}

            <div className="grid grid-cols-1 md:grid-cols-2 gap-4 pt-1">
              <div className="space-y-1.5">
                <label className="text-xs font-semibold text-foreground">서버 고유 식별자(길드 ID)</label>
                <Input
                  value={config.discordGuildId}
                  onChange={(e) => setConfig({ ...config, discordGuildId: e.target.value })}
                  placeholder="길드 ID 입력 (예: 1234567890)"
                />
              </div>
              <div className="space-y-1.5">
                <label className="text-xs font-semibold text-foreground">서버 표시 이름</label>
                <Input
                  value={config.name}
                  onChange={(e) => setConfig({ ...config, name: e.target.value })}
                  placeholder="Ru-Beacon 공식 마인크래프트 서버"
                />
              </div>
            </div>

            <div className="grid grid-cols-1 md:grid-cols-2 gap-4 pt-1">
              <div className="space-y-1.5">
                <label className="text-xs font-semibold text-foreground">관리자 역할 ID (adminRoleId)</label>
                <Input
                  value={config.adminRoleId || ""}
                  onChange={(e) => setConfig({ ...config, adminRoleId: e.target.value })}
                  placeholder="Discord 관리자 역할 ID (선택)"
                />
              </div>
              <div className="space-y-1.5">
                <label className="text-xs font-semibold text-foreground">2FA 보안 정책 모드</label>
                <select
                  aria-label="2FA 보안 정책 모드"
                  value={config.policyMode}
                  onChange={(e) =>
                    setConfig({
                      ...config,
                      policyMode: e.target.value as "MONITOR" | "ENFORCE",
                    })
                  }
                  className="w-full text-xs rounded border border-input bg-background px-3 py-2 text-foreground focus:outline-none focus:ring-1 focus:ring-primary h-9"
                >
                  <option value="MONITOR">MONITOR (감시 및 알림 전용)</option>
                  <option value="ENFORCE">ENFORCE (2FA 미인증 시 차단)</option>
                </select>
              </div>
            </div>
          </CardContent>
          <CardFooter className="flex justify-end border-t border-border pt-4">
            <Button
              size="sm"
              onClick={handleStep1Submit}
              disabled={loading || !config.discordGuildId || !config.name || !isOwner}
              className="h-8 text-xs bg-primary hover:bg-primary/90 text-primary-foreground font-semibold gap-1.5"
            >
              다음: 인스턴스 토큰 발급
              <ArrowRight className="w-3.5 h-3.5" />
            </Button>
          </CardFooter>
        </Card>
      )}

      {/* 2.3 Step 2: 인스턴스 토큰 발급 및 플러그인 설정 */}
      {currentStep === 2 && (
        <Card className="border-border bg-card">
          <CardHeader>
            <div className="flex items-center gap-2">
              <Key className="w-4 h-4 text-primary" />
              <CardTitle>Step 2. 인스턴스 토큰 발급</CardTitle>
            </div>
            <CardDescription>
              마인크래프트 서버와 게이트웨이를 안전하게 인증하기 위한 전용 토큰을 생성합니다.
            </CardDescription>
          </CardHeader>
          <CardContent className="space-y-4">
            <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
              <div className="space-y-1.5">
                <label className="text-xs font-semibold text-foreground">인스턴스 식별자</label>
                <Input
                  value={newInstanceId}
                  onChange={(e) => setNewInstanceId(e.target.value)}
                  placeholder="survival-node-01"
                />
              </div>
              <div className="space-y-1.5">
                <label className="text-xs font-semibold text-foreground">인스턴스 이름</label>
                <Input
                  value={newInstanceName}
                  onChange={(e) => setNewInstanceName(e.target.value)}
                  placeholder="야생 1번 서버"
                />
              </div>
            </div>

            <div className="pt-1">
              <Button
                size="sm"
                onClick={handleIssueToken}
                disabled={loading || !newInstanceId || !newInstanceName || !isOwner}
                className="h-8 text-xs bg-primary hover:bg-primary/90 text-primary-foreground font-semibold gap-1.5"
              >
                <Zap className="w-3.5 h-3.5" />
                토큰 발급 및 config.yml 생성
              </Button>
            </div>

            {/* 복사 가이드 패널 */}
            <div className="rounded border border-border bg-surface overflow-hidden space-y-0">
              <div className="flex items-center justify-between px-3.5 py-2 border-b border-border bg-card text-xs">
                <span className="font-mono text-[11px] text-muted-foreground">
                  plugins/RuBeacon/config.yml 파일에 아래 내용을 붙여넣으십시오.
                </span>
                <Button
                  variant="outline"
                  size="sm"
                  onClick={() => copyToClipboard(generatedYaml)}
                  className="h-6 text-[11px] border-border hover:bg-muted gap-1 px-2 font-mono"
                >
                  {copied ? <Check className="w-3 h-3 text-emerald-400" /> : <Copy className="w-3 h-3" />}
                  {copied ? "설정 내용이 클립보드에 복사되었습니다." : "설정 내용 전체 복사"}
                </Button>
              </div>
              <pre className="p-3.5 font-mono text-xs text-foreground bg-background/80 overflow-x-auto leading-relaxed">
                {generatedYaml}
              </pre>
            </div>
          </CardContent>
          <CardFooter className="flex justify-between border-t border-border pt-4">
            <Button
              variant="outline"
              size="sm"
              onClick={() => setCurrentStep(1)}
              className="h-8 text-xs border-border hover:bg-card gap-1 font-mono"
            >
              <ChevronLeft className="w-3.5 h-3.5" />
              이전 단계
            </Button>
            <Button
              size="sm"
              onClick={() => setCurrentStep(3)}
              className="h-8 text-xs bg-primary hover:bg-primary/90 text-primary-foreground font-semibold gap-1.5"
            >
              다음: 연결 상태 확인
              <ArrowRight className="w-3.5 h-3.5" />
            </Button>
          </CardFooter>
        </Card>
      )}

      {/* 2.4 Step 3: 연결 상태 실시간 핑 확인 */}
      {currentStep === 3 && (
        <Card className="border-border bg-card">
          <CardHeader>
            <div className="flex items-center gap-2">
              <Radio className="w-4 h-4 text-primary" />
              <CardTitle>Step 3. 연결 상태 실시간 확인</CardTitle>
            </div>
            <CardDescription>
              마인크래프트 서버 구동 후 발생하는 첫 번째 웹소켓 신호를 대기합니다.
            </CardDescription>
          </CardHeader>
          <CardContent className="space-y-6">
            {/* 레이더 관제 화면 */}
            <div className="p-8 rounded border border-border bg-surface text-center space-y-4 relative overflow-hidden">
              <div className="relative w-24 h-24 mx-auto flex items-center justify-center">
                <div
                  className={`absolute inset-0 rounded-full border border-primary/20 ${
                    !isOnline ? "animate-ping opacity-40" : ""
                  }`}
                />
                <div className="w-16 h-16 rounded-full border border-primary/40 flex items-center justify-center bg-card">
                  <div
                    className={`w-4 h-4 rounded-full ${
                      isOnline ? "bg-emerald-400" : "bg-primary animate-pulse"
                    }`}
                  />
                </div>
              </div>

              {!isOnline ? (
                <div className="space-y-3">
                  <div className="space-y-1">
                    <div className="font-semibold text-sm text-foreground">하트비트 신호 대기 중</div>
                    <p className="text-xs text-muted-foreground max-w-md mx-auto">
                      마인크래프트 서버에서 보낸 첫 번째 연결 신호를 기다리고 있습니다.
                    </p>
                  </div>

                  <div className="p-3.5 rounded border border-border/60 bg-card/60 text-left text-xs space-y-1.5 max-w-md mx-auto">
                    <div className="font-semibold text-foreground flex items-center gap-1.5 text-xs">
                      <HelpCircle className="w-3.5 h-3.5 text-primary shrink-0" />
                      연결 신호가 감지되지 않나요?
                    </div>
                    <ul className="list-disc list-inside text-muted-foreground space-y-1 text-[11px] leading-relaxed">
                      <li>서버의 <code className="text-foreground font-mono font-semibold">plugins/</code> 폴더에 플러그인 JAR 파일이 있는지 확인하세요.</li>
                      <li><code className="text-foreground font-mono font-semibold">plugins/RuBeacon/config.yml</code>에 발급받은 토큰이 올바르게 적혔는지 확인하세요.</li>
                      <li>마인크래프트 서버 콘솔에서 <code className="text-foreground font-mono font-semibold">/rubeacon reload</code>를 실행하거나 서버를 재시작하세요.</li>
                    </ul>
                  </div>

                  {IS_DEV_MOCK && (
                    <div className="pt-1">
                      <Button
                        variant="outline"
                        size="sm"
                        onClick={simulatePing}
                        className="h-7 text-[11px] border-border hover:bg-card text-muted-foreground font-mono"
                      >
                        테스트 핑 수신 시뮬레이션
                      </Button>
                    </div>
                  )}
                </div>
              ) : (
                <div className="space-y-2">
                  <div className="inline-flex items-center gap-1.5 px-3 py-1 rounded bg-emerald-500/10 border border-emerald-500/20 text-emerald-400 text-xs font-semibold font-mono">
                    <Check className="w-3.5 h-3.5" />
                    서버 연결 완료
                  </div>
                  <div className="font-mono text-xs text-muted-foreground">
                    상태: ONLINE | 지연 시간: 1ms 미만 | 프로토콜: WSS v1
                    {lastPingTime && ` | 최근 신호: ${lastPingTime}`}
                  </div>
                </div>
              )}
            </div>
          </CardContent>
          <CardFooter className="flex justify-between border-t border-border pt-4">
            <Button
              variant="outline"
              size="sm"
              onClick={() => setCurrentStep(2)}
              className="h-8 text-xs border-border hover:bg-card gap-1 font-mono"
            >
              <ChevronLeft className="w-3.5 h-3.5" />
              이전 단계
            </Button>
            {isOnline ? (
              <Button
                size="sm"
                onClick={onComplete}
                className="h-8 text-xs bg-primary hover:bg-primary/90 text-primary-foreground font-semibold gap-1.5"
              >
                대시보드로 이동하기
                <ArrowRight className="w-3.5 h-3.5" />
              </Button>
            ) : (
              <span className="text-xs font-mono text-muted-foreground">신호 수신 대기 중...</span>
            )}
          </CardFooter>
        </Card>
      )}
    </div>
  );
}
