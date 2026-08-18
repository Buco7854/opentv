package com.buco7854.opentv.hub

import com.buco7854.opentv.contract.CurrentUserDto
import com.buco7854.opentv.core.model.HubSource
import com.buco7854.opentv.core.storage.HubSourceStore
import com.buco7854.opentv.core.util.nowMs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Whether a hub can be talked to right now, for badges and empty states. */
enum class HubHealth { UNKNOWN, REACHABLE, UNREACHABLE, SIGNED_OUT }

/** Opaque, non-secret identity for one vaulted credential generation. */
internal data class HubCredentialGeneration internal constructor(internal val value: Long)

/**
 * One hub connection: the API bound to its base URL and vaulted token.
 *
 * Every call routes through [call] so a 401 flips the source to
 * [HubHealth.SIGNED_OUT] exactly once, rather than each screen inventing its
 * own reaction. The token is never wiped on a 401: explicit sign-out/removal owns
 * credential deletion, while reauthentication may replace it. The cached account
 * identity is cleared, however, so navigation immediately offers sign-in again.
 */
class HubClient(
    val id: Long,
    @Volatile var source: HubSource,
    private val api: HubApi,
    private val vault: HubSessionVault,
    private val onUnauthorized: suspend (HubClient, Long) -> Unit = { _, _ -> },
) {
    private val healthState = MutableStateFlow(HubHealth.UNKNOWN)
    val health: StateFlow<HubHealth> = healthState.asStateFlow()
    private val lifecycleLock = Any()
    private var healthOperation = 0L
    private var credentialGeneration = 0L

    val baseUrl: String get() = source.baseUrl
    val isSignedIn: Boolean get() = vault.token(id) != null

    fun credentials() = synchronized(lifecycleLock) {
        credentialsLocked()
    }

    fun storeToken(token: String) {
        synchronized(lifecycleLock) {
            healthOperation++
            credentialGeneration++
            vault.store(id, token)
            healthState.value = HubHealth.REACHABLE
        }
    }

    fun forgetToken() {
        synchronized(lifecycleLock) {
            healthOperation++
            credentialGeneration++
            vault.clear(id)
            healthState.value = HubHealth.SIGNED_OUT
        }
    }

    /**
     * Runs [block] against this hub and classifies the outcome. Non-auth,
     * non-transport failures (a 404, a bad request) propagate without touching
     * health: they say nothing about reachability.
     */
    suspend fun <T> call(block: suspend HubApi.(HubCredentials) -> T): T {
        val context = checkNotNull(beginCall())
        return executeCall(context, block)
    }

    /**
     * Executes only when [expected] still names the vaulted credential.
     *
     * The generation check and credential capture share [lifecycleLock], so reauthentication
     * cannot slip a replacement bearer into an operation queued by the old account. False also
     * means the generation changed while the old request was in flight; callers must then avoid
     * publishing its old-account result into UI now owned by the replacement account.
     */
    internal suspend fun callIfCredentialGeneration(
        expected: HubCredentialGeneration,
        block: suspend HubApi.(HubCredentials) -> Unit,
    ): Boolean {
        val context = beginCall(expected) ?: return false
        executeCall(context, block)
        return isCurrentCredentialGeneration(expected.value)
    }

    internal fun credentialGenerationSnapshot(): HubCredentialGeneration =
        synchronized(lifecycleLock) { HubCredentialGeneration(credentialGeneration) }

    internal fun runIfCredentialGenerationCurrent(
        expected: HubCredentialGeneration,
        block: () -> Unit,
    ): Boolean = synchronized(lifecycleLock) {
        if (credentialGeneration != expected.value) return@synchronized false
        block()
        true
    }

    private data class CallContext(
        val operation: Long,
        val generation: Long,
        val credentials: HubCredentials,
    )

    private fun beginCall(expected: HubCredentialGeneration? = null): CallContext? =
        synchronized(lifecycleLock) {
            if (expected != null && credentialGeneration != expected.value) {
                return@synchronized null
            }
            CallContext(++healthOperation, credentialGeneration, credentialsLocked())
        }

    private suspend fun <T> executeCall(
        context: CallContext,
        block: suspend HubApi.(HubCredentials) -> T,
    ): T {
        return try {
            api.block(context.credentials).also {
                setHealthIfCurrent(context.operation, HubHealth.REACHABLE)
            }
        } catch (e: HubUnauthorizedException) {
            // The bearer stays vaulted until explicit sign-out so the user can retry or
            // replace it, but the cached account identity must not keep presenting this
            // connection as signed in. Otherwise the shell keeps routing to Settings and
            // Settings keeps offering authenticated browser pages after the server has
            // already ended the session.
            setHealthIfCurrent(context.operation, HubHealth.SIGNED_OUT)
            if (clearIdentityIfCredentialsCurrent(context.generation)) {
                try {
                    onUnauthorized(this, context.generation)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (cleanupError: Throwable) {
                    // The authorization result remains the call's meaning. Persistence cleanup
                    // is presentation state and must not turn a signed-out response into a
                    // generic database failure, but retain it for diagnostics at the caller.
                    e.addSuppressed(cleanupError)
                }
            }
            throw e
        } catch (e: HubUnreachableException) {
            setHealthIfCurrent(context.operation, HubHealth.UNREACHABLE)
            throw e
        }
    }

    private fun setHealthIfCurrent(operation: Long, health: HubHealth) {
        synchronized(lifecycleLock) {
            if (healthOperation == operation) {
                healthState.value = health
            }
        }
    }

    private fun clearIdentityIfCredentialsCurrent(generation: Long): Boolean = synchronized(lifecycleLock) {
        if (credentialGeneration != generation) return@synchronized false
        source = source.copy(
            userId = null,
            username = null,
            role = null,
            lastSeenMs = null,
        )
        true
    }

    internal fun isCurrentCredentialGeneration(generation: Long): Boolean = synchronized(lifecycleLock) {
        credentialGeneration == generation
    }

    private fun credentialsLocked() = HubCredentials(source.baseUrl, vault.token(id))
}

/**
 * The app's hub connections, one [HubClient] per stored [HubSource]. Clients
 * are cached so their health state survives screen changes; rows removed from
 * storage drop out on the next lookup.
 */
class HubRegistry(
    private val store: HubSourceStore,
    private val api: HubApi,
    private val vault: HubSessionVault,
) {
    private val clients = mutableMapOf<Long, HubClient>()
    private val mutationMutex = Mutex()

    fun observeAll(): Flow<List<HubSource>> = store.observeAll()

    suspend fun sourceFor(hubId: Long): HubSource? = mutationMutex.withLock {
        store.get(hubId)
    }

    suspend fun withStoredHubIds(block: (Set<Long>) -> Unit) {
        mutationMutex.withLock {
            val existingIds = store.getAll().mapTo(mutableSetOf()) { it.id }
            clients.keys.removeAll { it !in existingIds }
            block(existingIds)
        }
    }

    suspend fun clientFor(hubId: Long): HubClient? = mutationMutex.withLock {
        val source = store.get(hubId)
        if (source == null) {
            clients.remove(hubId)
            null
        } else {
            clients.getOrPut(hubId) {
                HubClient(hubId, source, api, vault) { client, generation ->
                    mutationMutex.withLock {
                        if (client.isCurrentCredentialGeneration(generation)) store.clearIdentity(hubId)
                    }
                }
            }
                .also { it.source = source }
        }
    }

    /** Adds a connection and vaults the token from a completed sign-in. */
    suspend fun add(name: String, baseUrl: String, token: String): HubClient {
        val id = mutationMutex.withLock {
            store.upsert(
                HubSource(
                    name = name,
                    baseUrl = HubEndpoints.normalizeBaseUrl(baseUrl),
                    addedMs = nowMs(),
                ),
            )
        }
        try {
            vault.store(id, token)
        } catch (error: Throwable) {
            try {
                withContext(NonCancellable) {
                    mutationMutex.withLock {
                        store.delete(id)
                    }
                }
            } catch (cleanupError: Throwable) {
                error.addSuppressed(cleanupError)
            }
            throw error
        }
        return checkNotNull(clientFor(id)) { "hub $id vanished after insert" }
    }

    /**
     * Replaces the session for an existing connection without inserting or
     * changing its address. Cached identity belongs to the old session, so it
     * is cleared before `/auth/me` repopulates it.
     */
    suspend fun reauthenticate(hubId: Long, token: String): HubClient =
        mutationMutex.withLock {
            val source = store.get(hubId)
                ?: throw NoSuchElementException("hub $hubId no longer exists")
            val client = clients.getOrPut(hubId) {
                HubClient(hubId, source, api, vault) { client, generation ->
                    mutationMutex.withLock {
                        if (client.isCurrentCredentialGeneration(generation)) store.clearIdentity(hubId)
                    }
                }
            }
            val previousToken = vault.token(hubId)
            try {
                client.storeToken(token)
                store.clearIdentity(hubId)
            } catch (error: Throwable) {
                if (previousToken == null) {
                    client.forgetToken()
                } else {
                    client.storeToken(previousToken)
                }
                throw error
            }
            client.source = source.copy(
                userId = null,
                username = null,
                role = null,
                lastSeenMs = null,
            )
            client
        }

    /** Refreshes the cached identity used to gate admin entry points offline. */
    suspend fun refreshIdentity(hubId: Long): CurrentUserDto? {
        val client = clientFor(hubId) ?: return null
        val user = client.call { me(it) }
        store.updateIdentity(hubId, user.id, user.username, user.role, nowMs())
        store.get(hubId)?.let { client.source = it }
        return user
    }

    suspend fun signOut(hubId: Long) {
        val client = clientFor(hubId) ?: return
        val credentials = client.credentials()
        mutationMutex.withLock {
            client.forgetToken()
            store.clearIdentity(hubId)
            client.source = client.source.copy(
                userId = null,
                username = null,
                role = null,
                lastSeenMs = null,
            )
        }
        // Best effort: local sign-out is immediate even if the hub cannot be reached.
        try {
            api.logout(credentials)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) { }
    }

    suspend fun remove(
        hubId: Long,
        beforeLogout: suspend () -> Unit = {},
        beforeForget: () -> Unit = {},
    ) {
        val client = clientFor(hubId)
        if (client == null) {
            beforeForget()
            return
        }
        val credentials = client.credentials()
        try {
            // Releases go first because logout invalidates the bearer they need. One budget
            // covers every remote best-effort action so an unreachable server cannot hold
            // removal behind OkHttp's much longer general-purpose timeouts.
            withTimeoutOrNull(REMOVE_REMOTE_BUDGET_MS) {
                try {
                    beforeLogout()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Throwable) { }
                try {
                    api.logout(credentials)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Throwable) { }
            }
        } finally {
            try {
                beforeForget()
            } finally {
                // Explicit removal always wins over cancellation of its best-effort network
                // phase: no callback may leave a removed server's bearer in the vault.
                withContext(NonCancellable) {
                    mutationMutex.withLock {
                        client.forgetToken()
                        store.delete(hubId)
                        clients.remove(hubId)
                    }
                }
            }
        }
    }

    /** The unauthenticated API, for probing a URL before any hub row exists. */
    val discovery: HubApi get() = api

    private companion object {
        const val REMOVE_REMOTE_BUDGET_MS = 2_000L
    }
}
