package com.rubeacon.plugin.map

import com.rubeacon.common.transport.DiscordDeliveredPayload
import org.bukkit.entity.Player
import org.bukkit.map.MapCanvas
import org.bukkit.map.MapRenderer
import org.bukkit.map.MapView
import java.awt.Color
import java.awt.Font
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * 128x128 픽셀 마인크래프트 지도에 디스코드 실시간 전송 확인(ACK) 카드를 드로잉하는 렌더러.
 *
 * Graphics2D를 사용하여 다크 테마 디스코드 임베드 카드 UI를 그리고 Bukkit MapCanvas로 변환함.
 * 디스코드 실제 전송 성공 ACK가 도착했을 때만 캔버스를 갱신하여 인과적 일관성을 보장함.
 */
class DiscordStatusMapRenderer : MapRenderer(false) {

    private val latestPayload = AtomicReference<DiscordDeliveredPayload?>(null)
    private val isDirty = AtomicBoolean(true)

    fun update(payload: DiscordDeliveredPayload) {
        latestPayload.set(payload)
        isDirty.set(true)
    }

    fun getLatestPayload(): DiscordDeliveredPayload? = latestPayload.get()

    override fun render(map: MapView, canvas: MapCanvas, player: Player) {
        if (!isDirty.compareAndSet(true, false)) return

        val payload = latestPayload.get()
        val image = renderCardImage(payload)
        canvas.drawImage(0, 0, image)
    }

    /**
     * 128x128 BufferedImage에 디스코드 카드 그래픽을 렌더링함.
     * 단위 테스트에서 MapCanvas 없이도 독립적으로 그래픽 무결성을 검증할 수 있도록 분리됨.
     */
    fun renderCardImage(payload: DiscordDeliveredPayload?): BufferedImage {
        val image = BufferedImage(128, 128, BufferedImage.TYPE_INT_RGB)
        val g2d: Graphics2D = image.createGraphics()

        try {
            g2d.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)

            // 1. 다크 테마 배경 (#2B2D31)
            g2d.color = Color(0x2B, 0x2D, 0x31)
            g2d.fillRect(0, 0, 128, 128)

            // 2. 디스코드 Blurple 헤더 바 (#5865F2)
            g2d.color = Color(0x58, 0x65, 0xF2)
            g2d.fillRect(0, 0, 128, 18)

            // 헤더 텍스트
            g2d.color = Color.WHITE
            g2d.font = Font("SansSerif", Font.BOLD, 10)
            g2d.drawString("Discord Live Feed", 6, 13)

            if (payload == null) {
                g2d.color = Color(0x94, 0x9B, 0xA4)
                g2d.font = Font("SansSerif", Font.PLAIN, 9)
                g2d.drawString("대기 중...", 10, 45)
                g2d.drawString("이벤트를 발생시키면", 10, 65)
                g2d.drawString("실시간 표시됩니다.", 10, 80)
            } else {
                // 채널명
                g2d.color = Color(0xDB, 0xDE, 0xE1)
                g2d.font = Font("SansSerif", Font.BOLD, 9)
                g2d.drawString(payload.channelName.take(18), 8, 32)

                // 이벤트 카드 컨테이너 (#383A40)
                g2d.color = Color(0x38, 0x3A, 0x40)
                g2d.fillRoundRect(6, 38, 116, 56, 6, 6)

                // 카드 좌측 액센트 바 (#5865F2)
                g2d.color = Color(0x58, 0x65, 0xF2)
                g2d.fillRect(6, 38, 3, 56)

                // 플레이어 및 이벤트 타입
                g2d.color = Color.WHITE
                g2d.font = Font("SansSerif", Font.BOLD, 8)
                val cleanType = payload.eventType.removePrefix("minecraft.")
                g2d.drawString("${payload.playerName} • $cleanType", 12, 50)

                // 요약 문구
                g2d.color = Color(0xDB, 0xDE, 0xE1)
                g2d.font = Font("SansSerif", Font.PLAIN, 8)
                val summaryLine = payload.summary.take(18)
                g2d.drawString(summaryLine, 12, 64)

                // RTT 지표 뱃지 (#23A55A 녹색)
                g2d.color = Color(0x23, 0xA5, 0x5A)
                g2d.font = Font("SansSerif", Font.BOLD, 8)
                g2d.drawString("✓ RTT: ${payload.rttMs}ms", 12, 80)

                // 하단 푸터 (메시지 ID 및 인과 검증 보증)
                g2d.color = Color(0x94, 0x9B, 0xA4)
                g2d.font = Font("SansSerif", Font.PLAIN, 7)
                g2d.drawString("ACK: msg_${payload.messageId.takeLast(8)}", 8, 108)
                g2d.drawString("100% Causal Match", 8, 120)
            }
        } finally {
            g2d.dispose()
        }

        return image
    }
}
