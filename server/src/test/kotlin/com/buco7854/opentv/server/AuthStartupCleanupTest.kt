package com.buco7854.opentv.server

import com.buco7854.opentv.core.model.ChannelKind
import com.buco7854.opentv.data.db.PlaylistRow
import com.buco7854.opentv.serverdata.AuthMethod
import com.buco7854.opentv.serverdata.ClientKind
import com.buco7854.opentv.serverdata.DownloadBlobStatus
import com.buco7854.opentv.serverdata.UserRole
import com.buco7854.opentv.serverdata.UserStatus
import com.buco7854.opentv.serverdata.createOpenTvServerDatabase
import com.buco7854.opentv.serverdata.db.AuthSessionRow
import com.buco7854.opentv.serverdata.db.ContentIdentityRow
import com.buco7854.opentv.serverdata.db.DownloadBlobRow
import com.buco7854.opentv.serverdata.db.OidcIdentityRow
import com.buco7854.opentv.serverdata.db.UserDownloadRow
import com.buco7854.opentv.serverdata.db.UserRow
import kotlinx.coroutines.runBlocking
import java.net.URI
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AuthStartupCleanupTest {
    @Test
    fun `disabling password authentication at startup suspends the revoked user's fetch`() =
        withFixture(passwordEnabled = false) { fixture ->
            fixture.seedUser("owner", UserRole.ADMIN, oidcAdmin = true)
            fixture.seedOidcAdmin("owner")
            fixture.seedRunningDownload("owner")
            fixture.seedPasswordSession("owner")
            fixture.assertFetchRunning("owner")

            initializeAuthentication(fixture.auth, fixture.cleanup)

            assertEquals(fixture.now, fixture.db.sessions().get("session-owner")?.revokedAtMs)
            fixture.assertFetchSuspended("owner")
        }

    @Test
    fun `requiring MFA at startup suspends the newly invalid password user's fetch`() =
        withFixture(mfaRequiredRoles = setOf(UserRole.USER)) { fixture ->
            fixture.seedUser("admin", UserRole.ADMIN)
            fixture.seedUser("owner", UserRole.USER)
            fixture.seedRunningDownload("owner")
            fixture.seedPasswordSession("owner", mfaSatisfiedAtMs = null)
            fixture.assertFetchRunning("owner")

            initializeAuthentication(fixture.auth, fixture.cleanup)

            assertEquals(fixture.now, fixture.db.sessions().get("session-owner")?.revokedAtMs)
            fixture.assertFetchSuspended("owner")
        }

    @Test
    fun `a user who signed out before startup keeps their provider fetch running`() =
        withFixture(passwordEnabled = false) { fixture ->
            fixture.seedUser("admin", UserRole.ADMIN, oidcAdmin = true)
            fixture.seedOidcAdmin("admin")
            fixture.seedUser("owner", UserRole.USER)
            fixture.seedRunningDownload("owner")
            fixture.seedPasswordSession("owner", revokedAtMs = fixture.now - 1)

            initializeAuthentication(fixture.auth, fixture.cleanup)

            assertEquals(fixture.now - 1, fixture.db.sessions().get("session-owner")?.revokedAtMs)
            fixture.assertFetchRunning("owner")
        }

    private fun withFixture(
        passwordEnabled: Boolean = true,
        mfaRequiredRoles: Set<String> = emptySet(),
        block: suspend (Fixture) -> Unit,
    ) = runBlocking {
        val fixture = Fixture(passwordEnabled, mfaRequiredRoles)
        try {
            block(fixture)
        } finally {
            fixture.close()
        }
    }

    private class Fixture(
        passwordEnabled: Boolean,
        mfaRequiredRoles: Set<String>,
    ) {
        val now = 1_700_000_000_000L
        private val dir = Files.createTempDirectory("auth-startup-cleanup")
        val db = createOpenTvServerDatabase(dir.resolve("opentv.db").toString())
        private val sessions = PlaybackSessionRegistry(reapInBackground = false)
        private val downloads = DownloadManager(
            db = db,
            http = ServerHttp(),
            settings = ServerSettings(dir, pageSize = 50),
            dataDir = dir,
            connections = ProviderConnections(),
            connectionLimit = { Int.MAX_VALUE },
            clock = { now },
        )
        val cleanup = RuntimeUserStateCleanupCoordinator().also {
            it.bind(sessions, downloads)
        }
        val auth = AuthService(
            db,
            authConfig(passwordEnabled, mfaRequiredRoles),
            dir,
            clock = { now },
            cleanup = cleanup,
        )

        init {
            runBlocking {
                db.playlistDao().insert(PlaylistRow(id = 1, name = "Provider", url = null))
            }
        }

        suspend fun seedUser(id: String, role: String, oidcAdmin: Boolean = false) {
            db.users().insert(
                UserRow(
                    id = id,
                    username = id,
                    normalizedUsername = id,
                    displayName = id,
                    status = UserStatus.ACTIVE,
                    manualRole = role,
                    oidcAdmin = oidcAdmin,
                    createdAtMs = 1,
                    updatedAtMs = 1,
                    lastLoginAtMs = null,
                ),
            )
        }

        suspend fun seedOidcAdmin(userId: String) {
            db.oidc().upsert(
                OidcIdentityRow(
                    issuer = "https://issuer.example.test",
                    subject = userId,
                    userId = userId,
                    usernameClaim = userId,
                    displayNameClaim = userId,
                    groupsJson = """["admins"]""",
                    adminMapped = true,
                    updatedAtMs = 1,
                ),
            )
        }

        suspend fun seedRunningDownload(userId: String) {
            val identity = ContentIdentityRow(
                contentId = "content-$userId",
                playlistId = 1,
                kind = ChannelKind.MOVIE,
                providerFingerprint = "movie-$userId",
                currentChannelId = null,
                lastSeenAtMs = 1,
                retired = false,
            )
            db.content().upsert(identity)
            db.downloads().upsertBlob(
                DownloadBlobRow(
                    id = "blob-$userId",
                    contentId = identity.contentId,
                    title = "Movie",
                    sourceUrl = "https://provider.invalid/movie",
                    filePath = dir.resolve("user-downloads/$userId.bin").toString(),
                    status = DownloadBlobStatus.RUNNING,
                    totalBytes = 100,
                    downloadedBytes = 10,
                    error = null,
                    createdAtMs = 1,
                    updatedAtMs = 1,
                ),
            )
            db.downloads().upsertUserDownload(
                UserDownloadRow(
                    id = "download-$userId",
                    userId = userId,
                    blobId = "blob-$userId",
                    active = true,
                    suspended = false,
                    createdAtMs = 1,
                    updatedAtMs = 1,
                ),
            )
        }

        suspend fun seedPasswordSession(
            userId: String,
            mfaSatisfiedAtMs: Long? = now,
            revokedAtMs: Long? = null,
        ) {
            db.sessions().insert(
                AuthSessionRow(
                    id = "session-$userId",
                    userId = userId,
                    tokenHash = AuthCrypto.hashToken("token-$userId"),
                    csrfToken = "",
                    authMethod = AuthMethod.PASSWORD,
                    clientKind = ClientKind.BROWSER,
                    tokenFamilyId = "family-$userId",
                    credentialVersion = 0,
                    deviceId = null,
                    deviceName = null,
                    mfaSatisfiedAtMs = mfaSatisfiedAtMs,
                    createdAtMs = 1,
                    lastSeenAtMs = now,
                    idleExpiresAtMs = now + 60_000,
                    absoluteExpiresAtMs = now + 120_000,
                    revokedAtMs = revokedAtMs,
                ),
            )
        }

        suspend fun assertFetchSuspended(userId: String) {
            val download = assertNotNull(db.downloads().userDownload("download-$userId"))
            assertFalse(download.active)
            assertTrue(download.suspended)
            assertEquals(DownloadBlobStatus.PAUSED, db.downloads().blob("blob-$userId")?.status)
        }

        suspend fun assertFetchRunning(userId: String) {
            val download = assertNotNull(db.downloads().userDownload("download-$userId"))
            assertTrue(download.active)
            assertFalse(download.suspended)
            assertEquals(DownloadBlobStatus.RUNNING, db.downloads().blob("blob-$userId")?.status)
        }

        fun close() {
            sessions.close()
            downloads.close()
            db.close()
            dir.toFile().deleteRecursively()
        }
    }

    private companion object {
        fun authConfig(
            passwordEnabled: Boolean,
            mfaRequiredRoles: Set<String>,
        ) = AuthConfig(
            publicUrl = URI("https://tv.example.com"),
            passwordEnabled = passwordEnabled,
            encryptionKey = ByteArray(32) { it.toByte() },
            initialAdmin = null,
            mfaRequiredRoles = mfaRequiredRoles,
            oidc = if (passwordEnabled) null else OidcConfig(
                issuer = URI("https://issuer.example.test"),
                clientId = "client",
                clientSecret = "secret",
                scopes = listOf("openid"),
                usernameClaim = "preferred_username",
                displayNameClaim = "name",
                groupsClaim = "groups",
                adminGroups = setOf("admins"),
                autoProvision = false,
            ),
            secureCookies = true,
            webAuthnRpId = "tv.example.com",
            webAuthnOrigin = "https://tv.example.com",
            sessionIdleMs = 24 * 60 * 60_000L,
            sessionAbsoluteMs = 30L * 24 * 60 * 60_000L,
        )
    }
}
