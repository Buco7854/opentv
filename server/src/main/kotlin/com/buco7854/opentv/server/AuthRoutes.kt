package com.buco7854.opentv.server

import com.buco7854.opentv.contract.*
import com.buco7854.opentv.serverdata.ClientKind
import io.ktor.http.Cookie
import io.ktor.http.CookieEncoding
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondRedirect
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route

internal fun Route.publicAuthRoutes(
    auth: AuthService,
    oidc: OidcService,
    webAuthn: WebAuthnService,
    deviceLink: DeviceLinkService,
    config: AuthConfig,
    origins: PublicOrigin,
    clientIp: (ApplicationCall) -> String,
) {
    val flows = auth.flows
    route("/auth") {
        get("/capabilities") { call.respond(flows.capabilities(origins.webAuthn(call))) }
        post("/bootstrap") {
            call.respondAuth(
                flows.bootstrap(call.receive(), clientIp(call), call.clientKind()),
            )
        }
        post("/password") {
            call.respondAuth(
                flows.password(call.receive(), clientIp(call), call.clientKind()),
            )
        }
        post("/activate") {
            call.respondAuth(
                flows.activate(call.receive(), clientIp(call), call.clientKind()),
            )
        }
        post("/totp/enroll/start") {
            call.respond(
                flows.startTotpEnrollment(
                    call.receive<TotpEnrollmentStartRequestDto>().challenge,
                    clientIp(call),
                )
            )
        }
        post("/totp/enroll/complete") {
            call.respondAuth(
                flows.completeTotpEnrollment(call.receive(), clientIp(call), call.clientKind()),
            )
        }
        post("/totp") {
            call.respondAuth(
                flows.completeTotp(call.receive(), clientIp(call), call.clientKind()),
            )
        }
        post("/recovery") {
            call.respondAuth(
                flows.completeRecovery(call.receive(), clientIp(call), call.clientKind()),
            )
        }
        post("/webauthn/register/options") {
            call.respond(
                webAuthn.registrationOptions(call.receive(), clientIp(call), origins.webAuthn(call)),
            )
        }
        post("/webauthn/register/complete") {
            call.respondAuth(
                webAuthn.completeRegistration(
                    call.receive(),
                    clientIp(call),
                    call.clientKind(),
                ),
            )
        }
        post("/webauthn/authenticate/options") {
            call.respond(
                webAuthn.authenticationOptions(call.receive(), clientIp(call), origins.webAuthn(call)),
            )
        }
        post("/webauthn/authenticate/complete") {
            call.respondAuth(
                webAuthn.completeAuthentication(
                    call.receive(),
                    clientIp(call),
                    call.clientKind(),
                ),
            )
        }
        post("/webauthn/login/options") {
            call.respond(
                webAuthn.loginOptions(
                    call.receive<WebAuthnLoginOptionsRequestDto>(),
                    clientIp(call),
                    origins.webAuthn(call),
                )
            )
        }
        post("/webauthn/login/complete") {
            call.respondAuth(
                webAuthn.completeLogin(call.receive(), clientIp(call), call.clientKind()),
            )
        }
        post("/link/start") {
            call.respond(
                deviceLink.start(
                    call.receive(),
                    call.request.headers[HttpHeaders.UserAgent],
                    clientIp(call),
                    origins.of(call),
                )
            )
        }
        post("/link/poll") {
            val result = deviceLink.poll(call.receive())
            call.respond(result.status)
        }
        get("/oidc/start") {
            val secure = origins.secure(call)
            val start = oidc.start(
                clientIp(call),
                origins.url(call, OIDC_CALLBACK_PATH),
                call.request.queryParameters["handoff"],
            )
            call.response.cookies.append(
                oidcTransactionCookie(start.transactionToken, config, secure),
            )
            call.respondRedirect(start.authorizationUrl)
        }
        get("/oidc/callback") {
            val secure = origins.secure(call)
            // Every cookie has to be appended before a redirect completes the response;
            // a header set after that throws. The transaction is single-use, so it is
            // cleared up front whichever way the exchange ends.
            call.response.cookies.append(expiredOidcTransactionCookie(config, secure))
            try {
                val result = oidc.callback(
                    call.request.queryParameters["code"],
                    call.request.queryParameters["state"],
                    call.request.queryParameters["error"],
                    call.request.cookies[OIDC_TRANSACTION_COOKIE],
                    call.clientKind(),
                )
                call.respondRedirect(
                    oidcResultRedirect(result.flow, result.oidcHandoff),
                )
            } catch (_: InvalidCredentialsException) {
                call.respondRedirect("/login?auth=oidc_error")
            } catch (_: InvalidChallengeException) {
                call.respondRedirect("/login?auth=oidc_error")
            } catch (_: IllegalArgumentException) {
                call.respondRedirect("/login?auth=oidc_error")
            }
        }
    }
}

internal fun oidcResultRedirect(flow: AuthFlowDto, handoff: String? = null): String =
    flow.sessionToken?.let {
        "/#session=${urlEncode(it)}" +
            (handoff?.let { value -> "&handoff=${urlEncode(value)}" } ?: "")
    } ?: "/?auth=pending"

internal const val MAX_PUBLIC_AUTH_REQUEST_BODY_BYTES = 65_536L

private val PUBLIC_AUTH_BODY_PATHS = setOf(
    "/api/v1/auth/bootstrap",
    "/api/v1/auth/password",
    "/api/v1/auth/activate",
    "/api/v1/auth/totp/enroll/start",
    "/api/v1/auth/totp/enroll/complete",
    "/api/v1/auth/totp",
    "/api/v1/auth/recovery",
    "/api/v1/auth/webauthn/register/options",
    "/api/v1/auth/webauthn/register/complete",
    "/api/v1/auth/webauthn/authenticate/options",
    "/api/v1/auth/webauthn/authenticate/complete",
    "/api/v1/auth/webauthn/login/options",
    "/api/v1/auth/webauthn/login/complete",
    "/api/v1/auth/link/start",
    "/api/v1/auth/link/poll",
)

internal fun requestBodyLimit(path: String): Long =
    if (path in PUBLIC_AUTH_BODY_PATHS) {
        MAX_PUBLIC_AUTH_REQUEST_BODY_BYTES
    } else {
        MAX_REQUEST_BODY_BYTES
    }

internal fun Route.adminAuthRoutes(auth: AuthService) {
    route("/admin") {
        route("/users") {
            get { call.respond(auth.adminUsers(call.actor)) }
            post {
                call.respond(
                    HttpStatusCode.Created,
                    auth.adminCreateUser(call.actor, call.receive()),
                )
            }
            route("/{userId}") {
                post("/update") {
                    call.respond(
                        auth.adminUpdateUser(
                            call.actor,
                            call.requiredParameter("userId"),
                            call.receive(),
                        )
                    )
                }
                post("/reset") {
                    call.respond(
                        auth.adminResetUser(
                            call.actor,
                            call.requiredParameter("userId"),
                        )
                    )
                }
                post("/revoke-sessions") {
                    auth.revokeSession(call.actor, call.requiredParameter("userId"), null)
                    call.respond(HttpStatusCode.NoContent)
                }
                get("/sessions") {
                    call.respond(auth.adminSessions(call.actor, call.requiredParameter("userId")))
                }
                delete("/sessions/{sessionId}") {
                    auth.revokeSession(
                        call.actor,
                        call.requiredParameter("userId"),
                        call.requiredParameter("sessionId"),
                    )
                    call.respond(HttpStatusCode.NoContent)
                }
                post("/playlists") {
                    auth.setUserPlaylists(
                        call.actor,
                        call.requiredParameter("userId"),
                        call.receive<PlaylistIdsDto>().playlistIds,
                    )
                    call.respond(HttpStatusCode.NoContent)
                }
                get("/progress") {
                    call.respond(auth.adminResume(call.actor, call.requiredParameter("userId")))
                }
                delete("/progress/{contentId}") {
                    auth.adminDeleteResume(
                        call.actor,
                        call.requiredParameter("userId"),
                        call.requiredParameter("contentId"),
                    )
                    call.respond(HttpStatusCode.NoContent)
                }
                delete {
                    auth.adminDeleteUser(
                        call.actor,
                        call.requiredParameter("userId"),
                    )
                    call.respond(HttpStatusCode.NoContent)
                }
            }
        }
        route("/playlist-template") {
            get { call.respond(PlaylistIdsDto(auth.defaultPlaylists(call.actor))) }
            post {
                auth.setDefaultPlaylists(call.actor, call.receive<PlaylistIdsDto>().playlistIds)
                call.respond(HttpStatusCode.NoContent)
            }
        }
        route("/oidc") {
            get("/pending") { call.respond(auth.pendingOidc(call.actor)) }
            post("/approve") {
                call.respond(auth.approveOidc(call.actor, call.receive()))
            }
        }
    }
}

internal fun Route.authenticatedAuthRoutes(
    auth: AuthService,
    webAuthn: WebAuthnService,
    deviceLink: DeviceLinkService,
    origins: PublicOrigin,
    clientIp: (ApplicationCall) -> String,
) {
    route("/auth") {
        get("/me") { call.respond(auth.current(call.actor)) }
        post("/logout") {
            val request = call.receive<LogoutRequestDto>()
            auth.logout(call.actor, request.all)
            call.respond(HttpStatusCode.NoContent)
        }
        post("/webauthn/add/options") {
            call.respond(
                webAuthn.additionalRegistrationOptions(call.actor, origins.webAuthn(call)),
            )
        }
        post("/webauthn/add/complete") {
            call.respondAuth(
                webAuthn.completeRegistration(call.receive(), clientIp(call)),
            )
        }
        get("/webauthn/credentials") {
            call.respond(webAuthn.credentials(call.actor))
        }
        post("/webauthn/credentials/delete") {
            call.respondAuth(
                webAuthn.deleteCredential(call.actor, call.receive()),
            )
        }
        get("/totp/status") {
            call.respond(auth.totpStatus(call.actor))
        }
        post("/totp/add/start") {
            call.receive<TotpAddStartRequestDto>()
            call.respond(auth.startAdditionalTotpEnrollment(call.actor))
        }
        post("/totp/add/complete") {
            call.respondAuth(
                auth.completeAdditionalTotpEnrollment(
                    call.actor,
                    call.receive(),
                    clientIp(call),
                ),
            )
        }
        post("/totp/delete") {
            call.receive<TotpDeleteRequestDto>()
            call.respondAuth(auth.deleteTotp(call.actor))
        }
        post("/link/lookup") {
            call.respond(deviceLink.lookup(call.actor, call.receive(), clientIp(call)))
        }
        post("/link/approve") {
            deviceLink.approve(call.actor, call.receive(), clientIp(call))
            call.respond(HttpStatusCode.NoContent)
        }
        post("/link/deny") {
            deviceLink.deny(call.actor, call.receive(), clientIp(call))
            call.respond(HttpStatusCode.NoContent)
        }
        post("/recovery/regenerate") {
            call.respondAuth(auth.regenerateRecoveryCodes(call.actor))
        }
        post("/password/change") {
            call.respondAuth(auth.changePassword(call.actor, call.receive()))
        }
    }
}

private suspend fun ApplicationCall.respondAuth(
    result: AuthResult,
) {
    if (result.flow.status == "MFA_REQUIRED" ||
        result.flow.status == "ENROLLMENT_REQUIRED" ||
        result.flow.status == "PENDING_APPROVAL"
    ) {
        respond(HttpStatusCode.Conflict, result.flow)
    } else {
        respond(result.flow)
    }
}

private fun ApplicationCall.clientKind(): String =
    if (request.headers[CLIENT_KIND_HEADER].equals("native", ignoreCase = true)) {
        ClientKind.NATIVE
    } else {
        ClientKind.BROWSER
    }

internal const val CLIENT_KIND_HEADER = "X-OpenTV-Client"

internal const val OIDC_CALLBACK_PATH = "/api/v1/auth/oidc/callback"
internal const val OIDC_TRANSACTION_COOKIE = "opentv_oidc_tx"

private fun oidcTransactionCookie(token: String, config: AuthConfig, secure: Boolean) = Cookie(
    name = OIDC_TRANSACTION_COOKIE,
    value = token,
    encoding = CookieEncoding.RAW,
    maxAge = 5 * 60,
    path = "/api/v1/auth/oidc/callback",
    secure = secure,
    httpOnly = true,
    extensions = mapOf("SameSite" to "Lax"),
)

private fun expiredOidcTransactionCookie(config: AuthConfig, secure: Boolean) = Cookie(
    name = OIDC_TRANSACTION_COOKIE,
    value = "",
    encoding = CookieEncoding.RAW,
    maxAge = 0,
    path = "/api/v1/auth/oidc/callback",
    secure = secure,
    httpOnly = true,
    extensions = mapOf("SameSite" to "Lax"),
)
