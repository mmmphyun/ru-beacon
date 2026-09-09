package com.rubeacon.bot

import org.junit.jupiter.api.Test
import kotlin.test.assertTrue

class DiscordResponseRendererTest {

    @Test
    fun `verificationInitiated should render code correctly`() {
        val code = "789123"
        val message = DiscordResponseRenderer.verificationInitiated(code)

        assertTrue(message.contains(code))
        assertTrue(message.contains("Ru-Beacon 계정 연동"))
    }

    @Test
    fun `attendanceRequested should include user mention`() {
        val userId = "123456789012345678"
        val message = DiscordResponseRenderer.attendanceRequested(userId)

        assertTrue(message.contains("<@$userId>"))
        assertTrue(message.contains("일일 출석"))
    }

    @Test
    fun `rewardClaimRequested should include user mention and rewardId`() {
        val userId = "123456789012345678"
        val rewardId = "daily_gem_10"
        val message = DiscordResponseRenderer.rewardClaimRequested(userId, rewardId)

        assertTrue(message.contains("<@$userId>"))
        assertTrue(message.contains(rewardId))
    }

    @Test
    fun `error message should render reason`() {
        val reason = "Network timeout"
        val message = DiscordResponseRenderer.error(reason)

        assertTrue(message.contains(reason))
        assertTrue(message.contains("오류"))
    }
}
