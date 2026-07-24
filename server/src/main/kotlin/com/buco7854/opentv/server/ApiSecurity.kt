package com.buco7854.opentv.server

import io.ktor.http.HttpHeaders
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.createRouteScopedPlugin
import io.ktor.server.application.install
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.routing.Route
import io.ktor.util.AttributeKey

/**
 * Authentication data available to application endpoints.
 *
 * Keeping this type independent from a particular authentication mechanism lets a
 * future bearer-token, session-cookie, or reverse-proxy implementation replace the
 * current open-access adapter without changing route handlers.
 */
data class ApiPrincipal(
    val subject: String,
    val displayName: String? = null,
    val roles: Set<String> = emptySet(),
)

data class ApiRequestCredentials(
    val authorization: String?,
    val cookie: String?,
    val method: String,
    val path: String,
    val clientIp: String,
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
         * Preserves the application's current deployment contract. Replace this adapter
         * at the composition root when real authentication is introduced.
         */
        fun openAccess(): ApiSecurity = ApiSecurity(
            ApiAuthenticator { ApiPrincipal(subject = "anonymous", roles = setOf("user")) },
        )
    }
}

private val ApiPrincipalKey = AttributeKey<ApiPrincipal>("OpenTvApiPrincipal")

val ApplicationCall.apiPrincipal: ApiPrincipal
    get() = attributes[ApiPrincipalKey]

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
        )
        val principal = security.authenticate(request) ?: throw UnauthenticatedApiException()
        if (!security.isAllowed(principal, request)) throw ForbiddenApiException()
        call.attributes.put(ApiPrincipalKey, principal)
    }
}

/** Applies authentication and authorization once to the complete versioned API tree. */
internal fun Route.apiSecurityBoundary(
    security: ApiSecurity,
    clientIp: (ApplicationCall) -> String,
) {
    install(ApiSecurityPlugin) {
        this.security = security
        this.clientIp = clientIp
    }
}
