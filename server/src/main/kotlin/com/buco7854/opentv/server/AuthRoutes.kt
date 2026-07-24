package com.buco7854.opentv.server

import io.ktor.http.Cookie
import io.ktor.http.CookieEncoding
import io.ktor.http.HttpStatusCode
import io.ktor.http.HttpHeaders
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
    config: AuthConfig,
    clientIp: (ApplicationCall) -> String,
) {
    val flows = auth.flows
    route("/auth") {
        get("/capabilities") { call.respond(flows.capabilities()) }
        post("/bootstrap") {
            call.requireAuthOrigin(config)
            call.respondAuth(flows.bootstrap(call.receive(), clientIp(call)), config)
        }
        post("/password") {
            call.requireAuthOrigin(config)
            call.respondAuth(flows.password(call.receive(), clientIp(call)), config)
        }
        post("/activate") {
            call.requireAuthOrigin(config)
            call.respondAuth(flows.activate(call.receive(), clientIp(call)), config)
        }
        post("/totp/enroll/start") {
            call.requireAuthOrigin(config)
            call.respond(
                flows.startTotpEnrollment(
                    call.receive<TotpEnrollmentStartRequestDto>().challenge,
                    clientIp(call),
                )
            )
        }
        post("/totp/enroll/complete") {
            call.requireAuthOrigin(config)
            call.respondAuth(flows.completeTotpEnrollment(call.receive(), clientIp(call)), config)
        }
        post("/totp") {
            call.requireAuthOrigin(config)
            call.respondAuth(flows.completeTotp(call.receive(), clientIp(call)), config)
        }
        post("/recovery") {
            call.requireAuthOrigin(config)
            call.respondAuth(flows.completeRecovery(call.receive(), clientIp(call)), config)
        }
        post("/webauthn/register/options") {
            call.requireAuthOrigin(config)
            call.respond(webAuthn.registrationOptions(call.receive(), clientIp(call)))
        }
        post("/webauthn/register/complete") {
            call.requireAuthOrigin(config)
            call.respondAuth(webAuthn.completeRegistration(call.receive(), clientIp(call)), config)
        }
        post("/webauthn/authenticate/options") {
            call.requireAuthOrigin(config)
            call.respond(webAuthn.authenticationOptions(call.receive(), clientIp(call)))
        }
        post("/webauthn/authenticate/complete") {
            call.requireAuthOrigin(config)
            call.respondAuth(webAuthn.completeAuthentication(call.receive(), clientIp(call)), config)
        }
        get("/oidc/start") {
            val start = oidc.start(clientIp(call))
            call.response.cookies.append(oidcTransactionCookie(start.transactionToken, config))
            call.respondRedirect(start.authorizationUrl)
        }
        get("/oidc/callback") {
            try {
                val result = oidc.callback(
                    call.request.queryParameters["code"],
                    call.request.queryParameters["state"],
                    call.request.queryParameters["error"],
                    call.request.cookies[OIDC_TRANSACTION_COOKIE],
                    clientIp(call),
                )
                result.sessionToken?.let { call.response.cookies.append(sessionCookie(it, config)) }
                call.respondRedirect(
                    if (result.flow.status == "AUTHENTICATED") "/" else "/?auth=pending",
                )
            } catch (_: InvalidCredentialsException) {
                call.respondRedirect("/login?auth=oidc_error")
            } catch (_: InvalidChallengeException) {
                call.respondRedirect("/login?auth=oidc_error")
            } catch (_: IllegalArgumentException) {
                call.respondRedirect("/login?auth=oidc_error")
            } finally {
                call.response.cookies.append(expiredOidcTransactionCookie(config))
            }
        }
    }
}

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
)

internal fun requestBodyLimit(path: String): Long =
    if (path in PUBLIC_AUTH_BODY_PATHS) {
        MAX_PUBLIC_AUTH_REQUEST_BODY_BYTES
    } else {
        MAX_REQUEST_BODY_BYTES
    }

private fun ApplicationCall.requireAuthOrigin(config: AuthConfig) {
    val expected = "${config.publicUrl.scheme}://${config.publicUrl.rawAuthority}"
    if (request.headers[HttpHeaders.Origin] != expected) throw CsrfException()
}

internal fun Route.adminAuthRoutes(
    auth: AuthService,
    clientIp: (ApplicationCall) -> String,
) {
    route("/admin") {
        route("/users") {
            get { call.respond(auth.adminUsers(call.actor)) }
            post {
                call.respond(
                    HttpStatusCode.Created,
                    auth.adminCreateUser(call.actor, call.receive(), clientIp(call)),
                )
            }
            route("/{userId}") {
                post("/update") {
                    call.respond(
                        auth.adminUpdateUser(
                            call.actor,
                            call.requiredParameter("userId"),
                            call.receive(),
                            clientIp(call),
                        )
                    )
                }
                post("/reset") {
                    call.respond(
                        auth.adminResetUser(
                            call.actor,
                            call.requiredParameter("userId"),
                            clientIp(call),
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
                        clientIp(call),
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
    config: AuthConfig,
    clientIp: (ApplicationCall) -> String,
) {
    route("/auth") {
        get("/me") { call.respond(auth.current(call.actor)) }
        post("/logout") {
            val request = call.receive<LogoutRequestDto>()
            auth.logout(call.actor, request.all)
            call.response.cookies.append(expiredSessionCookie(config))
            call.respond(HttpStatusCode.NoContent)
        }
        post("/webauthn/add/options") {
            call.respond(webAuthn.additionalRegistrationOptions(call.actor))
        }
        post("/webauthn/add/complete") {
            call.respondAuth(
                webAuthn.completeRegistration(call.receive(), clientIp(call)),
                config,
            )
        }
        post("/recovery/regenerate") {
            call.respondAuth(auth.regenerateRecoveryCodes(call.actor), config)
        }
        post("/password/change") {
            call.respondAuth(auth.changePassword(call.actor, call.receive()), config)
        }
    }
}

internal fun interface SessionIssuer {
    fun issue(call: ApplicationCall, token: String)
}

internal class BrowserCookieSessionIssuer(
    private val config: AuthConfig,
) : SessionIssuer {
    override fun issue(call: ApplicationCall, token: String) {
        call.response.cookies.append(sessionCookie(token, config))
    }
}

private suspend fun ApplicationCall.respondAuth(
    result: AuthResult,
    config: AuthConfig,
    issuer: SessionIssuer = BrowserCookieSessionIssuer(config),
) {
    result.sessionToken?.let { issuer.issue(this, it) }
    if (result.flow.status == "MFA_REQUIRED" ||
        result.flow.status == "ENROLLMENT_REQUIRED"
    ) {
        respond(HttpStatusCode.Conflict, result.flow)
    } else {
        respond(result.flow)
    }
}

internal fun sessionCookie(token: String, config: AuthConfig) = Cookie(
    name = SESSION_COOKIE,
    value = token,
    encoding = CookieEncoding.RAW,
    maxAge = (config.sessionAbsoluteMs / 1000).toInt(),
    path = "/",
    secure = config.secureCookies,
    httpOnly = true,
    extensions = mapOf("SameSite" to "Lax"),
)

private fun expiredSessionCookie(config: AuthConfig) = Cookie(
    name = SESSION_COOKIE,
    value = "",
    encoding = CookieEncoding.RAW,
    maxAge = 0,
    path = "/",
    secure = config.secureCookies,
    httpOnly = true,
    extensions = mapOf("SameSite" to "Lax"),
)

internal const val OIDC_TRANSACTION_COOKIE = "opentv_oidc_tx"

private fun oidcTransactionCookie(token: String, config: AuthConfig) = Cookie(
    name = OIDC_TRANSACTION_COOKIE,
    value = token,
    encoding = CookieEncoding.RAW,
    maxAge = 5 * 60,
    path = "/api/v1/auth/oidc/callback",
    secure = config.secureCookies,
    httpOnly = true,
    extensions = mapOf("SameSite" to "Lax"),
)

private fun expiredOidcTransactionCookie(config: AuthConfig) = Cookie(
    name = OIDC_TRANSACTION_COOKIE,
    value = "",
    encoding = CookieEncoding.RAW,
    maxAge = 0,
    path = "/api/v1/auth/oidc/callback",
    secure = config.secureCookies,
    httpOnly = true,
    extensions = mapOf("SameSite" to "Lax"),
)
