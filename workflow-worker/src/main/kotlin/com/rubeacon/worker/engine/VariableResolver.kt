package com.rubeacon.worker.engine

import java.time.OffsetDateTime

/**
 * 템플릿 문자열 내 플레이스홀더 치환 및 미정의 변수 유효성 검증기.
 * docs/WORKFLOW_ENGINE_SPEC.md §5 규격을 준수함.
 */
object VariableResolver {

    private val PLACEHOLDER_REGEX = Regex("""\{([a-zA-Z0-9_]+)\}""")

    /**
     * 기본 제공 예약 변수 목록.
     */
    val DEFAULT_VARIABLE_NAMES = setOf(
        "User_Nickname",
        "Minecraft_UUID",
        "Discord_User",
        "Discord_User_ID",
        "Server_Name",
        "Current_Time"
    )

    /**
     * EventEnvelope로부터 기본 예약 변수 맵을 추출함.
     */
    fun extractDefaultVariables(context: WorkflowContext): Map<String, String> {
        val event = context.initialEvent
        val actor = event.actor
        val subject = event.subject
        val defaults = mutableMapOf<String, String>()

        // User_Nickname: actor 혹은 subject의 식별자나 payload 참조
        val nickname = context.variables["User_Nickname"]?.toString()
            ?: (if (actor?.type == "minecraft_player") actor.id else null)
            ?: (if (subject?.type == "minecraft_player") subject.id else null)
            ?: "UnknownPlayer"
        defaults["User_Nickname"] = nickname

        val mcUuid = context.variables["Minecraft_UUID"]?.toString()
            ?: (if (actor?.type == "minecraft_player") actor.id else null)
            ?: (if (subject?.type == "minecraft_player") subject.id else null)
            ?: "00000000-0000-0000-0000-000000000000"
        defaults["Minecraft_UUID"] = mcUuid

        val discordId = context.variables["Discord_User_ID"]?.toString()
            ?: (if (actor?.type == "discord_user") actor.id else null)
            ?: (if (subject?.type == "discord_user") subject.id else null)
            ?: "0"
        defaults["Discord_User_ID"] = discordId

        defaults["Discord_User"] = context.variables["Discord_User"]?.toString() ?: "<@$discordId>"
        defaults["Server_Name"] = context.variables["Server_Name"]?.toString()
            ?: event.minecraftNetworkId
            ?: "DefaultServer"
        defaults["Current_Time"] = context.variables["Current_Time"]?.toString()
            ?: event.occurredAt.ifEmpty { OffsetDateTime.now().toString() }

        return defaults
    }

    /**
     * 템플릿 문자열의 모든 `{var}` 플레이스홀더를 치환함.
     * 미선언된 변수가 존재할 경우 [IllegalArgumentException]을 즉시 발생시킴.
     */
    fun resolve(template: String, context: WorkflowContext): String {
        val defaults = extractDefaultVariables(context)
        val allVars = defaults + context.variables.mapValues { it.value?.toString() ?: "" }

        return PLACEHOLDER_REGEX.replace(template) { matchResult ->
            val key = matchResult.groupValues[1]
            val value = allVars[key]
                ?: throw IllegalArgumentException("Unresolved variable '{$key}' in template: '$template'")
            value
        }
    }

    /**
     * 워크플로우 배포 시 사전 유효성 검증용 메서드.
     */
    fun validateTemplate(template: String, declaredVariables: Set<String>) {
        val available = DEFAULT_VARIABLE_NAMES + declaredVariables
        val missing = mutableListOf<String>()

        PLACEHOLDER_REGEX.findAll(template).forEach { match ->
            val key = match.groupValues[1]
            if (key !in available) {
                missing.add(key)
            }
        }

        if (missing.isNotEmpty()) {
            throw IllegalArgumentException("Template contains undeclared variables: $missing in '$template'")
        }
    }
}
