package com.buco7854.opentv.server

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.oauth2.sdk.AuthorizationCode
import com.nimbusds.oauth2.sdk.AuthorizationCodeGrant
import com.nimbusds.oauth2.sdk.ResponseType
import com.nimbusds.oauth2.sdk.Scope
import com.nimbusds.oauth2.sdk.TokenRequest
import com.nimbusds.oauth2.sdk.auth.ClientSecretBasic
import com.nimbusds.oauth2.sdk.auth.Secret
import com.nimbusds.oauth2.sdk.id.ClientID
import com.nimbusds.oauth2.sdk.id.Issuer
import com.nimbusds.oauth2.sdk.id.State
import com.nimbusds.oauth2.sdk.pkce.CodeChallengeMethod
import com.nimbusds.oauth2.sdk.pkce.CodeVerifier
import com.nimbusds.openid.connect.sdk.AuthenticationRequest
import com.nimbusds.openid.connect.sdk.Nonce
import com.nimbusds.openid.connect.sdk.OIDCTokenResponse
import com.nimbusds.openid.connect.sdk.OIDCTokenResponseParser
import com.nimbusds.openid.connect.sdk.op.OIDCProviderMetadata
import com.nimbusds.openid.connect.sdk.validators.IDTokenValidator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.net.URI
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@Serializable
private data class OidcChallengePayload(
    val verifier: String,
    val nonce: String,
    val transactionHash: String,
)

internal data class OidcStartResult(
    val authorizationUrl: String,
    val expiresAtMs: Long,
    val transactionToken: String,
)

/** Confidential Authorization Code + PKCE adapter. Provider tokens never leave this class. */
class OidcService(
    private val auth: AuthService,
    private val config: AuthConfig,
) {
    private val oidc: OidcConfig get() = config.oidc ?: throw ResourceNotFound("oidc")
    private val callback: URI get() = config.publicUrl.resolve("/api/v1/auth/oidc/callback")
    private val metadataMutex = Mutex()
    @Volatile private var cachedMetadata: OIDCProviderMetadata? = null
    private val validators = ConcurrentHashMap<JWSAlgorithm, IDTokenValidator>()

    suspend fun validateConfiguration() {
        if (config.oidc != null) metadata()
    }

    internal suspend fun start(): OidcStartResult {
        val metadata = metadata()
        val verifier = CodeVerifier()
        val nonce = Nonce()
        val transaction = AuthCrypto.token()
        val (state, expires) = auth.issueOidcState(
            Json.encodeToString(
                OidcChallengePayload(
                    verifier.value,
                    nonce.value,
                    AuthCrypto.hashToken(transaction).toHex(),
                )
            ),
        )
        val request = AuthenticationRequest.Builder(
            ResponseType.CODE,
            Scope(*oidc.scopes.toTypedArray()),
            ClientID(oidc.clientId),
            callback,
        )
            .endpointURI(requireNotNull(metadata.authorizationEndpointURI))
            .state(State(state))
            .nonce(nonce)
            .codeChallenge(verifier, CodeChallengeMethod.S256)
            .build()
        return OidcStartResult(request.toURI().toString(), expires, transaction)
    }

    internal suspend fun callback(
        code: String?,
        state: String?,
        providerError: String?,
        transactionToken: String?,
        clientIp: String,
    ): AuthResult {
        if (providerError != null || code.isNullOrBlank() || state.isNullOrBlank()) {
            throw InvalidCredentialsException()
        }
        val payload = runCatching {
            Json.decodeFromString<OidcChallengePayload>(auth.consumeOidcState(state))
        }.getOrElse { throw InvalidChallengeException() }
        if (!oidcTransactionMatches(payload.transactionHash, transactionToken)) {
            throw InvalidChallengeException()
        }
        val metadata = metadata()
        val grant = AuthorizationCodeGrant(
            AuthorizationCode(code),
            callback,
            CodeVerifier(payload.verifier),
        )
        val response = runCatching {
            withContext(Dispatchers.IO) {
                val request = TokenRequest.Builder(
                    requireNotNull(metadata.tokenEndpointURI),
                    ClientSecretBasic(ClientID(oidc.clientId), Secret(oidc.clientSecret)),
                    grant,
                ).build()
                OIDCTokenResponseParser.parse(request.toHTTPRequest().send())
            }
        }.getOrElse { throw InvalidCredentialsException() }
        if (!response.indicatesSuccess()) throw InvalidCredentialsException()
        val tokens = (response.toSuccessResponse() as? OIDCTokenResponse)
            ?.oidcTokens ?: throw InvalidCredentialsException()
        val idToken = tokens.idToken ?: throw InvalidCredentialsException()
        val algorithm = idToken.header.algorithm as? JWSAlgorithm ?: throw InvalidCredentialsException()
        require(algorithm.name != "none" && !algorithm.name.startsWith("HS")) {
            "OIDC ID token must use an asymmetric signature"
        }
        val validator = validators.computeIfAbsent(algorithm) {
            IDTokenValidator(
                Issuer(oidc.issuer.toString()),
                ClientID(oidc.clientId),
                algorithm,
                requireNotNull(metadata.jwkSetURI).toURL(),
            ).also { created -> created.maxClockSkew = 60 }
        }
        val claims = runCatching {
            withContext(Dispatchers.IO) {
                validator.validate(idToken, Nonce(payload.nonce))
            }
        }.getOrElse { throw InvalidCredentialsException() }
        val values = claims.toJSONObject()
        val groups = parseOidcGroups(values[oidc.groupsClaim])
        return auth.completeOidc(
            issuer = claims.issuer.value,
            subject = claims.subject.value,
            usernameClaim = (values[oidc.usernameClaim] as? String)?.boundedClaim("username"),
            displayNameClaim = (values[oidc.displayNameClaim] as? String)?.boundedClaim("display name"),
            groups = groups,
            adminMapped = groups.any(oidc.adminGroups::contains),
            clientIp = clientIp,
        )
    }

    private suspend fun metadata(): OIDCProviderMetadata {
        cachedMetadata?.let { return it }
        return metadataMutex.withLock {
            cachedMetadata ?: withContext(Dispatchers.IO) {
                OIDCProviderMetadata.resolve(Issuer(oidc.issuer.toString()), 5_000, 5_000).also {
                    require(it.issuer.value == oidc.issuer.toString()) { "OIDC discovery issuer mismatch" }
                    require(
                        it.authorizationEndpointURI != null &&
                            it.tokenEndpointURI != null &&
                            it.jwkSetURI != null
                    ) {
                        "OIDC discovery metadata is incomplete"
                    }
                }
            }.also { cachedMetadata = it }
        }
    }

    private fun String.boundedClaim(name: String): String =
        also { require(it.length <= 256) { "OIDC $name claim is too large" } }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}

internal fun parseOidcGroups(value: Any?): List<String> {
    val groups = when (value) {
        null -> emptyList()
        is String -> listOf(value)
        is List<*> -> value.map {
            it as? String ?: throw IllegalArgumentException(
                "OIDC groups claim must contain strings",
            )
        }
        else -> throw IllegalArgumentException(
            "OIDC groups claim must be a string or string array",
        )
    }
    require(groups.size <= 100) { "OIDC groups claim is too large" }
    groups.forEach {
        require(it.length <= 256) { "OIDC group claim is too large" }
    }
    return groups
}

internal fun oidcTransactionMatches(expectedHash: String, transactionToken: String?): Boolean {
    val suppliedHash = transactionToken
        ?.takeIf { it.length <= 512 }
        ?.let(AuthCrypto::hashToken)
        ?.joinToString("") { "%02x".format(it) }
        ?: return false
    return MessageDigest.isEqual(
        expectedHash.toByteArray(),
        suppliedHash.toByteArray(),
    )
}
