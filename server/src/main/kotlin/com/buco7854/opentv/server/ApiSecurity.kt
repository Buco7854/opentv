package com.buco7854.opentv.server

import io.ktor.http.HttpHeaders
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.createRouteScopedPlugin
import io.ktor.server.application.install
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.routing.Route
import io.ktor.server.routing.RouteSelector
import io.ktor.server.routing.RouteSelectorEvaluation
import io.ktor.server.routing.RoutingResolveContext
import io.ktor.util.AttributeKey

/**
 * Authentication data available to application endpoints.
 *
 * Keeping this type independent from a particular authentication mechanism lets a
 * future native bearer-token implementation coexist with browser cookies without
 * changing route handlers.
 */
data class ApiPrincipal(
    val subject: String,
    val displayName: String? = null,
    val roles: Set<String> = emptySet(),
    val authSessionId: String = "",
    val username: String = subject,
    val authMethod: String = "unknown",
    val clientKind: String = "BROWSER",
)

data class ApiRequestCredentials(
    val authorization: String?,
    val cookie: String?,
    val method: String,
    val path: String,
    val clientIp: String,
    val csrfToken: String? = null,
    val origin: String? = null,
    /** The `Host` this request was addressed to; half of the same-origin decision. */
    val host: String? = null,
)

fun interface ApiAuthenticator {
    suspend fun authenticate(request: ApiRequestCredentials): ApiPrincipal?
}

fun interface ApiAccessPolicy {
    suspend fun isAllowed(principal: ApiPrincipal, request: ApiRequestCredentials): Boolean
}

class ApiSecurity(
    private val authenticator: ApiAuthenticator,
    private val accessPolicy: ApiAccessPolicy = ApiAccessPolicy { _, _ -> true },
    private val requestGuard: suspend (ApiPrincipal, ApiRequestCredentials) -> Unit = { _, _ -> },
) {
    suspend fun authenticate(request: ApiRequestCredentials): ApiPrincipal? =
        authenticator.authenticate(request)

    suspend fun isAllowed(principal: ApiPrincipal, request: ApiRequestCredentials): Boolean =
        accessPolicy.isAllowed(principal, request)

    suspend fun validate(principal: ApiPrincipal, request: ApiRequestCredentials) =
        requestGuard(principal, request)

    companion object {
        /**
         * Test-only open-access adapter. Production composition uses [authenticated].
         */
        fun openAccess(): ApiSecurity = ApiSecurity(
            ApiAuthenticator { ApiPrincipal(subject = "anonymous", roles = setOf("user")) },
        )

        fun authenticated(auth: AuthService, config: AuthConfig): ApiSecurity = ApiSecurity(
            authenticator = ApiAuthenticator { request ->
                val token = request.cookie
                    ?.split(';')
                    ?.map(String::trim)
                    ?.firstOrNull { it.startsWith("$SESSION_COOKIE=") }
                    ?.substringAfter('=')
                auth.requestAuthenticator.authenticate(token)?.let { actor ->
                    ApiPrincipal(
                        subject = actor.userId,
                        displayName = actor.displayName,
                        roles = actor.roles,
                        authSessionId = actor.authSessionId,
                        username = actor.username,
                        authMethod = actor.authMethod,
                        clientKind = actor.clientKind,
                    )
                }
            },
            requestGuard = { principal, request ->
                val actor = principal.toActor()
                val unsafe = request.method !in setOf("GET", "HEAD", "OPTIONS")
                if (unsafe) auth.validateCsrf(actor, request.csrfToken)
                if (unsafe || request.path.endsWith("/ws")) {
                    if (!RequestOrigin.isSameOrigin(request.origin, request.host, config.publicUrl)) {
                        throw RejectedOriginException(request.origin, config.publicUrl)
                    }
                }
            },
        )
    }
}

internal const val SESSION_COOKIE = "opentv_session"
private val ApiPrincipalKey = AttributeKey<ApiPrincipal>("OpenTvApiPrincipal")

val ApplicationCall.apiPrincipal: ApiPrincipal
    get() = attributes[ApiPrincipalKey]

val ApplicationCall.actor: Actor
    get() = apiPrincipal.toActor()

private fun ApiPrincipal.toActor() = Actor(
    userId = subject,
    authSessionId = authSessionId,
    username = username,
    displayName = displayName ?: username,
    roles = roles,
    authMethod = authMethod,
    clientKind = clientKind,
)

internal class UnauthenticatedApiException : RuntimeException()
internal class ForbiddenApiException : RuntimeException()

private class ApiSecurityConfiguration {
    lateinit var security: ApiSecurity
    lateinit var clientIp: (ApplicationCall) -> String
}

private val ApiSecurityPlugin = createRouteScopedPlugin(
    name = "OpenTvApiSecurity",
    createConfiguration = ::ApiSecurityConfiguration,
) {
    val security = pluginConfig.security
    val clientIp = pluginConfig.clientIp
    onCall { call ->
        val request = ApiRequestCredentials(
            authorization = call.request.headers[HttpHeaders.Authorization],
            cookie = call.request.headers[HttpHeaders.Cookie],
            method = call.request.httpMethod.value,
            path = call.request.path(),
            clientIp = clientIp(call),
            csrfToken = call.request.headers["X-CSRF-Token"],
            origin = call.request.headers[HttpHeaders.Origin],
            host = call.request.headers[HttpHeaders.Host],
        )
        val principal = security.authenticate(request) ?: throw UnauthenticatedApiException()
        if (!security.isAllowed(principal, request)) throw ForbiddenApiException()
        security.validate(principal, request)
        call.attributes.put(ApiPrincipalKey, principal)
    }
}

/**
 * Matches without consuming a path segment.
 *
 * `route("")` cannot express this: an empty path parses to zero segments, so Ktor
 * returns the receiver itself and a route-scoped plugin installed inside would
 * apply to sibling routes too — which silently put the public auth endpoints
 * behind authentication.
 */
private object AuthenticatedApiSelector : RouteSelector() {
    override suspend fun evaluate(context: RoutingResolveContext, segmentIndex: Int) =
        RouteSelectorEvaluation.Transparent

    override fun toString() = "(authenticated)"
}

/** Applies authentication and authorization once to the protected part of the API tree. */
internal fun Route.apiSecurityBoundary(
    security: ApiSecurity,
    clientIp: (ApplicationCall) -> String,
    build: Route.() -> Unit,
) {
    createChild(AuthenticatedApiSelector).apply {
        install(ApiSecurityPlugin) {
            this.security = security
            this.clientIp = clientIp
        }
        build()
    }
}
