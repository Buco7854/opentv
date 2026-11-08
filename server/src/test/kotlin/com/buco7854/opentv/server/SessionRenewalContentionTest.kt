package com.buco7854.opentv.server

import androidx.room.immediateTransaction
import androidx.room.useWriterConnection
import com.buco7854.opentv.serverdata.AuthMethod
import com.buco7854.opentv.serverdata.UserRole
import com.buco7854.opentv.serverdata.UserStatus
import com.buco7854.opentv.serverdata.createOpenTvServerDatabase
import com.buco7854.opentv.serverdata.db.UserRow
import java.net.URI
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

class SessionRenewalContentionTest {
    @Test
    fun returningViewerAuthenticatesWhileWriterIsBusyAndRenewsAfterward() = runBlocking {
        val directory = Files.createTempDirectory("opentv-session-renewal")
        val database = createOpenTvServerDatabase(directory.resolve("opentv.db").toString())
        var now = 1_700_000_000_000L
        val sessions = PersistentSessionService(
            database, config(), NoopUserStateCleanupCoordinator, { now },
        )
        val renewalScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val authenticator = RequestAuthenticator(sessions, renewalScope)
        val releaseWriter = CompletableDeferred<Unit>()
        try {
            val user = user(now)
            database.users().insert(user)
            val issued = sessions.issue(user, AuthMethod.WEBAUTHN, mfa = true)
            now += 120_000
            val writerHeld = CompletableDeferred<Unit>()
            val write = async(Dispatchers.IO) {
                database.useWriterConnection { connection ->
                    connection.immediateTransaction {
                        writerHeld.complete(Unit)
                        releaseWriter.await()
                    }
                }
            }
            writerHeld.await()

            // These are the returning page's parallel favorites, movies and series requests.
            // Their authority reads can run under WAL while a catalog transaction owns the
            // writer. Renewing the session must not make those reads wait for that transaction.
            val requests = List(8) { index ->
                async(Dispatchers.IO) {
                    if (index % 2 == 0) authenticator.authenticate(issued.token)
                    else authenticator.authenticateSession(issued.row.id)
                }
            }
            val authenticated = withTimeout(3_000) { requests.awaitAll() }
            assertTrue(authenticated.all { it?.userId == user.id })
            assertTrue(!write.isCompleted, "Authentication must finish while the writer is held")
            assertEquals(issued.row.lastSeenAtMs, database.sessions().get(issued.row.id)?.lastSeenAtMs)

            releaseWriter.complete(Unit)
            write.await()
            withTimeout(3_000) { renewalScope.coroutineContext.job.children.toList().joinAll() }
            val renewed = assertNotNull(database.sessions().get(issued.row.id))
            assertEquals(now, renewed.lastSeenAtMs)
            assertEquals(now + config().sessionIdleMs, renewed.idleExpiresAtMs)

            database.sessions().revoke(issued.row.id, now)
            assertNull(authenticator.authenticate(issued.token))
        } finally {
            releaseWriter.complete(Unit)
            authenticator.close()
            database.close()
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun parallelReadsDoNotQueueBehindTheRequestAlreadyRenewingTheirSession() = runBlocking {
        val directory = Files.createTempDirectory("opentv-session-renewal-fanout")
        val database = createOpenTvServerDatabase(directory.resolve("opentv.db").toString())
        var now = 1_700_000_000_000L
        val sessions = PersistentSessionService(
            database, config(), NoopUserStateCleanupCoordinator, { now },
        )
        val renewalScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val authenticator = RequestAuthenticator(sessions, renewalScope)
        val releaseWriter = CompletableDeferred<Unit>()
        try {
            val user = user(now)
            database.users().insert(user)
            val issued = sessions.issue(user, AuthMethod.WEBAUTHN, mfa = true)
            now += 120_000
            val writerHeld = CompletableDeferred<Unit>()
            val write = async(Dispatchers.IO) {
                database.useWriterConnection { connection ->
                    connection.immediateTransaction {
                        writerHeld.complete(Unit)
                        releaseWriter.await()
                    }
                }
            }
            writerHeld.await()
            assertNotNull(withTimeout(3_000) { authenticator.authenticate(issued.token) })
            val reads = List(6) { async(Dispatchers.IO) { authenticator.authenticate(issued.token) } }
            assertTrue(withTimeout(3_000) { reads.awaitAll() }.all { it != null })
            assertEquals(1, renewalScope.coroutineContext.job.children.count(),
                "Parallel requests must share one queued renewal")

            // A renewal queued before idle expiry cannot revive the session after it expires.
            now = issued.row.idleExpiresAtMs
            releaseWriter.complete(Unit)
            write.await()
            withTimeout(3_000) { renewalScope.coroutineContext.job.children.toList().joinAll() }
            assertEquals(issued.row.idleExpiresAtMs, database.sessions().get(issued.row.id)?.idleExpiresAtMs)
            assertNull(authenticator.authenticate(issued.token))
        } finally {
            releaseWriter.complete(Unit)
            authenticator.close()
            database.close()
            directory.toFile().deleteRecursively()
        }
    }

    private fun user(now: Long) = UserRow(
        id = "viewer", username = "viewer", normalizedUsername = "viewer",
        displayName = "Viewer", status = UserStatus.ACTIVE, manualRole = UserRole.USER,
        oidcAdmin = false, createdAtMs = now, updatedAtMs = now, lastLoginAtMs = now,
    )

    private fun config() = AuthConfig(
        publicUrl = URI("http://localhost:8080"), passwordEnabled = true,
        encryptionKey = ByteArray(32), initialAdmin = null, mfaRequiredRoles = emptySet(),
        oidc = null, secureCookies = false, webAuthnRpId = "localhost",
        webAuthnOrigin = "http://localhost:8080", sessionIdleMs = 86_400_000,
        sessionAbsoluteMs = 30L * 86_400_000,
    )
}
