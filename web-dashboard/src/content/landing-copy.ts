/**
 * 랜딩 페이지 한국어(KO) 공식 카피라이팅 SSOT 문서
 * 텍스트와 엔지니어링 코드(3D 기하학, SVG, 인터랙션 애니메이션)를 분리하여 단독 윤문 및 향후 다국어 확장을 지원합니다.
 */
export const LANDING_COPY_KO = {
  header: {
    nav: [
      { label: "작동 방식", href: "#pipeline" },
      { label: "핵심 기술", href: "#architecture" },
      { label: "성능 비교", href: "#comparison" },
      { label: "시작하기", href: "#setup" },
    ],
    ctaConsole: "대시보드 콘솔",
    ctaStart: "대시보드 시작하기",
  },
  beaconStage: {
    badgeLeft: "0.00ms Tick Occupancy",
    badgeRight: "LMAX Disruptor Core",
    subSlogan: "마인크래프트-디스코드 실시간 연동 서비스",
    scrollGuide: "스크롤하여 아키텍처 탐색",
  },
  hero: {
    category: "초저지연 마인크래프트-디스코드 브릿지",
    headline: "서버 TPS 저하 없는 마인크래프트-디스코드 실시간 연동",
    description:
      "서버에 이벤트가 몰려도 렉이 걸리지 않습니다. 게임 속 사건을 디스코드 알림과 역할에 곧바로 반영합니다.",
    ctaPrimary: "Ru-Beacon 콘솔 시작하기",
    ctaSecondary: "플러그인 다운로드",
    metrics: [
      { value: "0.00ms", label: "메인 틱 점유" },
      { value: "0.08ms", label: "링버퍼 큐잉" },
      { value: "40ms", label: "디스코드 도달" },
    ],
    waveLegends: {
      tpsStable: "20.0 TPS 수평 유지",
      eventSurge: "인게임 이벤트 폭증 파형",
    },
  },
  pipeline: {
    tag: "Signal Trajectory",
    title: "이벤트가 처리되는 실제 런타임 경로",
    steps: [
      {
        step: "01",
        metric: "0.00ms (완전 비동기)",
        title: "마인크래프트 서버",
        desc: "메인 틱 중단 없이 사건 즉시 분리",
      },
      {
        step: "02",
        metric: "0.08ms Queue",
        title: "LMAX Disruptor",
        desc: "수만 건의 이벤트를 무잠금 정렬",
      },
      {
        step: "03",
        metric: "Single-Hop",
        title: "WSS 게이트웨이 & Redis",
        desc: "상시 열린 웹소켓 통로로 직결 전송",
      },
      {
        step: "04",
        metric: "42ms Ack",
        title: "디스코드 봇 서비스",
        desc: "채널 알림 및 유저 역할 즉시 반영",
      },
    ],
  },
  architecture: {
    tag: "Core Engine",
    title: "초저지연 비동기 인프라 명세",
    items: [
      {
        tag: "01 / Direct Socket",
        title: "WSS 단일 계층 게이트웨이",
        desc: "중계 서버 없이 플러그인과 관제 서버가 직접 연결됩니다.",
      },
      {
        tag: "02 / Lock-Free Ring",
        title: "LMAX Disruptor 인메모리 링버퍼",
        desc: "대규모 전투·채굴 이벤트도 대기 시간 없이 메모리에서 즉시 소화합니다.",
      },
      {
        tag: "03 / Instant Role Sync",
        title: "디스코드 권한 실시간 동기화",
        desc: "인게임 달성 조건을 감지하는 즉시 디스코드 역할과 칭호를 바꿉니다.",
      },
    ],
  },
  comparison: {
    tag: "Benchmark Contrast",
    title: "기존 연동 방식과의 기술적 격차",
    subtitle: "마인크래프트 메인 틱 점유 시간",
    rubeaconSpeed: "0.00ms (완전 비동기)",
    legacyTitle: "기존 웹훅 플러그인",
    legacySpeed: "15 - 50ms (동기 블로킹 렉 유발)",
    setupGuideTag: "빠른 3단계 연동 안내",
    setupSteps: [
      { num: "01", title: "디스코드 봇 초대", desc: "전용 봇을 서버에 추가" },
      { num: "02", title: "인스턴스 토큰 발급", desc: "플러그인 설정에 토큰 등록" },
      { num: "03", title: "실시간 하트비트 확인", desc: "서버 가동 즉시 온라인 연결" },
    ],
    ctaHeadline: "서버 렉 없는 디스코드 커뮤니티, 지금 즉시 연결하세요.",
    ctaPluginDownload: "플러그인 JAR 다운로드",
    ctaStart: "지금 무료로 시작하기",
  },
} as const;

export type LandingCopy = typeof LANDING_COPY_KO;
