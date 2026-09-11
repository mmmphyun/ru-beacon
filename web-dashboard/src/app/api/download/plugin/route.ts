import { NextResponse } from "next/server";

/**
 * 최신 마인크래프트 플러그인(.jar) 다운로드 API 핸들러.
 * GitHub Releases의 영구 최신 아티팩트 다운로드 URL로 302 리다이렉트하여
 * 웹 서버 대역폭 비용 0원 유지 및 자동 최신 버전 다운로드를 보장함.
 */
export async function GET() {
  const latestPluginDownloadUrl =
    "https://github.com/mmmphyun/ru-beacon/releases/latest/download/ru-beacon-plugin.jar";

  return NextResponse.redirect(latestPluginDownloadUrl, {
    status: 302,
    headers: {
      "Cache-Control": "no-cache, no-store, must-revalidate",
    },
  });
}
