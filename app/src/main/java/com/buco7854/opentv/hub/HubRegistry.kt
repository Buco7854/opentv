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

/** Whether a hub can be talked to right now, for badges and empty states. */
enum class HubHealth { UNKNOWN, REACHABLE, UNREACHABLE, SIGNED_OUT }

/**
 * One hub connection: the API bound to its base URL and vaulted token.
 *
 * Every call routes through [call] so a 401 flips the source to
 * [HubHealth.SIGNED_OUT] exactly once, rather than each screen inventing its
 * own reaction. The token is never wiped on a 401 — the user may still want to
 * see which account was signed in, and clearing is an explicit action.
 */
class HubClient(
    val id: Long,
    @Volatile var source: HubSource,
    private val api: HubApi,
    private val vault: HubSessionVault,
) {
    private val healthState = MutableStateFlow(HubHealth.UNKNOWN)
    val health: StateFlow<HubHealth> = healthState.asStateFlow()
    private val lifecycleLock = Any()
    private var healthOperation = 0L

    val baseUrl: String get() = source.baseUrl
    val isSignedIn: Boolean get() = vault.token(id) != null

    fun credentials() = synchronized(lifecycleLock) {
        credentialsLocked()
    }

    fun storeToken(token: String) {
        synchronized(lifecycleLock) {
            healthOperation++
            vault.store(id, token)
            healthState.value = HubHealth.REACHABLE
        }
    }

    fun forgetToken() {
        synchronized(lifecycleLock) {
            healthOperation++
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
        val (operation, credentials) = synchronized(lifecycleLock) {
            ++healthOperation to credentialsLocked()
        }
        return try {
            api.block(credentials).also {
                setHealthIfCurrent(operation, HubHealth.REACHABLE)
            }
        } catch (e: HubUnauthorizedException) {
            setHealthIfCurrent(operation, HubHealth.SIGNED_OUT)
            throw e
        } catch (e: HubUnreachableException) {
            setHealthIfCurrent(operation, HubHealth.UNREACHABLE)
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
            clients.getOrPut(hubId) { HubClient(hubId, source, api, vault) }
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
            val client = clients.getOrPut(hubId) { HubClient(hubId, source, api, vault) }
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

    suspend fun remove(hubId: Long) {
        signOut(hubId)
        mutationMutex.withLock {
            store.delete(hubId)
            clients.remove(hubId)
        }
    }

    /** The unauthenticated API, for probing a URL before any hub row exists. */
    val discovery: HubApi get() = api
}
