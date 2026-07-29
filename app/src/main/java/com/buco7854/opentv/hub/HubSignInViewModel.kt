package com.buco7854.opentv.hub

import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.buco7854.opentv.contract.AuthCapabilitiesDto
import com.buco7854.opentv.contract.AuthFlowDto
import com.buco7854.opentv.contract.CurrentUserDto
import com.buco7854.opentv.contract.DeviceLinkStartDto
import com.buco7854.opentv.contract.DeviceLinkStatusDto
import com.buco7854.opentv.contract.ServerInfoDto
import com.buco7854.opentv.contract.TotpEnrollmentDto
import com.buco7854.opentv.core.model.HubSource
import java.net.URI
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

interface HubAuthGateway {
    suspend fun serverInfo(baseUrl: String): ServerInfoDto
    suspend fun authCapabilities(baseUrl: String): AuthCapabilitiesDto
    suspend fun password(baseUrl: String, username: String, password: String): AuthFlowDto
    suspend fun totp(baseUrl: String, challenge: String, code: String): AuthFlowDto
    suspend fun recovery(baseUrl: String, challenge: String, code: String): AuthFlowDto
    suspend fun totpEnrollmentStart(baseUrl: String, challenge: String): TotpEnrollmentDto
    suspend fun totpEnrollmentComplete(baseUrl: String, challenge: String, code: String): AuthFlowDto
    suspend fun linkStart(baseUrl: String, deviceName: String): DeviceLinkStartDto
    suspend fun linkPoll(baseUrl: String, pollToken: String): DeviceLinkStatusDto
}

class ApiHubAuthGateway(
    private val api: HubApi,
) : HubAuthGateway {
    override suspend fun serverInfo(baseUrl: String) = api.serverInfo(baseUrl)

    override suspend fun authCapabilities(baseUrl: String): AuthCapabilitiesDto =
        api.authCapabilities(baseUrl)

    override suspend fun password(baseUrl: String, username: String, password: String) =
        api.password(baseUrl, username, password)

    override suspend fun totp(baseUrl: String, challenge: String, code: String) =
        api.totp(baseUrl, challenge, code)

    override suspend fun recovery(baseUrl: String, challenge: String, code: String) =
        api.recovery(baseUrl, challenge, code)

    override suspend fun totpEnrollmentStart(baseUrl: String, challenge: String): TotpEnrollmentDto =
        api.totpEnrollStart(baseUrl, challenge)

    override suspend fun totpEnrollmentComplete(baseUrl: String, challenge: String, code: String): AuthFlowDto =
        api.totpEnrollComplete(baseUrl, challenge, code)

    override suspend fun linkStart(baseUrl: String, deviceName: String) =
        api.linkStart(baseUrl, deviceName)

    override suspend fun linkPoll(baseUrl: String, pollToken: String) =
        api.linkPoll(baseUrl, pollToken)
}

interface HubSignInSink {
    suspend fun source(id: Long): HubSource?
    suspend fun add(name: String, baseUrl: String, token: String): Long
    suspend fun reauthenticate(id: Long, token: String): Long
    suspend fun refreshIdentity(id: Long): CurrentUserDto?
}

class RegistryHubSignInSink(
    private val registry: HubRegistry,
) : HubSignInSink {
    override suspend fun source(id: Long): HubSource? = registry.sourceFor(id)

    override suspend fun add(name: String, baseUrl: String, token: String): Long =
        registry.add(name, baseUrl, token).id

    override suspend fun reauthenticate(id: Long, token: String): Long =
        registry.reauthenticate(id, token).id

    override suspend fun refreshIdentity(id: Long): CurrentUserDto? =
        registry.refreshIdentity(id)
}

enum class DeviceLinkMode {
    LINK_THIS_DEVICE,
    BROWSER_SIGN_IN,
}

enum class DeviceLinkPhase {
    PENDING,
    SCANNED,
}

sealed interface HubSignInState {
    data class UrlEntry(
        val url: String = "",
        val error: String? = null,
    ) : HubSignInState

    data class Probing(
        val url: String,
    ) : HubSignInState

    data class ProbeFailed(
        val url: String,
        val error: String,
    ) : HubSignInState

    data class UpdateRequired(
        val url: String,
        val serverApiVersion: Int,
        val supportedApiVersion: Int,
    ) : HubSignInState

    data class MethodChooser(
        val baseUrl: String,
        val capabilities: AuthCapabilitiesDto,
        val error: String? = null,
    ) : HubSignInState {
        val passwordAvailable: Boolean
            get() = capabilities.passwordEnabled
        val deviceLinkAvailable: Boolean
            get() = capabilities.deviceLinkEnabled
        val browserSignInAvailable: Boolean
            get() = capabilities.deviceLinkEnabled &&
                (capabilities.oidcEnabled || capabilities.passkeyLoginEnabled)
    }

    data class Password(
        val username: String = "",
        val error: String? = null,
    ) : HubSignInState

    data class MfaChallenge(
        val challenge: String,
        val methods: List<String>,
        val error: String? = null,
    ) : HubSignInState {
        val recoveryAvailable: Boolean
            get() = methods.any { it.equals(RECOVERY_METHOD, ignoreCase = true) }
    }

    data class TotpEnrollment(
        val secret: String,
        val otpauthUri: String,
        val challenge: String,
        val expiresAtMs: Long,
        val error: String? = null,
    ) : HubSignInState

    data class DeviceLink(
        val verificationUri: String,
        val expiresAtMs: Long,
        val phase: DeviceLinkPhase,
        val mode: DeviceLinkMode,
        val error: String? = null,
        /**
         * Who scanned the code, once the server knows. Shown so the user can
         * refuse an approval from an account that is not theirs: whoever holds
         * the link can approve with their own account, which would otherwise
         * sign this device silently into a stranger's account.
         */
        val scannedBy: String? = null,
    ) : HubSignInState

    data class DeviceLinkDenied(
        val mode: DeviceLinkMode,
    ) : HubSignInState

    data class DeviceLinkExpired(
        val mode: DeviceLinkMode,
    ) : HubSignInState

    data object PendingApproval : HubSignInState
    data object Finishing : HubSignInState

    data class Done(
        val hubId: Long,
    ) : HubSignInState

    private companion object {
        const val RECOVERY_METHOD = "recovery"
    }
}

class HubSignInViewModel(
    private val gateway: HubAuthGateway,
    private val sink: HubSignInSink,
    private val coroutineScope: CoroutineScope? = null,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val deviceName: String = Build.MODEL?.takeIf(String::isNotBlank) ?: "Android device",
    reauthenticateHubId: Long? = null,
) : ViewModel() {
    private val mutableState = MutableStateFlow<HubSignInState>(
        if (reauthenticateHubId == null) HubSignInState.UrlEntry() else HubSignInState.Probing(""),
    )
    val state: StateFlow<HubSignInState> = mutableState.asStateFlow()

    private val workScope: CoroutineScope
        get() = coroutineScope ?: viewModelScope

    private var activeJob: Job? = null
    private var baseUrl = ""
    private var capabilities: AuthCapabilitiesDto? = null
    private var username = ""
    private var targetHubId = reauthenticateHubId

    init {
        targetHubId?.let { hubId ->
            launch {
                val source = try {
                    sink.source(hubId)
                } catch (error: Throwable) {
                    error.rethrowCancellation()
                    null
                }
                if (source == null) {
                    // The row may have been removed while this route was open.
                    // Falling back to add mode keeps removing and re-adding valid.
                    targetHubId = null
                    mutableState.value = HubSignInState.UrlEntry()
                    return@launch
                }
                probeKnown(source.baseUrl)
            }
        }
    }

    fun probe(url: String) {
        if (activeJob?.isActive == true) return
        val normalized = HubEndpoints.normalizeBaseUrl(url)
        if (normalized.isBlank()) {
            mutableState.value = HubSignInState.ProbeFailed(url, "Enter an OpenTV server address.")
            return
        }
        mutableState.value = HubSignInState.Probing(normalized)
        launch {
            probeKnown(normalized)
        }
    }

    fun retryKnownHub() {
        if (targetHubId == null || baseUrl.isBlank() || activeJob?.isActive == true) return
        launch { probeKnown(baseUrl) }
    }

    fun selectPassword() {
        val offered = capabilities ?: return
        if (!offered.passwordEnabled || activeJob?.isActive == true) return
        mutableState.value = HubSignInState.Password(username)
    }

    fun submitPassword(username: String, password: String) {
        if (mutableState.value !is HubSignInState.Password || activeJob?.isActive == true) return
        this.username = username
        mutableState.value = HubSignInState.Password(username)
        launch {
            try {
                handleAuthFlow(
                    gateway.password(baseUrl, username, password),
                    fallback = { message -> HubSignInState.Password(username, message) },
                )
            } catch (_: HubUnauthorizedException) {
                mutableState.value = HubSignInState.Password(username, "The username or password was not accepted.")
            } catch (error: Throwable) {
                error.rethrowCancellation()
                mutableState.value = HubSignInState.Password(username, safeError(error))
            }
        }
    }

    fun submitTotp(code: String) {
        val current = mutableState.value as? HubSignInState.MfaChallenge ?: return
        if (activeJob?.isActive == true) return
        launch {
            try {
                handleAuthFlow(
                    gateway.totp(baseUrl, current.challenge, code),
                    fallback = { message -> current.copy(error = message) },
                )
            } catch (error: Throwable) {
                error.rethrowCancellation()
                mutableState.value = current.copy(error = authCodeError(error))
            }
        }
    }

    fun submitRecoveryCode(code: String) {
        val current = mutableState.value as? HubSignInState.MfaChallenge ?: return
        if (!current.recoveryAvailable || activeJob?.isActive == true) return
        launch {
            try {
                handleAuthFlow(
                    gateway.recovery(baseUrl, current.challenge, code),
                    fallback = { message -> current.copy(error = message) },
                )
            } catch (error: Throwable) {
                error.rethrowCancellation()
                mutableState.value = current.copy(error = authCodeError(error))
            }
        }
    }

    fun completeTotpEnrollment(code: String) {
        val current = mutableState.value as? HubSignInState.TotpEnrollment ?: return
        if (activeJob?.isActive == true) return
        launch {
            try {
                handleAuthFlow(
                    gateway.totpEnrollmentComplete(baseUrl, current.challenge, code),
                    fallback = { message -> current.copy(error = message) },
                )
            } catch (error: Throwable) {
                error.rethrowCancellation()
                mutableState.value = current.copy(error = authCodeError(error))
            }
        }
    }

    fun startDeviceLink(mode: DeviceLinkMode = DeviceLinkMode.LINK_THIS_DEVICE) {
        val offered = capabilities ?: return
        val available = offered.deviceLinkEnabled && (
            mode == DeviceLinkMode.LINK_THIS_DEVICE ||
                offered.oidcEnabled ||
                offered.passkeyLoginEnabled
            )
        if (!available || activeJob?.isActive == true) return
        launch {
            try {
                val link = gateway.linkStart(baseUrl, deviceName)
                mutableState.value = link.toState(DeviceLinkPhase.PENDING, mode)
                pollLink(link, mode)
            } catch (error: Throwable) {
                error.rethrowCancellation()
                mutableState.value = HubSignInState.MethodChooser(baseUrl, offered, safeError(error))
            }
        }
    }

    fun restartDeviceLink() {
        val current = mutableState.value
        val mode = when (current) {
            is HubSignInState.DeviceLinkDenied -> current.mode
            is HubSignInState.DeviceLinkExpired -> current.mode
            else -> return
        }
        startDeviceLink(mode)
    }

    fun back() {
        activeJob?.cancel()
        activeJob = null
        mutableState.value = when (val current = mutableState.value) {
            is HubSignInState.UrlEntry -> current
            is HubSignInState.Probing -> HubSignInState.UrlEntry(current.url)
            is HubSignInState.ProbeFailed -> HubSignInState.UrlEntry(current.url, current.error)
            is HubSignInState.UpdateRequired -> HubSignInState.UrlEntry(current.url)
            is HubSignInState.MethodChooser -> HubSignInState.UrlEntry(current.baseUrl)
            is HubSignInState.Password -> methodChooser()
            is HubSignInState.MfaChallenge -> HubSignInState.Password(username)
            is HubSignInState.TotpEnrollment -> HubSignInState.Password(username)
            is HubSignInState.DeviceLink -> methodChooser()
            is HubSignInState.DeviceLinkDenied -> methodChooser()
            is HubSignInState.DeviceLinkExpired -> methodChooser()
            HubSignInState.PendingApproval -> HubSignInState.Password(username)
            HubSignInState.Finishing -> methodChooser()
            is HubSignInState.Done -> current
        }
    }

    fun cancel() {
        activeJob?.cancel()
        activeJob = null
        mutableState.value = HubSignInState.UrlEntry(baseUrl)
    }

    override fun onCleared() {
        activeJob?.cancel()
        super.onCleared()
    }

    private fun launch(block: suspend CoroutineScope.() -> Unit) {
        activeJob = workScope.launch(block = block)
    }

    private suspend fun probeKnown(url: String) {
        val normalized = HubEndpoints.normalizeBaseUrl(url)
        baseUrl = normalized
        mutableState.value = HubSignInState.Probing(normalized)
        try {
            val info = gateway.serverInfo(normalized)
            if (info.product != PRODUCT) {
                mutableState.value = HubSignInState.ProbeFailed(
                    normalized,
                    "This address is not an OpenTV server.",
                )
                return
            }
            if (info.apiVersion > SUPPORTED_API_VERSION) {
                mutableState.value = HubSignInState.UpdateRequired(
                    normalized,
                    info.apiVersion,
                    SUPPORTED_API_VERSION,
                )
                return
            }
            val offered = gateway.authCapabilities(normalized)
            capabilities = offered
            mutableState.value = HubSignInState.MethodChooser(normalized, offered)
        } catch (error: Throwable) {
            error.rethrowCancellation()
            mutableState.value = HubSignInState.ProbeFailed(normalized, safeError(error))
        }
    }

    private suspend fun handleAuthFlow(
        flow: AuthFlowDto,
        fallback: (String) -> HubSignInState,
    ) {
        when (flow.status.uppercase(Locale.US)) {
            STATUS_AUTHENTICATED -> {
                val token = flow.sessionToken
                if (token.isNullOrBlank()) {
                    mutableState.value = fallback("The server completed sign-in without a session.")
                } else {
                    finish(token)
                }
            }

            STATUS_MFA_REQUIRED -> {
                val challenge = flow.challenge
                mutableState.value = if (challenge.isNullOrBlank()) {
                    fallback("The server returned an incomplete sign-in challenge.")
                } else {
                    HubSignInState.MfaChallenge(challenge, flow.methods)
                }
            }

            STATUS_ENROLLMENT_REQUIRED -> {
                val challenge = flow.challenge
                if (challenge.isNullOrBlank()) {
                    mutableState.value = fallback("The server returned an incomplete enrollment challenge.")
                    return
                }
                try {
                    val enrollment = gateway.totpEnrollmentStart(baseUrl, challenge)
                    mutableState.value = HubSignInState.TotpEnrollment(
                        enrollment.secret,
                        enrollment.uri,
                        enrollment.challenge,
                        enrollment.expiresAtMs,
                    )
                } catch (error: Throwable) {
                    error.rethrowCancellation()
                    mutableState.value = fallback(safeError(error))
                }
            }

            STATUS_PENDING_APPROVAL -> mutableState.value = HubSignInState.PendingApproval
            else -> mutableState.value = fallback("The server returned an unsupported sign-in response.")
        }
    }

    private suspend fun finish(token: String) {
        mutableState.value = HubSignInState.Finishing
        val id = try {
            targetHubId?.let { sink.reauthenticate(it, token) }
                ?: sink.add(defaultHubName(baseUrl), baseUrl, token)
        } catch (error: Throwable) {
            error.rethrowCancellation()
            val message = "Sign-in succeeded, but the hub could not be saved."
            mutableState.value = if (targetHubId == null) {
                HubSignInState.UrlEntry(baseUrl, message)
            } else {
                methodChooser().let { state ->
                    (state as? HubSignInState.MethodChooser)?.copy(error = message) ?: state
                }
            }
            return
        }
        try {
            sink.refreshIdentity(id)
        } catch (error: Throwable) {
            error.rethrowCancellation()
        }
        mutableState.value = HubSignInState.Done(id)
    }

    private suspend fun pollLink(start: DeviceLinkStartDto, mode: DeviceLinkMode) {
        var intervalMs = start.intervalMs.coerceAtLeast(0)
        var expiresAtMs = start.expiresAtMs
        while (true) {
            val remainingMs = expiresAtMs - nowMs()
            if (remainingMs <= 0) {
                mutableState.value = HubSignInState.DeviceLinkExpired(mode)
                return
            }
            delay(minOf(intervalMs, remainingMs))
            if (nowMs() >= expiresAtMs) {
                mutableState.value = HubSignInState.DeviceLinkExpired(mode)
                return
            }
            val status = try {
                gateway.linkPoll(baseUrl, start.pollToken)
            } catch (capacity: HubCapacityException) {
                intervalMs = (capacity.retryAfterMs ?: intervalMs).coerceAtLeast(0)
                continue
            } catch (error: Throwable) {
                error.rethrowCancellation()
                mutableState.value = start.toState(
                    phase = (mutableState.value as? HubSignInState.DeviceLink)?.phase
                        ?: DeviceLinkPhase.PENDING,
                    mode = mode,
                    error = safeError(error),
                )
                continue
            }
            intervalMs = status.intervalMs.coerceAtLeast(0)
            expiresAtMs = status.expiresAtMs
            when (status.status.uppercase(Locale.US)) {
                STATUS_PENDING -> mutableState.value = start.toState(
                    DeviceLinkPhase.PENDING,
                    mode,
                    expiresAtMs = expiresAtMs,
                )

                STATUS_SCANNED -> mutableState.value = start.toState(
                    DeviceLinkPhase.SCANNED,
                    mode,
                    expiresAtMs = expiresAtMs,
                    scannedBy = status.preview
                        ?.let { it.displayName.ifBlank { it.username } }
                        ?.takeIf(String::isNotBlank),
                )

                STATUS_APPROVED -> {
                    val flow = status.flow
                    if (flow == null) {
                        mutableState.value = start.toState(
                            DeviceLinkPhase.SCANNED,
                            mode,
                            "The hub approved this device without completing sign-in.",
                            expiresAtMs,
                        )
                    } else {
                        handleAuthFlow(
                            flow,
                            fallback = { message ->
                                start.toState(
                                    DeviceLinkPhase.SCANNED,
                                    mode,
                                    message,
                                    expiresAtMs,
                                )
                            },
                        )
                        return
                    }
                }

                STATUS_DENIED -> {
                    mutableState.value = HubSignInState.DeviceLinkDenied(mode)
                    return
                }

                STATUS_EXPIRED -> {
                    mutableState.value = HubSignInState.DeviceLinkExpired(mode)
                    return
                }

                else -> mutableState.value = start.toState(
                    DeviceLinkPhase.PENDING,
                    mode,
                    "The hub returned an unsupported device-link response.",
                    expiresAtMs,
                )
            }
        }
    }

    private fun DeviceLinkStartDto.toState(
        phase: DeviceLinkPhase,
        mode: DeviceLinkMode,
        error: String? = null,
        expiresAtMs: Long = this.expiresAtMs,
        scannedBy: String? = null,
    ) = HubSignInState.DeviceLink(
        verificationUri = verificationUriComplete,
        expiresAtMs = expiresAtMs,
        phase = phase,
        mode = mode,
        error = error,
        scannedBy = scannedBy,
    )

    private fun methodChooser(): HubSignInState =
        capabilities?.let { HubSignInState.MethodChooser(baseUrl, it) }
            ?: HubSignInState.UrlEntry(baseUrl)

    private fun authCodeError(error: Throwable): String =
        if (error is HubUnauthorizedException) {
            "The code was not accepted."
        } else {
            safeError(error)
        }

    private fun safeError(error: Throwable): String = when (error) {
        is HubUnreachableException -> "Could not reach this OpenTV server."
        is HubCapacityException -> "Too many attempts. Try again shortly."
        is NotImplementedError -> "This app build does not support this sign-in step yet."
        is HubException -> "The OpenTV server could not complete the request."
        else -> "Something went wrong. Try again."
    }

    private fun defaultHubName(url: String): String =
        runCatching { URI(url).host }
            .getOrNull()
            ?.takeIf(String::isNotBlank)
            ?: "OpenTV hub"

    private fun Throwable.rethrowCancellation() {
        if (this is CancellationException) throw this
    }

    companion object {
        const val SUPPORTED_API_VERSION = 1

        private const val PRODUCT = "opentv"
        private const val STATUS_AUTHENTICATED = "AUTHENTICATED"
        private const val STATUS_MFA_REQUIRED = "MFA_REQUIRED"
        private const val STATUS_ENROLLMENT_REQUIRED = "ENROLLMENT_REQUIRED"
        private const val STATUS_PENDING_APPROVAL = "PENDING_APPROVAL"
        private const val STATUS_PENDING = "PENDING"
        private const val STATUS_SCANNED = "SCANNED"
        private const val STATUS_APPROVED = "APPROVED"
        private const val STATUS_DENIED = "DENIED"
        private const val STATUS_EXPIRED = "EXPIRED"
    }
}
