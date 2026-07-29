package com.buco7854.opentv.hub

import android.content.SharedPreferences
import com.buco7854.opentv.core.model.HubSource
import com.buco7854.opentv.core.net.HttpResponseSpec
import com.buco7854.opentv.core.net.HttpTransport
import com.buco7854.opentv.core.storage.HubSourceStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HubSessionVaultTest {

    /** XOR "cipher": enough to prove the vault round-trips through the seam. */
    private val cipher = object : TokenCipher {
        override fun encrypt(plain: ByteArray) = plain.map { (it.toInt() xor 0x5A).toByte() }.toByteArray()
        override fun decrypt(blob: ByteArray) = encrypt(blob)
    }

    private val vault = HubSessionVault(FakePrefs(), cipher)

    @Test
    fun storesAndReadsBackPerHub() {
        vault.store(1, "token-one")
        vault.store(2, "token-two")
        assertEquals("token-one", vault.token(1))
        assertEquals("token-two", vault.token(2))
    }

    @Test
    fun clearRemovesOnlyThatHub() {
        vault.store(1, "token-one")
        vault.store(2, "token-two")
        vault.clear(1)
        assertNull(vault.token(1))
        assertEquals("token-two", vault.token(2))
    }

    @Test
    fun orphanedTokensArePrunedWhenTheirHubRowsAreGone() {
        vault.store(1, "token-one")
        vault.store(2, "token-two")

        vault.pruneMissingHubs(setOf(1))

        assertEquals("token-one", vault.token(1))
        assertNull(vault.token(2))
    }

    @Test
    fun corruptBlobReadsAsSignedOut() {
        val prefs = FakePrefs()
        val vault = HubSessionVault(prefs, object : TokenCipher {
            override fun encrypt(plain: ByteArray) = plain
            override fun decrypt(blob: ByteArray) = error("corrupt")
        })
        prefs.edit().putString("hub-token-1", "not-base64-!!").apply()
        assertNull(vault.token(1))
    }

    @Test
    fun failedVaultWriteDoesNotLeaveACredentiallessHubRow() = runTest {
        val store = RecordingHubStore()
        val vault = HubSessionVault(FakePrefs(), object : TokenCipher {
            override fun encrypt(plain: ByteArray): ByteArray = error("keystore unavailable")
            override fun decrypt(blob: ByteArray): ByteArray = blob
        })
        val registry = HubRegistry(
            store,
            HubApi(HttpTransport { error("network must not be used") }),
            vault,
        )

        val failure = try {
            registry.add("Home", "https://hub.example", "session-token")
            null
        } catch (error: Throwable) {
            error
        }

        assertTrue(failure is IllegalStateException)
        assertEquals(emptyList<HubSource>(), store.getAll())
        assertNull(vault.token(RecordingHubStore.ID))
    }

    @Test
    fun reauthenticationKeepsTheRowAndAcceptsADifferentAccount() = runTest {
        val store = RecordingHubStore()
        val id = store.upsert(
            HubSource(
                name = "Home",
                baseUrl = "https://hub.example",
                userId = "old-user",
                username = "alice",
                role = "USER",
                addedMs = 1,
                lastSeenMs = 2,
            ),
        )
        vault.store(id, "old-session")
        val registry = HubRegistry(
            store,
            HubApi(
                HttpTransport {
                    HttpResponseSpec(
                        200,
                        emptyMap(),
                        """
                            {
                              "id":"new-user",
                              "username":"bob",
                              "displayName":"Bob",
                              "role":"ADMIN",
                              "authMethod":"PASSWORD",
                              "clientKind":"NATIVE",
                              "authSessionId":"new-session",
                              "playlistIds":[],
                              "hasPassword":true
                            }
                        """.trimIndent(),
                    )
                },
            ),
            vault,
        )

        val client = registry.reauthenticate(id, "new-session")

        assertEquals(id, client.id)
        assertEquals(1, store.upsertCalls)
        assertEquals("new-session", vault.token(id))
        assertNull(store.get(id)?.userId)

        registry.refreshIdentity(id)

        assertEquals(listOf(id), store.getAll().map { it.id })
        assertEquals("new-user", store.get(id)?.userId)
        assertEquals("bob", store.get(id)?.username)
        assertEquals("ADMIN", store.get(id)?.role)
    }

    @Test
    fun concurrentLookupCannotRecacheAHubAfterRemoval() = runTest {
        val store = RacingHubStore()
        val registry = HubRegistry(
            store,
            HubApi(HttpTransport { HttpResponseSpec(204, emptyMap(), "") }),
            vault,
        )
        vault.store(RacingHubStore.ID, "session")

        val lookup = async { registry.clientFor(RacingHubStore.ID) }
        store.firstGetEntered.await()
        val removal = launch { registry.remove(RacingHubStore.ID) }
        runCurrent()
        store.releaseFirstGet.complete(Unit)
        lookup.await()
        removal.join()

        assertEquals(0, cachedClientCount(registry))
        assertNull(registry.clientFor(RacingHubStore.ID))
    }

    @Test
    fun signOutCancellationDoesNotContinueAsASuccessfulLocalSignOut() = runTest {
        val store = RecordingHubStore().apply {
            upsert(HubSource(name = "Home", baseUrl = "https://hub.example", addedMs = 1))
        }
        val logoutStarted = CompletableDeferred<Unit>()
        val registry = HubRegistry(
            store,
            HubApi(
                HttpTransport {
                    logoutStarted.complete(Unit)
                    awaitCancellation()
                },
            ),
            vault,
        )
        vault.store(RecordingHubStore.ID, "session")
        var returnedNormally = false

        val signOut = launch {
            registry.signOut(RecordingHubStore.ID)
            returnedNormally = true
        }
        logoutStarted.await()
        signOut.cancel()
        signOut.join()

        assertTrue(signOut.isCancelled)
        assertFalse(returnedNormally)
        assertEquals("session", vault.token(RecordingHubStore.ID))
    }

    @Test
    fun lateSuccessCannotOverwriteANewerSignedOutHealthOutcome() = runTest {
        val source = HubSource(
            id = 23,
            name = "Home",
            baseUrl = "https://hub.example",
            addedMs = 1,
        )
        val client = HubClient(
            source.id,
            source,
            HubApi(HttpTransport { error("the call blocks are used directly") }),
            vault,
        )
        val firstStarted = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val older = async {
            client.call {
                firstStarted.complete(Unit)
                releaseFirst.await()
                "old success"
            }
        }
        firstStarted.await()
        val newer = async {
            try {
                client.call<String> {
                    throw HubUnauthorizedException("unauthorized", "signed out")
                }
            } catch (_: HubUnauthorizedException) {
                Unit
            }
        }
        newer.await()
        releaseFirst.complete(Unit)
        older.await()

        assertEquals(HubHealth.SIGNED_OUT, client.health.value)
    }

    private fun cachedClientCount(registry: HubRegistry): Int {
        val field = HubRegistry::class.java.getDeclaredField("clients").apply {
            isAccessible = true
        }
        return (field.get(registry) as Map<*, *>).size
    }
}

private class RecordingHubStore : HubSourceStore {
    private var source: HubSource? = null
    var upsertCalls = 0
        private set

    override fun observeAll(): Flow<List<HubSource>> = flowOf(source?.let(::listOf).orEmpty())
    override suspend fun getAll(): List<HubSource> = source?.let(::listOf).orEmpty()
    override suspend fun get(id: Long): HubSource? = source?.takeIf { it.id == id }

    override suspend fun upsert(source: HubSource): Long {
        upsertCalls++
        this.source = source.copy(id = ID)
        return ID
    }

    override suspend fun delete(id: Long) {
        if (source?.id == id) source = null
    }

    override suspend fun updateIdentity(
        id: Long,
        userId: String?,
        username: String?,
        role: String?,
        seenMs: Long,
    ) {
        source = source?.takeIf { it.id == id }?.copy(
            userId = userId,
            username = username,
            role = role,
            lastSeenMs = seenMs,
        ) ?: source
    }

    override suspend fun clearIdentity(id: Long) {
        source = source?.takeIf { it.id == id }?.copy(
            userId = null,
            username = null,
            role = null,
            lastSeenMs = null,
        ) ?: source
    }

    companion object {
        const val ID = 7L
    }
}

private class RacingHubStore : HubSourceStore {
    private var source: HubSource? = HubSource(
        id = ID,
        name = "Home",
        baseUrl = "https://hub.example",
        addedMs = 1,
    )
    private var firstGet = true
    val firstGetEntered = CompletableDeferred<Unit>()
    val releaseFirstGet = CompletableDeferred<Unit>()

    override fun observeAll(): Flow<List<HubSource>> = flowOf(source?.let(::listOf).orEmpty())
    override suspend fun getAll(): List<HubSource> = source?.let(::listOf).orEmpty()

    override suspend fun get(id: Long): HubSource? {
        val snapshot = source?.takeIf { it.id == id }
        if (firstGet) {
            firstGet = false
            firstGetEntered.complete(Unit)
            releaseFirstGet.await()
        }
        return snapshot
    }

    override suspend fun upsert(source: HubSource): Long {
        this.source = source.copy(id = ID)
        return ID
    }

    override suspend fun delete(id: Long) {
        if (source?.id == id) source = null
    }

    override suspend fun updateIdentity(
        id: Long,
        userId: String?,
        username: String?,
        role: String?,
        seenMs: Long,
    ) = Unit

    override suspend fun clearIdentity(id: Long) {
        source = source?.takeIf { it.id == id }?.copy(
            userId = null,
            username = null,
            role = null,
            lastSeenMs = null,
        ) ?: source
    }

    companion object {
        const val ID = 17L
    }
}

/** In-memory SharedPreferences covering only what the vault touches. */
private class FakePrefs : SharedPreferences {
    private val values = mutableMapOf<String, String?>()

    override fun getString(key: String, defValue: String?): String? = values[key] ?: defValue

    override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
        override fun putString(key: String, value: String?) = apply { values[key] = value }
        override fun remove(key: String) = apply { values.remove(key) }
        override fun apply() = Unit
        override fun commit() = true
        override fun clear() = apply { values.clear() }
        override fun putStringSet(key: String, v: MutableSet<String>?) = throw UnsupportedOperationException()
        override fun putInt(key: String, v: Int) = throw UnsupportedOperationException()
        override fun putLong(key: String, v: Long) = throw UnsupportedOperationException()
        override fun putFloat(key: String, v: Float) = throw UnsupportedOperationException()
        override fun putBoolean(key: String, v: Boolean) = throw UnsupportedOperationException()
    }

    override fun getAll(): MutableMap<String, *> = values.toMutableMap()
    override fun getStringSet(key: String, defValues: MutableSet<String>?) = throw UnsupportedOperationException()
    override fun getInt(key: String, defValue: Int) = throw UnsupportedOperationException()
    override fun getLong(key: String, defValue: Long) = throw UnsupportedOperationException()
    override fun getFloat(key: String, defValue: Float) = throw UnsupportedOperationException()
    override fun getBoolean(key: String, defValue: Boolean) = throw UnsupportedOperationException()
    override fun contains(key: String) = values.containsKey(key)
    override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
    override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
}
