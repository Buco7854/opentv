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
 * Keeping this type independent from transport lets every client share the same
 * bearer-token authentication path without changing route handlers.
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
    val method: String,
    val path: String,
    val clientIp: String,
    val webSocketToken: String? = null,
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
) {
    suspend fun authenticate(request: ApiRequestCredentials): ApiPrincipal? =
        authenticator.authenticate(request)

    suspend fun isAllowed(principal: ApiPrincipal, request: ApiRequestCredentials): Boolean =
        accessPolicy.isAllowed(principal, request)

    companion object {
        /**
         * Test-only open-access adapter. Production composition uses [authenticated].
         */
        fun openAccess(): ApiSecurity = ApiSecurity(
            ApiAuthenticator { ApiPrincipal(subject = "anonymous", roles = setOf("user")) },
        )

        fun authenticated(auth: AuthService, cipher: StreamCipher): ApiSecurity = ApiSecurity(
            authenticator = ApiAuthenticator { request ->
                val actor = bearerToken(request.authorization)
                    ?.let { auth.requestAuthenticator.authenticate(it) }
                    ?: webSocketSession(request, cipher)
                        ?.let { auth.authenticateSession(it) }
                actor?.let {
                    ApiPrincipal(
                        subject = it.userId,
                        displayName = it.displayName,
                        roles = it.roles,
                        authSessionId = it.authSessionId,
                        username = it.username,
                        authMethod = it.authMethod,
                        clientKind = it.clientKind,
                    )
                }
            },
        )
    }
}

private fun bearerToken(authorization: String?): String? {
    val parts = authorization?.trim()?.split(Regex("\\s+")) ?: return null
    return parts.takeIf { it.size == 2 && it[0].equals("Bearer", ignoreCase = true) }
        ?.get(1)
        ?.takeIf(String::isNotBlank)
}

private fun webSocketSession(
    request: ApiRequestCredentials,
    cipher: StreamCipher,
): String? {
    if (!request.path.endsWith("/ws")) return null
    val capability = request.webSocketToken?.let(cipher::tryDecryptWebSocket) ?: return null
    val leaseId = request.path.substringAfterLast("/playback/", "").substringBefore("/ws")
    return capability.sessionId.takeIf { leaseId.isNotBlank() && capability.leaseId == leaseId }
}

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
            method = call.request.httpMethod.value,
            path = call.request.path(),
            clientIp = clientIp(call),
            webSocketToken = call.request.queryParameters["ws_token"],
        )
        val principal = security.authenticate(request) ?: throw UnauthenticatedApiException()
        if (!security.isAllowed(principal, request)) throw ForbiddenApiException()
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
