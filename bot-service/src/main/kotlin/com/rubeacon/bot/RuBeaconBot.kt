package com.rubeacon.bot

import com.rubeacon.bot.consumer.DiscordActionConsumer
import dev.kord.core.Kord
import dev.kord.core.behavior.interaction.response.respond
import dev.kord.core.event.gateway.ReadyEvent
import dev.kord.core.event.interaction.ButtonInteractionCreateEvent
import dev.kord.core.event.interaction.ChatInputCommandInteractionCreateEvent
import dev.kord.core.event.interaction.ModalSubmitInteractionCreateEvent
import dev.kord.core.on
import dev.kord.gateway.Intent
import dev.kord.gateway.PrivilegedIntent
import dev.kord.rest.builder.interaction.string
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.slf4j.LoggerFactory
import redis.clients.jedis.JedisPooled

/**
 * Kord 라이브러리 기반 Discord Gateway 봇 수명주기 및 인터랙션 디스패처.
 */
class RuBeaconBot(
    val config: DiscordBotConfig,
    val publisher: DiscordEventPublisher,
    val jedis: JedisPooled? = null
) {
    private val log = LoggerFactory.getLogger(RuBeaconBot::class.java)
    private val botScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    var kord: Kord? = null
        private set

    var actionConsumer: DiscordActionConsumer? = null
        private set

    /**
     * Kord 인스턴스 초기화, 인터랙션 리스너 바인딩 및 Gateway 로그인 기동.
     */
    suspend fun start() {
        if (config.token.isBlank()) {
            log.warn("DISCORD_BOT_TOKEN이 설정되지 않아 Kord Gateway 연결을 건너뜁니다.")
            return
        }

        log.info("Kord Discord Bot 초기화 시작...")
        val kordInstance = Kord(config.token)
        this.kord = kordInstance

        // 1. 이벤트 리스너 등록
        registerListeners(kordInstance)

        // 2. Redis Actions 컨슈머 기동
        if (jedis != null) {
            val consumer = DiscordActionConsumer(jedis, kordInstance)
            this.actionConsumer = consumer
            consumer.start(botScope)
        }

        // 3. Gateway 로그인
        @OptIn(PrivilegedIntent::class)
        kordInstance.login {
            intents += Intent.GuildMessages
            intents += Intent.MessageContent
        }
    }

    /**
     * 전역 슬래시 명령어 등록.
     */
    suspend fun registerGlobalCommands(kordInstance: Kord) {
        try {
            kordInstance.createGlobalChatInputCommand("verify", "마인크래프트 계정을 디스코드와 연동합니다.") {
                string("code", "인게임에서 발급받은 6자리 인증 코드") {
                    required = true
                }
            }
            kordInstance.createGlobalChatInputCommand("attend", "오늘의 마인크래프트 일일 출석 체크를 진행합니다.")
            log.info("Discord 글로벌 슬래시 명령어(/verify, /attend) 등록 완료")
        } catch (e: Exception) {
            log.error("글로벌 슬래시 명령어 등록 실패: {}", e.message, e)
        }
    }

    /**
     * 인터랙션 이벤트 리스너 바인딩 (장애 격리 보장).
     */
    fun registerListeners(kordInstance: Kord) {
        kordInstance.on<ReadyEvent> {
            log.info("Discord Bot Ready 완료: tag={}", kordInstance.getSelf().tag)
            registerGlobalCommands(kordInstance)
        }

        // 1. 슬래시 명령어 수신
        kordInstance.on<ChatInputCommandInteractionCreateEvent> {
            val command = interaction.command
            val commandName = command.rootName
            val userId = interaction.user.id.toString()
            val tenantId = interaction.data.guildId.value?.toString() ?: config.defaultTenantId
            val interactionId = interaction.id.toString()

            log.info("슬래시 커맨드 수신: /{} (user={}, tenant={})", commandName, userId, tenantId)

            try {
                val deferred = interaction.deferEphemeralResponse()
                try {
                    when (commandName) {
                        "verify" -> {
                            val code = command.strings["code"]?.trim() ?: ""
                            if (code.isBlank()) {
                                deferred.respond {
                                    content = DiscordResponseRenderer.error("인증 코드를 입력해주세요.")
                                }
                                return@on
                            }
                            val envelope = DiscordEventNormalizer.normalizeCommand(
                                tenantId = tenantId,
                                userId = userId,
                                commandName = "verify",
                                options = mapOf("code" to code),
                                interactionId = interactionId
                            )
                            publisher.publish(envelope)
                            deferred.respond {
                                content = DiscordResponseRenderer.verificationInitiated(code)
                            }
                        }
                        "attend" -> {
                            val envelope = DiscordEventNormalizer.normalizeCommand(
                                tenantId = tenantId,
                                userId = userId,
                                commandName = "attend",
                                interactionId = interactionId
                            )
                            publisher.publish(envelope)
                            deferred.respond {
                                content = DiscordResponseRenderer.attendanceRequested(userId)
                            }
                        }
                        else -> {
                            deferred.respond {
                                content = DiscordResponseRenderer.error("알 수 없는 명령어입니다: /$commandName")
                            }
                        }
                    }
                } catch (e: Exception) {
                    log.error("슬래시 커맨드 비즈니스 처리 중 예외 발생: {}", e.message, e)
                    runCatching {
                        deferred.respond {
                            content = DiscordResponseRenderer.error(e.message ?: "내부 서버 오류")
                        }
                    }
                }
            } catch (e: Exception) {
                log.error("슬래시 커맨드 Defer 단계 실패: {}", e.message, e)
            }
        }

        // 2. 버튼 클릭 인터랙션 수신
        kordInstance.on<ButtonInteractionCreateEvent> {
            val customId = interaction.componentId
            val userId = interaction.user.id.toString()
            val tenantId = interaction.data.guildId.value?.toString() ?: config.defaultTenantId
            val interactionId = interaction.id.toString()
            val messageId = interaction.message.id.toString()

            log.info("버튼 인터랙션 수신: customId={} (user={}, tenant={})", customId, userId, tenantId)

            try {
                val deferred = interaction.deferEphemeralResponse()
                try {
                    val envelope = DiscordEventNormalizer.normalizeButton(
                        tenantId = tenantId,
                        userId = userId,
                        customId = customId,
                        messageId = messageId,
                        interactionId = interactionId
                    )
                    publisher.publish(envelope)

                    val responseMessage = when {
                        customId.startsWith("attend_claim_btn") -> DiscordResponseRenderer.attendanceRequested(userId)
                        customId.startsWith("reward_claim_btn") -> DiscordResponseRenderer.rewardClaimRequested(userId, customId.substringAfter(":", ""))
                        else -> DiscordResponseRenderer.interactionAcknowledged(customId)
                    }

                    deferred.respond {
                        content = responseMessage
                    }
                } catch (e: Exception) {
                    log.error("버튼 인터랙션 처리 중 예외 발생: {}", e.message, e)
                    runCatching {
                        deferred.respond {
                            content = DiscordResponseRenderer.error(e.message ?: "내부 서버 오류")
                        }
                    }
                }
            } catch (e: Exception) {
                log.error("버튼 인터랙션 Defer 단계 실패: {}", e.message, e)
            }
        }

        // 3. 모달 제출 인터랙션 수신
        kordInstance.on<ModalSubmitInteractionCreateEvent> {
            val modalId = interaction.modalId
            val userId = interaction.user.id.toString()
            val tenantId = interaction.data.guildId.value?.toString() ?: config.defaultTenantId
            val interactionId = interaction.id.toString()

            val textInputs = interaction.textInputs.mapValues { it.value.value ?: "" }
            log.info("모달 제출 수신: modalId={} (user={}, tenant={})", modalId, userId, tenantId)

            try {
                val deferred = interaction.deferEphemeralResponse()
                try {
                    val envelope = DiscordEventNormalizer.normalizeModal(
                        tenantId = tenantId,
                        userId = userId,
                        modalId = modalId,
                        values = textInputs,
                        interactionId = interactionId
                    )
                    publisher.publish(envelope)

                    deferred.respond {
                        content = DiscordResponseRenderer.interactionAcknowledged(modalId)
                    }
                } catch (e: Exception) {
                    log.error("모달 제출 처리 중 예외 발생: {}", e.message, e)
                    runCatching {
                        deferred.respond {
                            content = DiscordResponseRenderer.error(e.message ?: "내부 서버 오류")
                        }
                    }
                }
            } catch (e: Exception) {
                log.error("모달 제출 Defer 단계 실패: {}", e.message, e)
            }
        }
    }

    /**
     * 봇 안전 종료.
     */
    suspend fun shutdown() {
        log.info("RuBeaconBot 안전 종료 시작...")
        actionConsumer?.stop()
        kord?.shutdown()
        jedis?.close()
        log.info("RuBeaconBot 안전 종료 완료")
    }
}

/**
 * bot-service 독립 실행 엔트리포인트.
 */
suspend fun main() {
    val config = DiscordBotConfig()
    val jedis = try {
        JedisPooled(config.redisHost, config.redisPort)
    } catch (e: Exception) {
        LoggerFactory.getLogger("Main").warn("Redis 연결 실패, mock 상태로 동작: {}", e.message)
        null
    }

    val publisher = DiscordEventPublisher(jedis)
    val bot = RuBeaconBot(config, publisher, jedis)

    Runtime.getRuntime().addShutdownHook(Thread {
        kotlinx.coroutines.runBlocking {
            bot.shutdown()
        }
    })

    bot.start()
}
