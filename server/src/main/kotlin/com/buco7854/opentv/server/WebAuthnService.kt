package com.buco7854.opentv.server

import com.buco7854.opentv.contract.*
import com.buco7854.opentv.serverdata.ChallengeKind
import com.buco7854.opentv.serverdata.ClientKind
import com.buco7854.opentv.serverdata.UserStatus
import com.buco7854.opentv.serverdata.db.OpenTvServerDatabase
import com.buco7854.opentv.serverdata.db.WebAuthnCredentialRow
import com.webauthn4j.WebAuthnManager
import com.webauthn4j.converter.util.ObjectConverter
import com.webauthn4j.credential.CredentialRecord
import com.webauthn4j.credential.CredentialRecordImpl
import com.webauthn4j.data.AuthenticationParameters
import com.webauthn4j.data.AuthenticatorTransport
import com.webauthn4j.data.PublicKeyCredentialParameters
import com.webauthn4j.data.PublicKeyCredentialType
import com.webauthn4j.data.RegistrationParameters
import com.webauthn4j.data.attestation.authenticator.AAGUID
import com.webauthn4j.data.attestation.authenticator.AttestedCredentialData
import com.webauthn4j.data.attestation.authenticator.COSEKey
import com.webauthn4j.data.attestation.statement.COSEAlgorithmIdentifier
import com.webauthn4j.data.attestation.statement.NoneAttestationStatement
import com.webauthn4j.data.client.ClientDataType
import com.webauthn4j.data.client.CollectedClientData
import com.webauthn4j.data.client.Origin
import com.webauthn4j.data.client.challenge.DefaultChallenge
import com.webauthn4j.data.extension.authenticator.AuthenticationExtensionsAuthenticatorOutputs
import com.webauthn4j.data.extension.client.AuthenticationExtensionsClientOutputs
import com.webauthn4j.server.ServerProperty
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import java.util.Base64
import org.slf4j.LoggerFactory

/**
 * The relying party is stored with the challenge, not recomputed at verification: a
 * ceremony must be verified under the exact party the browser signed for, and when the
 * party follows the requested address (see [PublicOrigin]) that is not guaranteed to be
 * whatever the completing request happens to carry.
 */
@Serializable
private data class WebAuthnChallengePayload(
    val parentId: String,
    val browserChallenge: String,
    val rpId: String = "",
    val origin: String = "",
)

@Serializable
private data class WebAuthnLoginChallengePayload(
    val browserChallenge: String,
    val rpId: String = "",
    val origin: String = "",
)

internal data class VerifiedWebAuthnAssertion(
    val credential: WebAuthnCredentialRow,
    val userHandle: ByteArray?,
    val signCount: Long,
    val backupEligible: Boolean,
    val backedUp: Boolean,
)

internal interface WebAuthnAssertionVerifier {
    suspend fun verify(
        credentialJson: String,
        browserChallenge: String,
        userVerificationRequired: Boolean,
        credentialLookup: suspend (ByteArray) -> WebAuthnCredentialRow?,
    ): VerifiedWebAuthnAssertion
}

/** WebAuthn registration, second-factor, and primary-login ceremonies verified by WebAuthn4J. */
class WebAuthnService private constructor(
    private val db: OpenTvServerDatabase,
    private val auth: AuthService,
    private val config: AuthConfig,
    private val clock: () -> Long,
    private val assertionVerifier: WebAuthnAssertionVerifier?,
) {
    constructor(
        db: OpenTvServerDatabase,
        auth: AuthService,
        config: AuthConfig,
        clock: () -> Long = System::currentTimeMillis,
    ) : this(db, auth, config, clock, null)

    internal constructor(
        db: OpenTvServerDatabase,
        auth: AuthService,
        config: AuthConfig,
        verifier: WebAuthnAssertionVerifier,
        clock: () -> Long = System::currentTimeMillis,
    ) : this(db, auth, config, clock, verifier)

    private val converter = ObjectConverter()
    private val manager = WebAuthnManager.createNonStrictWebAuthnManager(converter)
    private val b64 = Base64.getUrlEncoder().withoutPadding()
    private val log = LoggerFactory.getLogger(WebAuthnService::class.java)

    internal suspend fun registrationOptions(
        request: WebAuthnOptionsRequestDto,
        clientIp: String,
        relyingParty: WebAuthnRelyingParty = config.pinnedRelyingParty(),
    ): WebAuthnOptionsDto {
        auth.checkFlowLimit(clientIp, "webauthn-options", request.challenge)
        try {
            val parent = auth.mfaChallenge(request.challenge)
            val user = parent.userId?.let { db.users().get(it) } ?: throw InvalidChallengeException()
            if (parent.payloadJson.isBlank() &&
                (db.credentials().confirmedTotp(user.id).isNotEmpty() ||
                    db.credentials().webAuthn(user.id).isNotEmpty())
            ) {
                throw ForbiddenApiException()
            }
            return registrationOptions(parent, user, relyingParty).also {
                auth.clearFlowLimit(clientIp, "webauthn-options", request.challenge)
            }
        } catch (error: Exception) {
            auth.failFlowLimit(clientIp, "webauthn-options", request.challenge)
            throw error
        }
    }

    internal suspend fun additionalRegistrationOptions(
        actor: Actor,
        relyingParty: WebAuthnRelyingParty = config.pinnedRelyingParty(),
    ): WebAuthnOptionsDto {
        val raw = auth.reauthenticationChallenge(actor)
        val parent = auth.mfaChallenge(raw)
        val user = db.users().get(actor.userId) ?: throw UnauthenticatedApiException()
        return registrationOptions(parent, user, relyingParty)
    }

    private suspend fun registrationOptions(
        parent: com.buco7854.opentv.serverdata.db.AuthChallengeRow,
        user: com.buco7854.opentv.serverdata.db.UserRow,
        relyingParty: WebAuthnRelyingParty,
    ): WebAuthnOptionsDto {
        relyingParty.require()
        val browserChallenge = AuthCrypto.randomBytes(32)
        val encoded = b64.encodeToString(browserChallenge)
        val issued = auth.issueWebAuthnChallenge(
            user.id,
            ChallengeKind.WEBAUTHN_REGISTER,
            Json.encodeToString(
                WebAuthnChallengePayload(
                    parent.id, encoded, relyingParty.rpId, relyingParty.origin,
                )
            ),
        )
        return WebAuthnOptionsDto(
            challenge = encoded,
            rp = WebAuthnRpDto(relyingParty.rpId, "OpenTV"),
            user = WebAuthnUserDto(
                b64.encodeToString(user.id.toByteArray(Charsets.UTF_8)),
                user.username,
                user.displayName,
            ),
            pubKeyCredParams = webAuthnAlgorithmDtos(),
            excludeCredentials = db.credentials().webAuthn(user.id).map(::descriptor),
            authenticatorSelection = WebAuthnSelectionDto(
                residentKey = "preferred",
                userVerification = "preferred",
            ),
            attestation = "none",
            serverChallenge = issued.first,
        )
    }

    internal suspend fun loginOptions(
        @Suppress("UNUSED_PARAMETER") request: WebAuthnLoginOptionsRequestDto,
        clientIp: String,
        relyingParty: WebAuthnRelyingParty = config.pinnedRelyingParty(),
    ): WebAuthnOptionsDto {
        relyingParty.require()
        val browserChallenge = b64.encodeToString(AuthCrypto.randomBytes(32))
        val issued = auth.issueWebAuthnLoginChallenge(
            Json.encodeToString(
                WebAuthnLoginChallengePayload(
                    browserChallenge, relyingParty.rpId, relyingParty.origin,
                )
            ),
            clientIp,
        )
        return WebAuthnOptionsDto(
            challenge = browserChallenge,
            rpId = relyingParty.rpId,
            allowCredentials = emptyList(),
            userVerification = "required",
            serverChallenge = issued.first,
        )
    }

    internal suspend fun authenticationOptions(
        request: WebAuthnOptionsRequestDto,
        clientIp: String,
        relyingParty: WebAuthnRelyingParty = config.pinnedRelyingParty(),
    ): WebAuthnOptionsDto {
        auth.checkFlowLimit(clientIp, "webauthn-options", request.challenge)
        try {
            relyingParty.require()
            val parent = auth.mfaChallenge(request.challenge)
            val userId = parent.userId ?: throw InvalidChallengeException()
            val credentials = db.credentials().webAuthn(userId)
            if (credentials.isEmpty()) throw InvalidChallengeException()
            val browserChallenge = AuthCrypto.randomBytes(32)
            val encoded = b64.encodeToString(browserChallenge)
            val issued = auth.issueWebAuthnChallenge(
                userId,
                ChallengeKind.WEBAUTHN_AUTHENTICATE,
                Json.encodeToString(
                    WebAuthnChallengePayload(
                        parent.id, encoded, relyingParty.rpId, relyingParty.origin,
                    )
                ),
            )
            return WebAuthnOptionsDto(
                challenge = encoded,
                rpId = relyingParty.rpId,
                allowCredentials = credentials.map(::descriptor),
                userVerification = "discouraged",
                serverChallenge = issued.first,
            ).also {
                auth.clearFlowLimit(clientIp, "webauthn-options", request.challenge)
            }
        } catch (error: Exception) {
            auth.failFlowLimit(clientIp, "webauthn-options", request.challenge)
            throw error
        }
    }

    internal suspend fun completeRegistration(
        request: WebAuthnCompleteRequestDto,
        clientIp: String,
        clientKind: String = ClientKind.BROWSER,
    ): AuthResult {
        require(request.challenge.length <= 512) { "WebAuthn challenge is too large" }
        require(request.credential.length <= 65_536) { "WebAuthn response is too large" }
        require(request.label.codePointCount(0, request.label.length) <= 100) {
            "WebAuthn label is too long"
        }
        auth.checkFlowLimit(clientIp, "webauthn-register", request.challenge)
        return try {
            val challenge = auth.webAuthnChallenge(
                ChallengeKind.WEBAUTHN_REGISTER, request.challenge,
            )
            val payload = Json.decodeFromString<WebAuthnChallengePayload>(challenge.payloadJson)
            val data = try {
                manager.verifyRegistrationResponseJSON(
                    request.credential,
                    webAuthnRegistrationParameters(
                        payload.relyingParty(config),
                        payload.browserChallenge,
                    ),
                )
            } catch (error: Exception) {
                log.warn("WebAuthn registration verification failed", error)
                throw InvalidCredentialsException()
            }
            val authenticatorData = data.attestationObject?.authenticatorData
                ?: throw InvalidCredentialsException()
            val attested = authenticatorData.attestedCredentialData
                ?: throw InvalidCredentialsException()
            if (db.credentials().webAuthnById(attested.credentialId) != null) {
                throw InvalidCredentialsException()
            }
            val userId = challenge.userId ?: throw InvalidChallengeException()
            val row = WebAuthnCredentialRow(
                credentialId = attested.credentialId,
                userId = userId,
                publicKeyCose = converter.cborMapper.writeValueAsBytes(attested.coseKey),
                signCount = authenticatorData.signCount,
                transportsJson = Json.encodeToString(data.transports.orEmpty().map { it.value }),
                backupEligible = authenticatorData.isFlagBE,
                backedUp = authenticatorData.isFlagBS,
                label = request.label.trim().ifBlank { "Security key" },
                createdAtMs = clock(),
                lastUsedAtMs = null,
            )
            auth.finishWebAuthn(
                challenge.id,
                payload.parentId,
                row,
                enrollment = true,
                clientKind = clientKind,
            ).also {
                auth.clearFlowLimit(clientIp, "webauthn-register", request.challenge)
            }
        } catch (error: Exception) {
            auth.failFlowLimit(clientIp, "webauthn-register", request.challenge)
            throw error
        }
    }

    internal suspend fun completeAuthentication(
        request: WebAuthnCompleteRequestDto,
        clientIp: String,
        clientKind: String = ClientKind.BROWSER,
    ): AuthResult {
        require(request.challenge.length <= 512) { "WebAuthn challenge is too large" }
        require(request.credential.length <= 65_536) { "WebAuthn response is too large" }
        auth.checkFlowLimit(clientIp, "webauthn-authenticate", request.challenge)
        return try {
            val challenge = auth.webAuthnChallenge(
                ChallengeKind.WEBAUTHN_AUTHENTICATE, request.challenge,
            )
            val payload = Json.decodeFromString<WebAuthnChallengePayload>(challenge.payloadJson)
            val verified = verifyAssertion(
                request.credential,
                payload.browserChallenge,
                userVerificationRequired = false,
                relyingParty = payload.relyingParty(config),
            )
            val row = verified.credential
            if (row.userId != challenge.userId) throw InvalidCredentialsException()
            val nextCounter = verified.signCount
            if (row.signCount > 0 && nextCounter <= row.signCount) {
                throw InvalidCredentialsException()
            }
            auth.finishWebAuthn(
                challenge.id,
                payload.parentId,
                row.copy(
                    signCount = nextCounter,
                    backupEligible = verified.backupEligible,
                    backedUp = verified.backedUp,
                    lastUsedAtMs = clock(),
                ),
                enrollment = false,
                clientKind = clientKind,
            ).also {
                auth.clearFlowLimit(clientIp, "webauthn-authenticate", request.challenge)
            }
        } catch (error: Exception) {
            auth.failFlowLimit(clientIp, "webauthn-authenticate", request.challenge)
            throw error
        }
    }

    internal suspend fun completeLogin(
        request: WebAuthnLoginCompleteRequestDto,
        clientIp: String,
        clientKind: String = ClientKind.BROWSER,
    ): AuthResult {
        require(request.challenge.length <= 512) { "WebAuthn challenge is too large" }
        require(request.credential.length <= 65_536) { "WebAuthn response is too large" }
        auth.checkFlowLimit(clientIp, "webauthn-login", request.challenge)
        return try {
            val challenge = auth.webAuthnChallenge(
                ChallengeKind.WEBAUTHN_LOGIN,
                request.challenge,
            )
            if (challenge.userId != null) throw InvalidChallengeException()
            val payload = Json.decodeFromString<WebAuthnLoginChallengePayload>(
                challenge.payloadJson,
            )
            val verified = verifyAssertion(
                request.credential,
                payload.browserChallenge,
                userVerificationRequired = true,
                relyingParty = payload.relyingParty(config),
            )
            val row = verified.credential
            val user = db.users().get(row.userId)
                ?.takeIf { it.status == UserStatus.ACTIVE }
                ?: throw InvalidCredentialsException()
            verified.userHandle?.let {
                if (!MessageDigest.isEqual(it, user.id.toByteArray(Charsets.UTF_8))) {
                    throw InvalidCredentialsException()
                }
            }
            if (row.signCount > 0 && verified.signCount <= row.signCount) {
                throw InvalidCredentialsException()
            }
            auth.finishWebAuthnLogin(
                challenge.id,
                row.copy(
                    signCount = verified.signCount,
                    backupEligible = verified.backupEligible,
                    backedUp = verified.backedUp,
                    lastUsedAtMs = clock(),
                ),
                clientKind,
            ).also {
                auth.clearFlowLimit(clientIp, "webauthn-login", request.challenge)
            }
        } catch (error: Exception) {
            auth.failFlowLimit(clientIp, "webauthn-login", request.challenge)
            throw error
        }
    }

    internal suspend fun credentials(actor: Actor): List<WebAuthnCredentialDto> =
        auth.webAuthnCredentials(actor).map {
            WebAuthnCredentialDto(
                id = b64.encodeToString(it.credentialId),
                label = it.label,
                createdAtMs = it.createdAtMs,
                lastUsedAtMs = it.lastUsedAtMs,
                backedUp = it.backedUp,
            )
        }

    internal suspend fun deleteCredential(
        actor: Actor,
        request: WebAuthnCredentialDeleteRequestDto,
    ): AuthResult {
        require(request.id.isNotBlank() && request.id.length <= 2_048) {
            "Invalid WebAuthn credential id"
        }
        val credentialId = runCatching { Base64.getUrlDecoder().decode(request.id) }
            .getOrElse { throw IllegalArgumentException("Invalid WebAuthn credential id") }
        require(credentialId.isNotEmpty()) { "Invalid WebAuthn credential id" }
        return auth.deleteWebAuthnCredential(actor, credentialId)
    }

    private suspend fun verifyAssertion(
        credentialJson: String,
        browserChallenge: String,
        userVerificationRequired: Boolean,
        relyingParty: WebAuthnRelyingParty,
    ): VerifiedWebAuthnAssertion {
        assertionVerifier?.let {
            return it.verify(
                credentialJson,
                browserChallenge,
                userVerificationRequired,
                db.credentials()::webAuthnById,
            )
        }
        val parsed = try {
            manager.parseAuthenticationResponseJSON(credentialJson)
        } catch (error: Exception) {
            log.debug("WebAuthn authentication response parsing failed", error)
            throw InvalidCredentialsException()
        }
        val row = db.credentials().webAuthnById(parsed.credentialId)
            ?: throw InvalidCredentialsException()
        val record = credentialRecord(row, relyingParty)
        val verified = try {
            manager.verify(
                parsed,
                webAuthnAuthenticationParameters(
                    relyingParty,
                    browserChallenge,
                    record,
                    row.credentialId,
                    userVerificationRequired,
                ),
            )
        } catch (error: Exception) {
            log.debug("WebAuthn authentication verification failed", error)
            throw InvalidCredentialsException()
        }
        val authenticator = verified.authenticatorData ?: throw InvalidCredentialsException()
        return VerifiedWebAuthnAssertion(
            credential = row,
            userHandle = parsed.userHandle,
            signCount = authenticator.signCount,
            backupEligible = authenticator.isFlagBE,
            backedUp = authenticator.isFlagBS,
        )
    }

    private fun descriptor(row: WebAuthnCredentialRow) = WebAuthnDescriptorDto(
        id = b64.encodeToString(row.credentialId),
        transports = runCatching {
            Json.decodeFromString<List<String>>(row.transportsJson)
        }.getOrDefault(emptyList()),
    )

    @Suppress("UNCHECKED_CAST")
    private fun credentialRecord(
        row: WebAuthnCredentialRow,
        relyingParty: WebAuthnRelyingParty,
    ): CredentialRecordImpl {
        val cose = converter.cborMapper.readValue(row.publicKeyCose, COSEKey::class.java)
            ?: throw IllegalStateException("Stored WebAuthn public key is invalid")
        val attested = AttestedCredentialData(AAGUID.ZERO, row.credentialId, cose)
        val transports = runCatching {
            Json.decodeFromString<List<String>>(row.transportsJson)
        }.getOrDefault(emptyList()).map(AuthenticatorTransport::create).toSet()
        val clientData = CollectedClientData(
            ClientDataType.WEBAUTHN_CREATE,
            DefaultChallenge(ByteArray(0)),
            Origin(relyingParty.origin),
            null,
        )
        return CredentialRecordImpl(
            NoneAttestationStatement(),
            false,
            row.backupEligible,
            row.backedUp,
            row.signCount,
            attested,
            AuthenticationExtensionsAuthenticatorOutputs(),
            clientData,
            AuthenticationExtensionsClientOutputs(),
            transports,
        )
    }
}

/** The relying party `OPENTV_WEBAUTHN_*` (or the public URL) pins, when it pins one. */
internal fun AuthConfig.pinnedRelyingParty() =
    WebAuthnRelyingParty(webAuthnRpId, webAuthnOrigin)

private fun WebAuthnChallengePayload.relyingParty(config: AuthConfig) =
    if (rpId.isBlank() || origin.isBlank()) config.pinnedRelyingParty()
    else WebAuthnRelyingParty(rpId, origin)

private fun WebAuthnLoginChallengePayload.relyingParty(config: AuthConfig) =
    if (rpId.isBlank() || origin.isBlank()) config.pinnedRelyingParty()
    else WebAuthnRelyingParty(rpId, origin)

/** A ceremony no browser would complete is refused here, where it can be explained. */
private fun WebAuthnRelyingParty.require() {
    if (!usable) throw WebAuthnUnavailableException(origin)
}

internal fun webAuthnServerProperty(
    relyingParty: WebAuthnRelyingParty,
    encodedChallenge: String,
): ServerProperty = ServerProperty.builder()
    .origin(Origin(relyingParty.origin))
    .rpId(relyingParty.rpId)
    .challenge(DefaultChallenge(Base64.getUrlDecoder().decode(encodedChallenge)))
    .build()

private val WEB_AUTHN_ALGORITHMS = listOf(
    COSEAlgorithmIdentifier.ES256,
    COSEAlgorithmIdentifier.RS256,
    COSEAlgorithmIdentifier.EdDSA,
)

internal fun webAuthnAlgorithmDtos(): List<WebAuthnAlgorithmDto> =
    WEB_AUTHN_ALGORITHMS.map { WebAuthnAlgorithmDto(alg = it.value.toInt()) }

internal fun webAuthnRegistrationParameters(
    relyingParty: WebAuthnRelyingParty,
    encodedChallenge: String,
) = RegistrationParameters(
    webAuthnServerProperty(relyingParty, encodedChallenge),
    WEB_AUTHN_ALGORITHMS.map {
        PublicKeyCredentialParameters(PublicKeyCredentialType.PUBLIC_KEY, it)
    },
    false,
    true,
)

internal fun webAuthnAuthenticationParameters(
    relyingParty: WebAuthnRelyingParty,
    encodedChallenge: String,
    credential: CredentialRecord,
    credentialId: ByteArray,
    userVerificationRequired: Boolean,
) = AuthenticationParameters(
    webAuthnServerProperty(relyingParty, encodedChallenge),
    credential,
    listOf(credentialId),
    userVerificationRequired,
    true,
)
