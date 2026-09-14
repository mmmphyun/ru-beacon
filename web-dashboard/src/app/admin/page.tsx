import { notFound } from "next/navigation";
import { cookies } from "next/headers";
import { AdminConsoleClient } from "./AdminConsoleClient";

interface UserProfileResponse {
  discordUserId: string;
  username: string;
  isPlatformSuperAdmin: boolean;
}

/**
 * 운영자 관제 센터 서버 컴포넌트 (/admin)
 * 일반 고객이나 비인가 접근 시 Next.js notFound()를 호출하여 경로 존재 자체를 은닉(404)함.
 */
export default async function AdminPage() {
  const API_BASE = process.env.NEXT_PUBLIC_API_URL || "http://localhost:8080";
  const cookieStore = cookies();
  const sessionCookie = cookieStore.get("RU_BEACON_SESSION")?.value;

  let isSuperAdmin = false;

  // 로컬 개발 환경(NODE_ENV !== "production")에서는 접근 허용
  if (process.env.NODE_ENV !== "production") {
    isSuperAdmin = true;
  }

  if (sessionCookie) {
    try {
      const res = await fetch(`${API_BASE}/api/v1/auth/me`, {
        headers: {
          Cookie: `RU_BEACON_SESSION=${sessionCookie}`,
        },
        cache: "no-store",
      });

      if (res.ok) {
        const data = (await res.json()) as UserProfileResponse;
        if (data.isPlatformSuperAdmin) {
          isSuperAdmin = true;
        }
      }
    } catch {
      // 백엔드 오프라인 시 개발 모드 통과 유지
    }
  }

  if (!isSuperAdmin) {
    // 404 Not Found로 완전 은닉
    notFound();
  }

  return <AdminConsoleClient />;
}
