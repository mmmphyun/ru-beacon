package com.rubeacon.plugin

import be.seeseemelk.mockbukkit.MockBukkit
import be.seeseemelk.mockbukkit.ServerMock
import com.rubeacon.common.event.EventEnvelope
import com.rubeacon.common.transport.CommandRequestPayload
import com.rubeacon.common.transport.Opcode
import com.rubeacon.plugin.listener.PlayerLevelChangePayload
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.bukkit.Material
import org.bukkit.command.CommandSender
import org.bukkit.command.defaults.BukkitCommand
import org.bukkit.event.player.PlayerLevelChangeEvent
import org.bukkit.inventory.ItemStack
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class RuBeaconPluginTest {

    private lateinit var server: ServerMock
    private lateinit var plugin: RuBeaconPlugin

    @BeforeEach
    fun setUp() {
        server = MockBukkit.mock()
        plugin = MockBukkit.load(RuBeaconPlugin::class.java)

        // MockBukkit 가상 환경용 give 명령어 모킹 등록
        server.commandMap.register("minecraft", object : BukkitCommand("give") {
            override fun execute(sender: CommandSender, commandLabel: String, args: Array<out String>): Boolean {
                if (args.isEmpty()) return false
                val target = server.getPlayer(args[0]) ?: return false
                val materialName = args.getOrNull(1)?.uppercase() ?: "DIAMOND"
                val material = Material.matchMaterial(materialName) ?: Material.DIAMOND
                val amount = args.getOrNull(2)?.toIntOrNull() ?: 1
                target.inventory.addItem(ItemStack(material, amount))
                return true
            }
        })
    }

    @AfterEach
    fun tearDown() {
        MockBukkit.unmock()
    }

    @Test
    fun `플러그인 로드 시 기본 설정과 컴포넌트가 정상 초기화되어야 한다`() {
        assertNotNull(plugin.ruConfig)
        assertNotNull(plugin.webSocketClient)
        assertTrue(plugin.isEnabled)
    }

    @Test
    fun `플레이어 레벨업 이벤트 수신 시 올바른 WSS 이벤트를 전송해야 한다`() {
        val player = server.addPlayer("Steve")
        player.setLevel(30)
        // MockBukkit에서 레벨 변경 이벤트 명시적 트리거
        server.pluginManager.callEvent(PlayerLevelChangeEvent(player, 0, 30))

        val outboundQueue = plugin.getOutboundQueue()
        assertEquals(1, outboundQueue.size)

        val frame = outboundQueue.first()
        assertEquals(Opcode.EVENT, frame.op)

        val envelope = frame.decodePayload<EventEnvelope>()
        assertEquals("minecraft.player.level_up", envelope.eventType)
        assertEquals("minecraft", envelope.source)
        assertEquals(player.uniqueId.toString(), envelope.actor?.id)

        val payload = envelope.decodePayload<PlayerLevelChangePayload>()
        assertEquals(0, payload.oldLevel)
        assertEquals(30, payload.newLevel)
        assertEquals("Steve", payload.playerName)
    }

    @Test
    fun `서버로부터 수신한 명령어는 Bukkit 메인 스레드에서 실행되어야 한다`() {
        val player = server.addPlayer("Steve")

        val commandPayload = CommandRequestPayload(
            requestId = "req_diamond_01",
            command = "give",
            args = listOf("Steve", "diamond", "1")
        )

        // 가상 COMMAND_REQUEST 수신
        val scheduledRes = plugin.handleIncomingCommand(commandPayload)
        assertEquals("req_diamond_01", scheduledRes.requestId)

        // 1틱 진행하여 스케줄된 태스크 실행 (비동기 스레드에서 왔거나 메인 스레드 직접 실행된 경우 커버)
        server.scheduler.performOneTick()

        // 플레이어 인벤토리에 다이아몬드가 들어왔는지 검증
        assertTrue(player.inventory.contains(Material.DIAMOND))
    }

    @Test
    fun `executeCommandOnMainTick 코루틴 호출 시 메인 틱에서 안전하게 실행되고 결과를 반환해야 한다`() = runTest {
        val player = server.addPlayer("Alex")

        val commandPayload = CommandRequestPayload(
            requestId = "req_apple_01",
            command = "give",
            args = listOf("Alex", "apple", "5")
        )

        // 비동기 스레드 풀에서 디스패치 호출
        val deferredResponse = async(Dispatchers.IO) {
            plugin.executeCommandOnMainTick(commandPayload)
        }

        // 스케줄러가 메인 틱에서 실행할 수 있도록 1틱 진행
        // 백그라운드 태스크가 스케줄러에 등록될 때까지 잠시 대기 후 틱 실행
        var ticks = 0
        while (!deferredResponse.isCompleted && ticks < 20) {
            server.scheduler.performOneTick()
            ticks++
            Thread.sleep(10)
        }

        val response = deferredResponse.await()
        assertEquals("req_apple_01", response.requestId)
        assertTrue(response.success)
        assertTrue(player.inventory.contains(Material.APPLE))
    }
}
