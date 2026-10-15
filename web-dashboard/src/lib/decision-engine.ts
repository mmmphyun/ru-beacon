export interface TriggerMetadata {
  type: string;
  label: string;
  description: string;
  keywords: string[];
}

export interface ActionMetadata {
  type: string;
  label: string;
  description: string;
  keywords: string[];
}

export const TRIGGER_REGISTRY: TriggerMetadata[] = [
  {
    type: "MINECRAFT_LEVEL_UP",
    label: "레벨 달성",
    description: "플레이어가 특정 경험치 레벨에 도달했을 때",
    keywords: ["레벨", "달성", "도달", "level", "lv", "성장", "전직"],
  },
  {
    type: "MINECRAFT_BLOCK_BREAK",
    label: "블록 채굴",
    description: "플레이어가 특정 광석이나 블록을 파괴했을 때",
    keywords: ["채굴", "블록", "파괴", "다이아", "원석", "캐면", "부수면", "mine"],
  },
  {
    type: "MINECRAFT_PLAYER_DEATH",
    label: "플레이어 사망",
    description: "플레이어가 몬스터나 환경 요인으로 사망했을 때",
    keywords: ["사망", "죽음", "죽으면", "전사", "낙사", "death"],
  },
  {
    type: "MINECRAFT_MONSTER_KILL",
    label: "몬스터 처치",
    description: "플레이어가 보스나 특정 몬스터를 토벌했을 때",
    keywords: ["처치", "토벌", "잡으면", "보스", "드래곤", "킬", "kill"],
  },
  {
    type: "DISCORD_BUTTON_CLICK",
    label: "디스코드 버튼 클릭",
    description: "디스코드 임베드 내 인터랙션 버튼을 클릭했을 때",
    keywords: ["버튼", "출석", "출석체크", "클릭", "상호작용", "button"],
  },
];

export const ACTION_REGISTRY: ActionMetadata[] = [
  {
    type: "DISCORD_SEND_MESSAGE",
    label: "디스코드 임베드 전송",
    description: "디스코드 채널로 서식화된 메시지나 박스형 임베드를 전송합니다.",
    keywords: ["디스코드", "메시지", "임베드", "박스", "알림", "공지", "전송", "보내"],
  },
  {
    type: "MINECRAFT_DISPATCH_COMMAND",
    label: "마인크래프트 명령어 실행",
    description: "마인크래프트 서버 콘솔로 인게임 커맨드를 디스패치합니다.",
    keywords: ["명령어", "커맨드", "실행", "지급", "give", "whitelist", "kick"],
  },
  {
    type: "ATTENDANCE_RESERVATION",
    label: "아이템 지급 및 선착순 예약",
    description: "2-Tier 링버퍼와 캐시를 통해 한정 보상을 예약 지급합니다.",
    keywords: ["선착순", "보상", "아이템", "다이아", "정원", "한정"],
  },
];

export interface DecisionEngineResult {
  triggerType: string;
  triggerConfig: Record<string, unknown>;
  actionType: string;
  actionConfig: Record<string, unknown>;
  confidence: number; // 0.0 ~ 1.0 (보정된 확신도)
  reasoning: string;
  supported: boolean;
  suggestedTrigger?: string;
  workflowName?: string;
  description?: string;
}

export interface DecisionEngineAdapter {
  decide(prompt: string): Promise<DecisionEngineResult>;
}

/**
 * Gemini 3.8 Flash 기반 결정 엔진 어댑터
 * API Key 미제공 환경에서는 내장된 시맨틱 휴리스틱 추론 엔진으로 폴백함.
 */
export class GeminiDecisionAdapter implements DecisionEngineAdapter {
  private apiKey: string | null;

  constructor(apiKey?: string) {
    this.apiKey = apiKey || process.env.GEMINI_API_KEY || null;
  }

  async decide(prompt: string): Promise<DecisionEngineResult> {
    const trimmed = prompt.trim();
    if (!trimmed) {
      return {
        triggerType: "",
        triggerConfig: {},
        actionType: "",
        actionConfig: {},
        confidence: 0.0,
        reasoning: "입력된 프롬프트가 없습니다.",
        supported: false,
      };
    }

    // API Key가 유효할 경우 @google/genai SDK 호출 시도
    if (this.apiKey) {
      try {
        const { GoogleGenAI } = await import("@google/genai");
        const ai = new GoogleGenAI({ apiKey: this.apiKey });
        const systemPrompt = `You are the Ru-Beacon Workflow Decision Engine.
Available Triggers: ${TRIGGER_REGISTRY.map((t) => t.type).join(", ")}.
Available Actions: ${ACTION_REGISTRY.map((a) => a.type).join(", ")}.
Determine triggerType, actionType, confidence (0.0 to 1.0), and reasoning.
Respond in valid JSON:
{"triggerType":"...","actionType":"...","confidence":0.95,"reasoning":"...","supported":true}`;

        const response = await ai.models.generateContent({
          model: "gemini-3.8-flash",
          contents: prompt,
          config: {
            systemInstruction: systemPrompt,
            responseMimeType: "application/json",
          },
        });

        const text = response.text;
        if (text) {
          const parsed = JSON.parse(text);
          return {
            triggerType: parsed.triggerType || "MINECRAFT_LEVEL_UP",
            triggerConfig: parsed.triggerConfig || {},
            actionType: parsed.actionType || "DISCORD_SEND_MESSAGE",
            actionConfig: parsed.actionConfig || {},
            confidence: typeof parsed.confidence === "number" ? parsed.confidence : 0.8,
            reasoning: parsed.reasoning || "Gemini 3.8 Flash가 자연어 의도를 매핑했습니다.",
            supported: parsed.confidence > 0.0,
            workflowName: parsed.workflowName || "자동 생성 워크플로우",
            description: parsed.description || trimmed,
          };
        }
      } catch {
        // Fallback to deterministic heuristic below
      }
    }

    // 시맨틱 결정 엔진 휴리스틱 추론
    return this.evaluateHeuristic(trimmed);
  }

  evaluateHeuristic(input: string): DecisionEngineResult {
    const normalized = input.toLowerCase();

    // 1. 도메인 무관 요청 조기 필터링 (confidence = 0.0)
    const domainKeywords = ["레벨", "채굴", "죽", "몬스터", "보스", "디스코드", "마인크래프트", "서버", "명령", "버튼", "출석", "다이아", "원석"];
    const hasDomainKeyword = domainKeywords.some((kw) => normalized.includes(kw));

    if (!hasDomainKeyword) {
      return {
        triggerType: "",
        triggerConfig: {},
        actionType: "",
        actionConfig: {},
        confidence: 0.0,
        reasoning: "입력하신 내용 중 지원하지 않는 조건 또는 동작이 포함되어 있습니다. Ru-Beacon은 마인크래프트-디스코드 연동만 지원합니다.",
        supported: false,
      };
    }

    // 2. 모호성 매칭 (0.0 < confidence < 0.7)
    const isAmbiguous = (normalized.includes("레벨") || normalized.includes("성장")) &&
      !normalized.includes("디스코드") && !normalized.includes("명령") && !normalized.includes("메시지");

    if (isAmbiguous) {
      return {
        triggerType: "MINECRAFT_LEVEL_UP",
        triggerConfig: { level: 30 },
        actionType: "DISCORD_SEND_MESSAGE",
        actionConfig: { channelId: "1234567890" },
        confidence: 0.5,
        reasoning: "지시하신 조건이 [레벨 달성] 조건과 유사합니다. 이 조건으로 적용하시겠습니까?",
        supported: true,
        suggestedTrigger: "MINECRAFT_LEVEL_UP",
        workflowName: "레벨 달성 알림",
        description: input,
      };
    }

    // 3. 확신도 높은 매칭 (confidence >= 0.7)
    let matchedTrigger = "MINECRAFT_LEVEL_UP";
    let matchedAction = "DISCORD_SEND_MESSAGE";

    if (normalized.includes("채굴") || normalized.includes("원석") || normalized.includes("다이아")) {
      matchedTrigger = "MINECRAFT_BLOCK_BREAK";
    } else if (normalized.includes("사망") || normalized.includes("죽")) {
      matchedTrigger = "MINECRAFT_PLAYER_DEATH";
    } else if (normalized.includes("보스") || normalized.includes("몬스터") || normalized.includes("처치")) {
      matchedTrigger = "MINECRAFT_MONSTER_KILL";
    } else if (normalized.includes("버튼") || normalized.includes("출석")) {
      matchedTrigger = "DISCORD_BUTTON_CLICK";
    }

    if (normalized.includes("명령") || normalized.includes("커맨드") || normalized.includes("give")) {
      matchedAction = "MINECRAFT_DISPATCH_COMMAND";
    } else if (normalized.includes("선착순") || normalized.includes("보상")) {
      matchedAction = "ATTENDANCE_RESERVATION";
    }

    return {
      triggerType: matchedTrigger,
      triggerConfig: matchedTrigger === "MINECRAFT_LEVEL_UP" ? { level: 30 } : {},
      actionType: matchedAction,
      actionConfig: {
        channelId: "1234567890",
        message: "{User_Nickname}님이 조건을 달성했습니다.",
      },
      confidence: 0.95,
      reasoning: "자연어 지시문으로부터 트리거 및 디스코드 액션을 성공적으로 매핑했습니다.",
      supported: true,
      workflowName: `${TRIGGER_REGISTRY.find((t) => t.type === matchedTrigger)?.label} 자동화`,
      description: input,
    };
  }
}
