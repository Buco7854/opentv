package com.buco7854.opentv.contract

import kotlinx.serialization.Serializable

@Serializable
data class AuthCapabilitiesDto(
    val passwordEnabled: Boolean,
    val oidcEnabled: Boolean,
    val passkeyLoginEnabled: Boolean,
    val deviceLinkEnabled: Boolean,
    val bootstrapRequired: Boolean,
    val webAuthnRpId: String,
    val oidcStartUrl: String? = null,
)

@Serializable
data class BootstrapRequestDto(
    val token: String,
    val username: String,
    val password: String,
    val displayName: String = "",
)

@Serializable data class PasswordLoginRequestDto(val username: String, val password: String)

@Serializable
data class AuthFlowDto(
    val status: String,
    val code: String? = null,
    val challenge: String? = null,
    val methods: List<String> = emptyList(),
    val expiresAtMs: Long? = null,
    val user: CurrentUserDto? = null,
    val sessionToken: String? = null,
    val recoveryCodes: List<String> = emptyList(),
)

@Serializable data class TotpEnrollmentStartRequestDto(val challenge: String)
@Serializable class TotpAddStartRequestDto
@Serializable class TotpDeleteRequestDto

@Serializable
data class TotpEnrollmentDto(
    val challenge: String,
    val secret: String,
    val uri: String,
    val expiresAtMs: Long,
)

@Serializable
data class TotpStatusDto(
    val enrolled: Boolean,
    val confirmedAtMs: Long?,
)

@Serializable data class TotpCompleteRequestDto(val challenge: String, val code: String)
@Serializable data class RecoveryCompleteRequestDto(val challenge: String, val code: String)

@Serializable
data class CurrentUserDto(
    val id: String,
    val username: String,
    val displayName: String,
    val role: String,
    val authMethod: String,
    val clientKind: String,
    val authSessionId: String,
    val playlistIds: List<Long>,
    val hasPassword: Boolean,
)

@Serializable data class ActivationRequestDto(val token: String, val password: String)
@Serializable data class LogoutRequestDto(val all: Boolean = false)
@Serializable data class RecoveryCodesDto(val recoveryCodes: List<String>)
@Serializable data class PasswordChangeRequestDto(val password: String)

@Serializable
data class AuthSessionDto(
    val id: String,
    val authMethod: String,
    val clientKind: String,
    val deviceName: String?,
    val createdAtMs: Long,
    val lastSeenAtMs: Long,
    val idleExpiresAtMs: Long,
    val absoluteExpiresAtMs: Long,
)

@Serializable
data class AdminUserDto(
    val id: String,
    val username: String,
    val displayName: String,
    val status: String,
    val manualRole: String,
    val effectiveRole: String,
    val authMethods: List<String>,
    val playlistIds: List<Long>,
    val createdAtMs: Long,
    val lastLoginAtMs: Long?,
    val settableStatuses: List<String>,
)

@Serializable
data class CreateUserRequestDto(
    val username: String,
    val displayName: String = "",
    val role: String = "USER",
    val password: String? = null,
)

@Serializable data class CreatedUserDto(val user: AdminUserDto, val activationToken: String?)

@Serializable
data class UpdateUserRequestDto(
    val username: String? = null,
    val displayName: String? = null,
    val role: String? = null,
    val status: String? = null,
)

@Serializable data class ResetUserDto(val setupToken: String)
@Serializable data class PlaylistIdsDto(val playlistIds: List<Long>)

@Serializable
data class AdminResumeDto(
    val contentId: String,
    val title: String?,
    val positionMs: Long,
    val durationMs: Long,
    val updatedMs: Long = 0,
)

@Serializable data class OidcStartDto(val authorizationUrl: String, val expiresAtMs: Long)

@Serializable
data class PendingOidcDto(
    val issuer: String,
    val subject: String,
    val username: String?,
    val displayName: String?,
    val groups: List<String>,
    val adminMapped: Boolean,
    val createdAtMs: Long,
)

@Serializable
data class ApproveOidcRequestDto(
    val issuer: String,
    val subject: String,
    val userId: String? = null,
)

@Serializable data class WebAuthnOptionsRequestDto(val challenge: String)
@Serializable class WebAuthnLoginOptionsRequestDto
@Serializable data class WebAuthnLoginCompleteRequestDto(
    val challenge: String,
    val credential: String,
)
@Serializable data class WebAuthnCompleteRequestDto(
    val challenge: String,
    val credential: String,
    val label: String = "Security key",
)

@Serializable data class WebAuthnRpDto(val id: String, val name: String)
@Serializable data class WebAuthnUserDto(val id: String, val name: String, val displayName: String)
@Serializable data class WebAuthnAlgorithmDto(val type: String = "public-key", val alg: Int)
@Serializable data class WebAuthnDescriptorDto(
    val type: String = "public-key",
    val id: String,
    val transports: List<String> = emptyList(),
)
@Serializable data class WebAuthnSelectionDto(
    val residentKey: String = "discouraged",
    val requireResidentKey: Boolean = false,
    val userVerification: String = "discouraged",
)

@Serializable
data class WebAuthnOptionsDto(
    val challenge: String,
    val rp: WebAuthnRpDto? = null,
    val user: WebAuthnUserDto? = null,
    val rpId: String? = null,
    val pubKeyCredParams: List<WebAuthnAlgorithmDto> = emptyList(),
    val excludeCredentials: List<WebAuthnDescriptorDto> = emptyList(),
    val allowCredentials: List<WebAuthnDescriptorDto> = emptyList(),
    val authenticatorSelection: WebAuthnSelectionDto? = null,
    val timeout: Long = 300_000,
    val attestation: String? = null,
    val userVerification: String? = null,
    val serverChallenge: String,
)

@Serializable
data class WebAuthnCredentialDto(
    val id: String,
    val label: String,
    val createdAtMs: Long,
    val lastUsedAtMs: Long?,
    val backedUp: Boolean,
)

@Serializable data class WebAuthnCredentialDeleteRequestDto(val id: String)

@Serializable data class DeviceLinkStartRequestDto(val deviceName: String? = null)

@Serializable
data class DeviceLinkStartDto(
    val pollToken: String,
    val linkToken: String,
    val verificationUriComplete: String,
    val expiresAtMs: Long,
    val intervalMs: Long,
)

@Serializable data class DeviceLinkPollRequestDto(val pollToken: String)

@Serializable
data class DeviceLinkPreviewDto(
    val displayName: String,
    val username: String,
)

@Serializable
data class DeviceLinkStatusDto(
    val status: String,
    val preview: DeviceLinkPreviewDto? = null,
    val flow: AuthFlowDto? = null,
    val intervalMs: Long,
    val expiresAtMs: Long,
)

@Serializable data class DeviceLinkTokenRequestDto(val linkToken: String)

@Serializable
data class DeviceLinkLookupDto(
    val deviceName: String?,
    val userAgent: String?,
    val ip: String?,
    val requestedAtMs: Long,
    val expiresAtMs: Long,
)
