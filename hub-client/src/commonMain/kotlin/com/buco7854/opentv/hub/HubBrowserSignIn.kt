package com.buco7854.opentv.hub

import com.buco7854.opentv.contract.CurrentUserDto
import com.buco7854.opentv.contract.DeviceLinkPreviewDto
import com.buco7854.opentv.core.net.Urls
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Opaque, process-local key for one browser sign-in. It contains no server secret and is
 * useful only with the [HubBrowserSignIn] instance that created it.
 */
class HubBrowserSignInHandle internal constructor(
    internal val owner: Any,
    internal val id: Long,
) {
    override fun equals(other: Any?): Boolean =
        other is HubBrowserSignInHandle && other.owner === owner && other.id == id

    override fun hashCode(): Int = 31 * owner.hashCode() + id.hashCode()

    override fun toString(): String = "HubBrowserSignInHandle(redacted)"
}

class HubBrowserSignInRequest internal constructor(
    val browserUrl: String,
    val handle: HubBrowserSignInHandle,
    val expiresAtMs: Long,
    val retryAfterMs: Long,
) {
    // browserUrl necessarily carries the link secret, so do not leak it through logs or
    // observable-state diagnostics.
    override fun toString(): String =
        "HubBrowserSignInRequest(browserUrl=redacted, handle=$handle, " +
            "expiresAtMs=$expiresAtMs, retryAfterMs=$retryAfterMs)"
}

/**
 * The linked session returned exactly once by an approved browser sign-in.
 *
 * [token] is intentionally redacted from [toString]; callers should move it directly into
 * their session vault rather than placing this object in observable UI state.
 */
class HubLinkedSession internal constructor(
    val baseUrl: String,
    val token: String,
    val user: CurrentUserDto,
) {
    override fun toString(): String =
        "HubLinkedSession(baseUrl=$baseUrl, token=redacted, user=${user.username})"
}

sealed interface HubBrowserSignInState {
    data class Pending(
        val retryAfterMs: Long,
        val expiresAtMs: Long,
        val account: DeviceLinkPreviewDto? = null,
    ) : HubBrowserSignInState

    class Approved(val session: HubLinkedSession) : HubBrowserSignInState {
        override fun toString(): String = "Approved(session=$session)"
    }

    data object Denied : HubBrowserSignInState
    data object Expired : HubBrowserSignInState
}

/**
 * Small stateful facade for Android's browser-first sign-in.
 *
 * Poll tokens and the raw link token remain private to this process-local object; the link
 * secret is exposed only as part of [HubBrowserSignInRequest.browserUrl] for the Custom Tab.
 * [poll] performs one HTTP poll and returns the server's next pacing interval; the caller owns
 * scheduling so it can stop with its screen lifecycle. Terminal results and [cancel] forget
 * the handle.
 */
class HubBrowserSignIn(
    private val api: HubApi,
) {
    private data class ActiveRequest(
        val baseUrl: String,
        val pollToken: String,
    )

    private val state = Mutex()
    private val owner = Any()
    private val active = mutableMapOf<Long, ActiveRequest>()
    private var nextHandle = 1L

    suspend fun start(
        baseUrl: String,
        deviceName: String? = null,
    ): HubBrowserSignInRequest {
        val normalized = HubEndpoints.normalizeBaseUrl(baseUrl)
        val started = api.linkStart(normalized, deviceName, browserSignIn = true)
        if (!HubEndpoints.isSameOrigin(normalized, started.verificationUriComplete) ||
            !hasFragmentSecret(started.verificationUriComplete)
        ) {
            try {
                api.linkCancel(normalized, started.pollToken)
            } catch (_: HubException) {
                // The unsafe URL is still rejected; its server challenge expires shortly.
            }
            throw HubProtocolException(
                "The hub returned an unsafe browser sign-in URL",
            )
        }
        val handle = state.withLock {
            val id = nextHandle++
            active[id] = ActiveRequest(normalized, started.pollToken)
            HubBrowserSignInHandle(owner, id)
        }
        return HubBrowserSignInRequest(
            browserUrl = started.verificationUriComplete,
            handle = handle,
            expiresAtMs = started.expiresAtMs,
            retryAfterMs = started.intervalMs.coerceAtLeast(0),
        )
    }

    suspend fun poll(handle: HubBrowserSignInHandle): HubBrowserSignInState {
        if (handle.owner !== owner) return HubBrowserSignInState.Expired
        val request = state.withLock { active[handle.id] }
            ?: return HubBrowserSignInState.Expired
        val status = api.linkPoll(request.baseUrl, request.pollToken)
        return when (status.status.uppercase()) {
            "PENDING", "SCANNED" -> HubBrowserSignInState.Pending(
                retryAfterMs = status.intervalMs.coerceAtLeast(0),
                expiresAtMs = status.expiresAtMs,
                account = status.preview,
            )

            "APPROVED" -> {
                val flow = status.flow
                val token = flow?.sessionToken
                val user = flow?.user
                if (flow?.status != "AUTHENTICATED" || token.isNullOrBlank() || user == null) {
                    throw HubProtocolException(
                        "The hub approved browser sign-in without a complete session",
                    )
                }
                state.withLock { active.remove(handle.id) }
                HubBrowserSignInState.Approved(
                    HubLinkedSession(request.baseUrl, token, user),
                )
            }

            "DENIED" -> {
                state.withLock { active.remove(handle.id) }
                HubBrowserSignInState.Denied
            }

            "EXPIRED" -> {
                state.withLock { active.remove(handle.id) }
                HubBrowserSignInState.Expired
            }

            else -> throw HubProtocolException(
                "Unsupported browser sign-in status ${status.status}",
            )
        }
    }

    /**
     * Stops local polling first, then invalidates the server request. If delivery of the
     * cancellation fails, the local handle still stays forgotten and the five-minute server
     * expiry remains the backstop.
     */
    suspend fun cancel(handle: HubBrowserSignInHandle) {
        if (handle.owner !== owner) return
        val request = state.withLock { active.remove(handle.id) } ?: return
        api.linkCancel(request.baseUrl, request.pollToken)
    }

    private fun hasFragmentSecret(url: String): Boolean {
        val fragment = url.substringAfter('#', missingDelimiterValue = "")
        if (fragment.isBlank() || Urls.parse(url)?.queryParameter("t") != null) return false
        val fields = fragment.split('&').associate { field ->
            val separator = field.indexOf('=')
            if (separator < 0) field to "" else field.substring(0, separator) to
                field.substring(separator + 1)
        }
        return fields["t"].orEmpty().isNotBlank() && fields["mode"] == "sign-in"
    }
}
