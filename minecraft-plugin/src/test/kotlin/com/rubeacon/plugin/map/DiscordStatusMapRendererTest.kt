package com.rubeacon.plugin.map

import com.rubeacon.common.transport.DiscordDeliveredPayload
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import java.awt.image.BufferedImage

class DiscordStatusMapRendererTest {

    @Test
    fun `대기 상태(null 페이로드) 렌더링 시 128x128 이미지가 정상 생성된다`() {
        val renderer = DiscordStatusMapRenderer()
        val image: BufferedImage = renderer.renderCardImage(null)

        assertEquals(128, image.width)
        assertEquals(128, image.height)
        // 상단 Blurple 헤더 영역 픽셀 확인 (#5865F2: R=88, G=101, B=242)
        val headerPixel = image.getRGB(10, 5)
        val red = (headerPixel shr 16) and 0xFF
        val green = (headerPixel shr 8) and 0xFF
        val blue = headerPixel and 0xFF
        assertEquals(0x58, red)
        assertEquals(0x65, green)
        assertEquals(0xF2, blue)
    }

    @Test
    fun `디스코드 확정 페이로드 주입 시 카드 그래픽이 정상 렌더링된다`() {
        val renderer = DiscordStatusMapRenderer()
        val payload = DiscordDeliveredPayload(
            eventId = "evt_01",
            eventType = "minecraft.block.break",
            playerName = "Steve",
            messageId = "987654321012345678",
            channelName = "#인게임-실시간-로그",
            occurredAt = "2026-11-10T12:00:00Z",
            confirmedAt = "2026-11-10T12:00:00.125Z",
            rttMs = 125L,
            summary = "다이아몬드 원석 채굴"
        )

        renderer.update(payload)
        assertEquals(payload, renderer.getLatestPayload())

        val image: BufferedImage = renderer.renderCardImage(payload)
        assertEquals(128, image.width)
        assertEquals(128, image.height)
        assertNotNull(image)
    }
}
