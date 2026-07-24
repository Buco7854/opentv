package com.buco7854.opentv.server

import com.buco7854.opentv.serverdata.ChallengeKind
import com.buco7854.opentv.serverdata.db.ServerUserDatabase
import com.buco7854.opentv.serverdata.db.WebAuthnCredentialRow
import com.webauthn4j.WebAuthnManager
import com.webauthn4j.converter.util.ObjectConverter
import com.webauthn4j.credential.CredentialRecordImpl
import com.webauthn4j.data.AuthenticationParameters
import com.webauthn4j.data.AuthenticatorTransport
import com.webauthn4j.data.RegistrationParameters
import com.webauthn4j.data.attestation.authenticator.AAGUID
import com.webauthn4j.data.attestation.authenticator.AttestedCredentialData
import com.webauthn4j.data.attestation.authenticator.COSEKey
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
import java.util.Base64

@Serializable
private data class WebAuthnChallengePayload(
    val parentId: String,
    val browserChallenge: String,
)

/** WebAuthn second-factor ceremonies verified by WebAuthn4J. */
class WebAuthnService(
    private val db: ServerUserDatabase,
    private val auth: AuthService,
    private val config: AuthConfig,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val converter = ObjectConverter()
    private val manager = WebAuthnManager.createNonStrictWebAuthnManager(converter)
    private val b64 = Base64.getUrlEncoder().withoutPadding()

    suspend fun registrationOptions(
        request: WebAuthnOptionsRequestDto,
        clientIp: String,
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
            return registrationOptions(parent, user).also {
                auth.clearFlowLimit(clientIp, "webauthn-options", request.challenge)
            }
        } catch (error: Exception) {
            auth.failFlowLimit(clientIp, "webauthn-options", request.challenge)
            throw error
        }
    }

    suspend fun additionalRegistrationOptions(actor: Actor): WebAuthnOptionsDto {
        val raw = auth.recentMfaChallenge(actor)
        val parent = auth.mfaChallenge(raw)
        val user = db.users().get(actor.userId) ?: throw UnauthenticatedApiException()
        return registrationOptions(parent, user)
    }

    private suspend fun registrationOptions(
        parent: com.buco7854.opentv.serverdata.db.AuthChallengeRow,
        user: com.buco7854.opentv.serverdata.db.UserRow,
    ): WebAuthnOptionsDto {
        val browserChallenge = AuthCrypto.randomBytes(32)
        val encoded = b64.encodeToString(browserChallenge)
        val issued = auth.issueWebAuthnChallenge(
            user.id,
            ChallengeKind.WEBAUTHN_REGISTER,
            Json.encodeToString(WebAuthnChallengePayload(parent.id, encoded)),
        )
        return WebAuthnOptionsDto(
            challenge = encoded,
            rp = WebAuthnRpDto(config.webAuthnRpId, "OpenTV"),
            user = WebAuthnUserDto(
                b64.encodeToString(user.id.toByteArray()),
                user.username,
                user.displayName,
            ),
            pubKeyCredParams = listOf(
                WebAuthnAlgorithmDto(alg = -7),
                WebAuthnAlgorithmDto(alg = -257),
                WebAuthnAlgorithmDto(alg = -8),
            ),
            excludeCredentials = db.credentials().webAuthn(user.id).map(::descriptor),
            authenticatorSelection = WebAuthnSelectionDto(),
            attestation = "none",
            serverChallenge = issued.first,
        )
    }

    suspend fun authenticationOptions(
        request: WebAuthnOptionsRequestDto,
        clientIp: String,
    ): WebAuthnOptionsDto {
        auth.checkFlowLimit(clientIp, "webauthn-options", request.challenge)
        try {
            val parent = auth.mfaChallenge(request.challenge)
            val userId = parent.userId ?: throw InvalidChallengeException()
            val credentials = db.credentials().webAuthn(userId)
            if (credentials.isEmpty()) throw InvalidChallengeException()
            val browserChallenge = AuthCrypto.randomBytes(32)
            val encoded = b64.encodeToString(browserChallenge)
            val issued = auth.issueWebAuthnChallenge(
                userId,
                ChallengeKind.WEBAUTHN_AUTHENTICATE,
                Json.encodeToString(WebAuthnChallengePayload(parent.id, encoded)),
            )
            return WebAuthnOptionsDto(
                challenge = encoded,
                rpId = config.webAuthnRpId,
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
            val data = runCatching {
                manager.verifyRegistrationResponseJSON(
                    request.credential,
                    RegistrationParameters(
                        webAuthnServerProperty(config, payload.browserChallenge),
                        emptyList(),
                        false,
                        true,
                    ),
                )
            }.getOrElse { throw InvalidCredentialsException() }
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
                clientIp,
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
    ): AuthResult {
        require(request.challenge.length <= 512) { "WebAuthn challenge is too large" }
        require(request.credential.length <= 65_536) { "WebAuthn response is too large" }
        auth.checkFlowLimit(clientIp, "webauthn-authenticate", request.challenge)
        return try {
            val challenge = auth.webAuthnChallenge(
                ChallengeKind.WEBAUTHN_AUTHENTICATE, request.challenge,
            )
            val payload = Json.decodeFromString<WebAuthnChallengePayload>(challenge.payloadJson)
            val parsed = runCatching { manager.parseAuthenticationResponseJSON(request.credential) }
                .getOrElse { throw InvalidCredentialsException() }
            val row = db.credentials().webAuthnById(parsed.credentialId)
                ?: throw InvalidCredentialsException()
            if (row.userId != challenge.userId) throw InvalidCredentialsException()
            val record = credentialRecord(row)
            val verified = runCatching {
                manager.verify(
                    parsed,
                    AuthenticationParameters(
                        webAuthnServerProperty(config, payload.browserChallenge),
                        record,
                        listOf(row.credentialId),
                        false,
                        true,
                    ),
                )
            }.getOrElse { throw InvalidCredentialsException() }
            val verifiedAuthenticator = verified.authenticatorData
                ?: throw InvalidCredentialsException()
            val nextCounter = verifiedAuthenticator.signCount
            if (row.signCount > 0 && nextCounter <= row.signCount) {
                throw InvalidCredentialsException()
            }
            auth.finishWebAuthn(
                challenge.id,
                payload.parentId,
                row.copy(
                    signCount = nextCounter,
                    backupEligible = verifiedAuthenticator.isFlagBE,
                    backedUp = verifiedAuthenticator.isFlagBS,
                    lastUsedAtMs = clock(),
                ),
                enrollment = false,
                clientIp,
            ).also {
                auth.clearFlowLimit(clientIp, "webauthn-authenticate", request.challenge)
            }
        } catch (error: Exception) {
            auth.failFlowLimit(clientIp, "webauthn-authenticate", request.challenge)
            throw error
        }
    }

    private fun descriptor(row: WebAuthnCredentialRow) = WebAuthnDescriptorDto(
        id = b64.encodeToString(row.credentialId),
        transports = runCatching {
            Json.decodeFromString<List<String>>(row.transportsJson)
        }.getOrDefault(emptyList()),
    )

    @Suppress("UNCHECKED_CAST")
    private fun credentialRecord(row: WebAuthnCredentialRow): CredentialRecordImpl {
        val cose = converter.cborMapper.readValue(row.publicKeyCose, COSEKey::class.java)
            ?: throw IllegalStateException("Stored WebAuthn public key is invalid")
        val attested = AttestedCredentialData(AAGUID.ZERO, row.credentialId, cose)
        val transports = runCatching {
            Json.decodeFromString<List<String>>(row.transportsJson)
        }.getOrDefault(emptyList()).map(AuthenticatorTransport::create).toSet()
        val clientData = CollectedClientData(
            ClientDataType.WEBAUTHN_CREATE,
            DefaultChallenge(ByteArray(0)),
            Origin(config.webAuthnOrigin),
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

internal fun webAuthnServerProperty(
    config: AuthConfig,
    encodedChallenge: String,
): ServerProperty = ServerProperty.builder()
    .origin(Origin(config.webAuthnOrigin))
    .rpId(config.webAuthnRpId)
    .challenge(DefaultChallenge(Base64.getUrlDecoder().decode(encodedChallenge)))
    .build()
