package com.buco7854.opentv.serverdata

import androidx.room.useReaderConnection
import com.buco7854.opentv.core.model.Channel
import com.buco7854.opentv.core.model.ChannelKind
import com.buco7854.opentv.core.model.Playlist
import com.buco7854.opentv.core.model.XtreamSeries
import com.buco7854.opentv.data.db.ChannelRow
import com.buco7854.opentv.data.db.PlaylistRow
import com.buco7854.opentv.serverdata.db.ContentIdentityRow
import com.buco7854.opentv.serverdata.db.AuthChallengeRow
import com.buco7854.opentv.serverdata.db.AuthSessionRow
import com.buco7854.opentv.serverdata.db.DefaultPlaylistRow
import com.buco7854.opentv.serverdata.db.DownloadBlobRow
import com.buco7854.opentv.serverdata.db.MfaCompletionWrite
import com.buco7854.opentv.serverdata.db.OpenTvServerDatabase
import com.buco7854.opentv.serverdata.db.OidcIdentityRow
import com.buco7854.opentv.serverdata.db.RecoveryCodeRow
import com.buco7854.opentv.serverdata.db.UserFavoriteRow
import com.buco7854.opentv.serverdata.db.UserDownloadRow
import com.buco7854.opentv.serverdata.db.UserPlaylistGrantRow
import com.buco7854.opentv.serverdata.db.UserResumeRow
import com.buco7854.opentv.serverdata.db.UserRow
import com.buco7854.opentv.serverdata.db.deleteCatalogPlaylist
import com.buco7854.opentv.serverdata.db.replaceUserPlaylistGrants
import com.buco7854.opentv.serverdata.db.completeMfa
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFails
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class OpenTvServerDatabaseTest {
    @Test
    fun uniqueUsernamesAndHardDeleteCascadesAreEnforced() = runTest {
        val dir = Files.createTempDirectory("opentv-server-db-test")
        val db = createOpenTvServerDatabase(dir.resolve("opentv.db").toString())
        try {
            val playlistId = seedPlaylist(db)
            seedChannel(db, playlistId, id = 1)
            val user = UserRow(
                "u1", "Alice", "alice", "Alice", UserStatus.ACTIVE, UserRole.USER,
                false, 1, 1, null,
            )
            db.users().insert(user)
            assertFails { db.users().insert(user.copy(id = "u2", username = "ALICE")) }
            db.content().upsert(
                ContentIdentityRow("c1", playlistId, 1, "fingerprint", 1, 1, false),
            )
            db.activity().addFavorite(UserFavoriteRow("u1", "c1", 1))

            db.users().delete("u1")

            assertTrue(db.activity().favorites("u1").isEmpty())
        } finally {
            db.close()
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun contentAndBlobUpdatesPreserveDependentUserState() = runTest {
        withDatabase { db ->
            val playlistId = seedPlaylist(db)
            seedChannel(db, playlistId, id = 10)
            seedChannel(db, playlistId, id = 11)
            db.users().insert(user())
            db.content().upsert(
                ContentIdentityRow("c1", playlistId, 1, "opaque", 10, 1, false),
            )
            db.activity().addFavorite(UserFavoriteRow("u1", "c1", 1))
            db.activity().upsertResume(UserResumeRow("u1", "c1", 20_000, 60_000, 1))
            db.downloads().upsertBlob(blob())
            db.downloads().upsertUserDownload(
                UserDownloadRow("ud1", "u1", "b1", true, false, 1, 1)
            )

            db.content().upsert(
                ContentIdentityRow("c1", playlistId, 1, "opaque", 11, 2, false),
            )
            db.downloads().upsertBlob(blob().copy(title = "Updated", updatedAtMs = 2))

            assertEquals(11, db.content().get("c1")?.currentChannelId)
            assertEquals(listOf("c1"), db.activity().favorites("u1").map { it.contentId })
            assertNotNull(db.activity().resume("u1", "c1"))
            assertEquals("Updated", db.downloads().blob("b1")?.title)
            assertNotNull(db.downloads().userDownload("ud1"))
        }
    }

    @Test
    fun grantReplacementAndDownloadSuspensionAreAtomic() = runTest {
        withDatabase { db ->
            val firstPlaylistId = seedPlaylist(db, "First")
            val secondPlaylistId = seedPlaylist(db, "Second")
            seedChannel(db, firstPlaylistId, id = 10)
            db.users().insert(user())
            db.content().upsert(
                ContentIdentityRow("c1", firstPlaylistId, 1, "opaque", 10, 1, false),
            )
            db.downloads().upsertBlob(blob())
            db.downloads().upsertUserDownload(
                UserDownloadRow("ud1", "u1", "b1", true, false, 1, 1)
            )
            db.grants().grant(UserPlaylistGrantRow("u1", firstPlaylistId, 1))

            val removed = db.replaceUserPlaylistGrants("u1", listOf(secondPlaylistId), 2)

            assertEquals(setOf(firstPlaylistId), removed.removed)
            assertEquals(listOf(secondPlaylistId), db.grants().forUser("u1"))
            val association = assertNotNull(db.downloads().userDownload("ud1"))
            assertTrue(association.suspended)
            assertTrue(!association.active)
        }
    }

    @Test
    fun playlistAndChannelForeignKeysCascadeServerState() = runTest {
        withDatabase { db ->
            assertEquals(
                1L,
                db.useReaderConnection { connection ->
                    connection.usePrepared("PRAGMA foreign_keys") {
                        check(it.step())
                        it.getLong(0)
                    }
                },
            )
            val playlistId = seedPlaylist(db)
            seedChannel(db, playlistId, id = 10)
            db.users().insert(user())
            db.grants().addDefault(DefaultPlaylistRow(playlistId))
            db.grants().grant(UserPlaylistGrantRow("u1", playlistId, 1))
            db.content().insert(
                ContentIdentityRow("c1", playlistId, 1, "opaque", 10, 1, false),
            )
            db.activity().addFavorite(UserFavoriteRow("u1", "c1", 1))
            db.activity().upsertResume(UserResumeRow("u1", "c1", 20, 60, 1))
            db.downloads().upsertBlob(blob())
            db.downloads().upsertUserDownload(
                UserDownloadRow("ud1", "u1", "b1", true, false, 1, 1),
            )

            // A rejected orphan proves foreign_keys is enabled on Room's own connection.
            assertFails { db.grants().addDefault(DefaultPlaylistRow(999)) }

            db.channelDao().deleteForPlaylist(playlistId)
            assertEquals(null, db.content().get("c1")?.currentChannelId)

            seedChannel(db, playlistId, id = 11)
            db.deleteCatalogPlaylist(playlistId)

            assertEquals(0, db.channelDao().count(playlistId, ChannelKind.LIVE))
            assertTrue(db.grants().defaults().isEmpty())
            assertTrue(db.grants().forUser("u1").isEmpty())
            assertEquals(null, db.content().get("c1"))
            assertTrue(db.activity().favorites("u1").isEmpty())
            assertEquals(null, db.activity().resume("u1", "c1"))
            assertEquals(null, db.downloads().blob("b1"))
            assertEquals(null, db.downloads().userDownload("ud1"))
        }
    }

    @Test
    fun freshServerDatabaseInstallsCatalogSearchSidecars() = runTest {
        val dir = Files.createTempDirectory("opentv-server-search-test")
        val persistence = createOpenTvServerStorage(dir.resolve("opentv.db").toString())
        try {
            val storage = persistence.catalog
            val playlistId = storage.playlists.insert(Playlist(name = "Fresh", url = null))
            storage.channels.insertAll(
                listOf(
                    Channel(
                        playlistId = playlistId,
                        name = "World Central",
                        url = "https://fixture.invalid/channel",
                        logo = null,
                        groupTitle = "News",
                        tvgId = null,
                        kind = ChannelKind.LIVE,
                        seriesKey = null,
                        season = null,
                        episode = null,
                        position = 0,
                    ),
                ),
            )
            storage.xtreamSeries.insertAll(
                listOf(
                    XtreamSeries(
                        playlistId = playlistId,
                        seriesId = 1,
                        name = "Central Stories",
                        categoryName = "Drama",
                        cover = null,
                        plot = null,
                        castNames = null,
                        genre = null,
                        rating = null,
                    ),
                ),
            )

            assertEquals(
                listOf("World Central"),
                storage.channels.search(playlistId, "central", 10).map { it.name },
            )
            assertEquals(
                listOf("Central Stories"),
                storage.xtreamSeries.search(playlistId, "central", 10).map { it.name },
            )
        } finally {
            persistence.catalog.close()
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun oidcAdminStatusIsTheUnionOfEveryLinkedIdentity() = runTest {
        withDatabase { db ->
            db.users().insert(user())
            db.oidc().upsert(
                OidcIdentityRow(
                    "https://issuer.example", "regular", "u1", "alice", "Alice",
                    "[\"viewers\"]", false, 1,
                ),
            )
            db.oidc().upsert(
                OidcIdentityRow(
                    "https://issuer.example", "administrator", "u1", "alice", "Alice",
                    "[\"admins\"]", true, 1,
                ),
            )

            assertTrue(db.oidc().hasAdminMapping("u1"))
            assertTrue(db.oidc().hasUsableAdminIdentity())

            db.oidc().upsert(
                OidcIdentityRow(
                    "https://issuer.example", "administrator", "u1", "alice", "Alice",
                    "[\"viewers\"]", false, 2,
                ),
            )

            assertTrue(!db.oidc().hasAdminMapping("u1"))
            assertTrue(!db.oidc().hasUsableAdminIdentity())
        }
    }

    @Test
    fun mfaCompletionRollsBackEveryConsumptionOnConflict() = runTest {
        withDatabase { db ->
            db.users().insert(user())
            db.challenges().insert(challenge("child", byteArrayOf(1)))
            db.challenges().insert(challenge("parent", byteArrayOf(2)))
            val session = session()

            val completed = db.completeMfa(
                challengeId = "child",
                parentChallengeId = "parent",
                recoveryCodeId = "missing",
                write = MfaCompletionWrite(session, loginAtMs = 10),
            )

            assertTrue(!completed)
            assertEquals(null, db.challenges().get("child")?.consumedAtMs)
            assertEquals(null, db.challenges().get("parent")?.consumedAtMs)
            assertEquals(null, db.sessions().get(session.id))

            db.credentials().insertRecoveryCodes(
                listOf(RecoveryCodeRow("recovery", "u1", byteArrayOf(3), 1, null)),
            )
            assertTrue(
                db.completeMfa(
                    challengeId = "child",
                    parentChallengeId = "parent",
                    recoveryCodeId = "recovery",
                    write = MfaCompletionWrite(session, loginAtMs = 10),
                ),
            )
            assertEquals(10, db.challenges().get("child")?.consumedAtMs)
            assertEquals(10, db.challenges().get("parent")?.consumedAtMs)
            assertTrue(db.credentials().unusedRecoveryCodes("u1").isEmpty())
            assertNotNull(db.sessions().get(session.id))
        }
    }

    @Test
    fun mfaCompletionCannotConsumeAnotherUsersRecoveryCode() = runTest {
        withDatabase { db ->
            db.users().insert(user())
            db.users().insert(
                user().copy(
                    id = "u2",
                    username = "Bob",
                    normalizedUsername = "bob",
                    displayName = "Bob",
                ),
            )
            db.challenges().insert(challenge("child", byteArrayOf(1)))
            db.credentials().insertRecoveryCodes(
                listOf(RecoveryCodeRow("other-recovery", "u2", byteArrayOf(3), 1, null)),
            )

            val completed = db.completeMfa(
                challengeId = "child",
                recoveryCodeId = "other-recovery",
                write = MfaCompletionWrite(session(), loginAtMs = 10),
            )

            assertTrue(!completed)
            assertEquals(null, db.challenges().get("child")?.consumedAtMs)
            assertEquals(
                listOf("other-recovery"),
                db.credentials().unusedRecoveryCodes("u2").map { it.id },
            )
            assertEquals(null, db.sessions().get("session"))
        }
    }

    private suspend fun withDatabase(
        block: suspend (com.buco7854.opentv.serverdata.db.OpenTvServerDatabase) -> Unit,
    ) {
        val dir = Files.createTempDirectory("opentv-server-db-test")
        val db = createOpenTvServerDatabase(dir.resolve("opentv.db").toString())
        try {
            block(db)
        } finally {
            db.close()
            dir.toFile().deleteRecursively()
        }
    }

    private suspend fun seedPlaylist(
        db: OpenTvServerDatabase,
        name: String = "Catalog",
    ): Long = db.playlistDao().insert(PlaylistRow(name = name, url = null))

    private suspend fun seedChannel(
        db: OpenTvServerDatabase,
        playlistId: Long,
        id: Long,
    ) {
        db.channelDao().insertAll(
            listOf(
                ChannelRow(
                    id = id,
                    playlistId = playlistId,
                    name = "Channel $id",
                    url = "https://fixture.invalid/$id",
                    logo = null,
                    groupTitle = "Fixture",
                    tvgId = null,
                    kind = ChannelKind.LIVE,
                    seriesKey = null,
                    season = null,
                    episode = null,
                    position = id.toInt(),
                    searchName = "channel $id",
                ),
            ),
        )
    }

    private fun user() = UserRow(
        "u1", "Alice", "alice", "Alice", UserStatus.ACTIVE, UserRole.USER,
        false, 1, 1, null,
    )

    private fun blob() = DownloadBlobRow(
        "b1", "c1", "Title", "https://provider.invalid/media", "/tmp/file",
        DownloadBlobStatus.QUEUED, 0, 0, null, 1, 1,
    )

    private fun challenge(id: String, tokenHash: ByteArray) = AuthChallengeRow(
        id, "u1", ChallengeKind.MFA, tokenHash, "", 0, 1, 100, null,
    )

    private fun session() = AuthSessionRow(
        id = "session",
        userId = "u1",
        tokenHash = byteArrayOf(4),
        csrfToken = "csrf",
        authMethod = AuthMethod.PASSWORD,
        clientKind = ClientKind.BROWSER,
        tokenFamilyId = "family",
        credentialVersion = 1,
        deviceId = null,
        deviceName = null,
        mfaSatisfiedAtMs = 10,
        createdAtMs = 10,
        lastSeenAtMs = 10,
        idleExpiresAtMs = 20,
        absoluteExpiresAtMs = 30,
        revokedAtMs = null,
    )
}
