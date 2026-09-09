package com.rubeacon.plugin

import be.seeseemelk.mockbukkit.MockBukkit
import be.seeseemelk.mockbukkit.ServerMock
import com.rubeacon.common.event.EventEnvelope
import com.rubeacon.common.transport.CommandRequestPayload
import com.rubeacon.common.transport.Opcode
import com.rubeacon.plugin.listener.PlayerAdvancementPayload
import com.rubeacon.plugin.listener.PlayerLevelChangePayload
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.advancement.Advancement
import org.bukkit.command.CommandSender
import org.bukkit.command.defaults.BukkitCommand
import org.bukkit.event.player.PlayerAdvancementDoneEvent
import org.bukkit.event.player.PlayerLevelChangeEvent
import org.bukkit.inventory.ItemStack
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy
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
    fun `플레이어 레벨업 이벤트 수신 시 올바른 비즈니스 멱등키와 WSS 이벤트를 전송해야 한다`() {
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
        // 비즈니스 멱등키 검증 (mc:lvl:{uuid}:{newLevel})
        assertEquals("mc:lvl:${player.uniqueId}:30", envelope.idempotencyKey)

        val payload = envelope.decodePayload<PlayerLevelChangePayload>()
        assertEquals(0, payload.oldLevel)
        assertEquals(30, payload.newLevel)
        assertEquals("Steve", payload.playerName)
    }

    @Test
    fun `디스플레이가 있는 발전과제 완료 이벤트 수신 시 올바른 비즈니스 멱등키와 WSS 이벤트를 전송해야 한다`() {
        val player = server.addPlayer("Alex")

        val display = Proxy.newProxyInstance(
            io.papermc.paper.advancement.AdvancementDisplay::class.java.classLoader,
            arrayOf(io.papermc.paper.advancement.AdvancementDisplay::class.java)
        ) { _, _, _ -> null } as io.papermc.paper.advancement.AdvancementDisplay

        val key = NamespacedKey.minecraft("story/mine_stone")
        val advancement = Proxy.newProxyInstance(
            Advancement::class.java.classLoader,
            arrayOf(Advancement::class.java)
        ) { _, method, _ ->
            when (method.name) {
                "getKey" -> key
                "getDisplay" -> display
                else -> null
            }
        } as Advancement

        server.pluginManager.callEvent(PlayerAdvancementDoneEvent(player, advancement))

        val outboundQueue = plugin.getOutboundQueue()
        assertEquals(1, outboundQueue.size)

        val frame = outboundQueue.first()
        assertEquals(Opcode.EVENT, frame.op)

        val envelope = frame.decodePayload<EventEnvelope>()
        assertEquals("minecraft.player.advancement_done", envelope.eventType)
        assertEquals("mc:adv:${player.uniqueId}:minecraft:story/mine_stone", envelope.idempotencyKey)

        val payload = envelope.decodePayload<PlayerAdvancementPayload>()
        assertEquals("Alex", payload.playerName)
        assertEquals("minecraft:story/mine_stone", payload.advancementId)
    }

    @Test
    fun `디스플레이가 없는 백그라운드 발전과제(레시피 등)는 이벤트를 발행하지 않아야 한다`() {
        val player = server.addPlayer("Alex")

        val key = NamespacedKey.minecraft("recipes/decorations/crafting_table")
        val advancementWithoutDisplay = Proxy.newProxyInstance(
            Advancement::class.java.classLoader,
            arrayOf(Advancement::class.java)
        ) { _, method, _ ->
            when (method.name) {
                "getKey" -> key
                "getDisplay" -> null
                else -> null
            }
        } as Advancement

        server.pluginManager.callEvent(PlayerAdvancementDoneEvent(player, advancementWithoutDisplay))

        val outboundQueue = plugin.getOutboundQueue()
        assertEquals(0, outboundQueue.size)
    }

    @Test
    fun `메인 스레드에서 직접 executeCommandOnMainTick 호출 시 즉시 동기 실행되어야 한다`() = runTest {
        val player = server.addPlayer("Steve")

        val commandPayload = CommandRequestPayload(
            requestId = "req_diamond_01",
            command = "give",
            args = listOf("Steve", "diamond", "1")
        )

        // 메인 스레드에서 직접 호출
        val response = plugin.executeCommandOnMainTick(commandPayload)
        assertEquals("req_diamond_01", response.requestId)
        assertTrue(response.success)

        // 플레이어 인벤토리에 다이아몬드가 들어왔는지 검증
        assertTrue(player.inventory.contains(Material.DIAMOND))
    }

    @Test
    fun `비동기 스레드에서 executeCommandOnMainTick 호출 시 메인 틱에서 안전하게 실행되고 결과를 반환해야 한다`() = runTest {
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

        // 스케줄러가 메인 틱에서 실행할 수 있도록 틱 진행
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

    @Test
    fun `아웃바운드 큐는 50개를 초과할 때 오래된 항목을 제거하여 메모리 누수를 방어해야 한다`() {
        // 60개의 이벤트 프레임을 연속 적재
        for (i in 1..60) {
            val dummyFrame = com.rubeacon.common.transport.WebSocketFrame.ping(traceId = "trc_$i")
            plugin.enqueueOutboundFrame(dummyFrame)
        }

        val queue = plugin.getOutboundQueue()
        assertEquals(50, queue.size)
        // 가장 오래된 1~10번은 제거되고 11번부터 60번까지 유지되어야 함
        assertEquals("trc_11", queue.first().traceId)
        assertEquals("trc_60", queue.last().traceId)
    }

    @Test
    fun `플러그인이 비활성화된 상태에서 명령어 수신 시 실패 응답을 반환해야 한다`() = runTest {
        // 플러그인 비활성화
        server.pluginManager.disablePlugin(plugin)

        val commandPayload = CommandRequestPayload(
            requestId = "req_disabled_01",
            command = "say hello",
            args = emptyList()
        )

        val response = plugin.executeCommandOnMainTick(commandPayload)
        assertEquals("req_disabled_01", response.requestId)
        kotlin.test.assertFalse(response.success)
        assertEquals("Plugin is disabled", response.output)
    }
}
