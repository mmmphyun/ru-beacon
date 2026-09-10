package com.rubeacon.bot

/**
 * Discord 사용자 상호작용에 대한 Ephemeral(본인에게만 표시) 안내 메시지 템플릿 렌더러.
 */
object DiscordResponseRenderer {

    /**
     * 계정 연동 코드 접수 안내 메시지.
     */
    fun verificationInitiated(code: String): String =
        "🔑 **[Ru-Beacon 계정 연동]**\n인증 코드 `[ $code ]` 접수 요청을 전송했습니다.\n마인크래프트 서버에서 연동을 확인해주세요."

    /**
     * 일일 출석 요청 접수 안내 메시지.
     */
    fun attendanceRequested(userId: String): String =
        "📅 **[Ru-Beacon 일일 출석]**\n<@$userId> 님의 오늘자 출석 요청이 접수되었습니다.\n잠시 후 보상 지급 여부가 처리됩니다."

    /**
     * 보상 수령 요청 접수 안내 메시지.
     */
    fun rewardClaimRequested(userId: String, rewardId: String? = null): String {
        val rewardText = if (!rewardId.isNullOrBlank()) "(`$rewardId`) " else ""
        return "🎁 **[Ru-Beacon 보상 수령]**\n<@$userId> 님의 보상${rewardText}수령 요청이 서버로 전송되었습니다.\n게임 내 인벤토리를 확인해주세요!"
    }

    /**
     * 알 수 없는 컴포넌트 인터랙션 접수 안내 메시지.
     */
    fun interactionAcknowledged(customId: String): String =
        "✅ 요청(`$customId`)이 정상 접수되어 워크플로우 엔진으로 전달되었습니다."

    /**
     * 오류 발생 안내 메시지.
     */
    fun error(reason: String): String =
        "⚠️ **[Ru-Beacon 오류]**\n요청을 처리하는 도중 오류가 발생했습니다: $reason"
}
