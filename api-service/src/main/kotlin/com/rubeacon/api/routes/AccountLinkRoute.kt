package com.rubeacon.api.routes

import com.rubeacon.api.service.AccountLinkService
import com.rubeacon.api.service.LinkResult
import com.rubeacon.api.service.VerifyResult
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
data class LinkRequestDto(
    @SerialName("tenant_id") val tenantId: String,
    @SerialName("discord_user_id") val discordUserId: String,
    @SerialName("minecraft_uuid") val minecraftUuid: String,
    @SerialName("minecraft_username") val minecraftUsername: String
)

@Serializable
data class VerifyRequestDto(
    @SerialName("tenant_id") val tenantId: String,
    @SerialName("minecraft_uuid") val minecraftUuid: String,
    val code: String
)

@Serializable
data class ApiResponse(
    val success: Boolean,
    val message: String? = null,
    val code: String? = null,
    @SerialName("expires_at") val expiresAt: String? = null
)

/**
 * 디스코드 유저와 마인크래프트 플레이어 간 계정 연동 요청 및 6자리 인증코드 검증 REST API.
 */
fun Route.accountLinkRoutes(accountLinkService: AccountLinkService) {
    route("/api/v1/accounts/link") {
        post("/request") {
            val req = try {
                call.receive<LinkRequestDto>()
            } catch (e: Exception) {
                call.respond(HttpStatusCode.BadRequest, ApiResponse(success = false, message = "잘못된 요청 형식입니다"))
                return@post
            }

            val uuid = try {
                UUID.fromString(req.minecraftUuid)
            } catch (e: Exception) {
                call.respond(HttpStatusCode.BadRequest, ApiResponse(success = false, message = "유효하지 않은 UUID입니다"))
                return@post
            }

            when (val result = accountLinkService.requestLink(req.tenantId, req.discordUserId, uuid, req.minecraftUsername)) {
                is LinkResult.Success -> {
                    call.respond(
                        HttpStatusCode.OK,
                        ApiResponse(
                            success = true,
                            message = "인증 코드가 발급되었습니다 (5분간 유효)",
                            code = result.code,
                            expiresAt = result.expiresAt.toString()
                        )
                    )
                }
                is LinkResult.LimitExceeded -> {
                    call.respond(
                        HttpStatusCode.Conflict,
                        ApiResponse(
                            success = false,
                            message = "최대 연동 가능 계정 수(${result.maxAllowed}개)를 초과했습니다"
                        )
                    )
                }
                is LinkResult.AlreadyLinked -> {
                    call.respond(
                        HttpStatusCode.Conflict,
                        ApiResponse(
                            success = false,
                            message = "이미 다른 디스코드 계정에 연동된 마인크래프트 계정입니다"
                        )
                    )
                }
            }
        }

        post("/verify") {
            val req = try {
                call.receive<VerifyRequestDto>()
            } catch (e: Exception) {
                call.respond(HttpStatusCode.BadRequest, ApiResponse(success = false, message = "잘못된 요청 형식입니다"))
                return@post
            }

            val uuid = try {
                UUID.fromString(req.minecraftUuid)
            } catch (e: Exception) {
                call.respond(HttpStatusCode.BadRequest, ApiResponse(success = false, message = "유효하지 않은 UUID입니다"))
                return@post
            }

            when (accountLinkService.verifyCode(req.tenantId, uuid, req.code)) {
                VerifyResult.Success -> {
                    call.respond(HttpStatusCode.OK, ApiResponse(success = true, message = "계정 연동이 완료되었습니다"))
                }
                VerifyResult.Expired -> {
                    call.respond(HttpStatusCode.Gone, ApiResponse(success = false, message = "인증 코드가 만료되었습니다"))
                }
                VerifyResult.InvalidCode -> {
                    call.respond(HttpStatusCode.BadRequest, ApiResponse(success = false, message = "인증 코드가 일치하지 않습니다"))
                }
                VerifyResult.TooManyAttempts -> {
                    call.respond(HttpStatusCode.TooManyRequests, ApiResponse(success = false, message = "인증 시도 횟수를 초과했습니다 (5회)"))
                }
                VerifyResult.NotFound -> {
                    call.respond(HttpStatusCode.NotFound, ApiResponse(success = false, message = "대기 중인 연동 요청을 찾을 수 없습니다"))
                }
            }
        }
    }
}
