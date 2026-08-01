package com.buco7854.opentv.server

import com.buco7854.opentv.core.model.ChannelKind
import com.buco7854.opentv.data.db.PlaylistRow
import com.buco7854.opentv.serverdata.DownloadBlobStatus
import com.buco7854.opentv.serverdata.UserRole
import com.buco7854.opentv.serverdata.UserStatus
import com.buco7854.opentv.serverdata.db.ContentIdentityRow
import com.buco7854.opentv.serverdata.db.DownloadBlobRow
import com.buco7854.opentv.serverdata.db.UserDownloadRow
import com.buco7854.opentv.serverdata.db.UserRow
import com.buco7854.opentv.serverdata.db.deleteCatalogPlaylist
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DownloadManagerCleanupAdversarialTest {
    @Test
    fun `orphan cleanup rechecks a stale candidate after a new reference arrives`() =
        withFixture { fixture ->
            fixture.seedBlob(withReference = false)
            val staleCandidate = fixture.db.downloads().orphanBlobs().single()
            fixture.addReference()

            fixture.manager.deleteOrphanBlob(staleCandidate.id)

            assertNotNull(fixture.db.downloads().blob("blob-1"))
            assertEquals(1, fixture.db.downloads().referenceCount("blob-1"))
            assertTrue(Files.exists(fixture.downloadPath))
        }

    @Test
    fun `playlist deletion removes download files before catalog cascades rows`() =
        withFixture { fixture ->
            fixture.seedBlob(withReference = true)

            fixture.manager.deletePlaylist(fixture.playlistId)
            fixture.db.deleteCatalogPlaylist(fixture.playlistId)

            assertFalse(Files.exists(fixture.downloadPath))
            assertNull(fixture.db.downloads().blob("blob-1"))
            assertNull(fixture.db.downloads().userDownload("download-1"))
            assertNull(fixture.db.content().get("content-1"))
        }

    @Test
    fun `revoking one user does not park a blob still active for another user`() =
        withFixture { fixture ->
            fixture.seedBlob(withReference = true, status = DownloadBlobStatus.RUNNING)
            fixture.addReference("other")

            fixture.manager.suspendUserAccess("owner")

            assertEquals(true, fixture.db.downloads().userDownload("download-1")?.suspended)
            assertEquals(false, fixture.db.downloads().userDownload("download-1")?.active)
            assertEquals(false, fixture.db.downloads().userDownload("download-other")?.suspended)
            assertEquals(true, fixture.db.downloads().userDownload("download-other")?.active)
            assertEquals(DownloadBlobStatus.RUNNING, fixture.db.downloads().blob("blob-1")?.status)
        }

    @Test
    fun `a suspended user can restore its persistent reference`() = withFixture { fixture ->
        fixture.seedBlob(withReference = true)
        fixture.manager.suspendUserAccess("owner")

        fixture.manager.resume("owner", "download-1")

        assertEquals(false, fixture.db.downloads().userDownload("download-1")?.suspended)
        assertEquals(true, fixture.db.downloads().userDownload("download-1")?.active)
        assertEquals(DownloadBlobStatus.DONE, fixture.db.downloads().blob("blob-1")?.status)
        assertTrue(Files.exists(fixture.downloadPath))
    }

    @Test
    fun `deleting one shared reference preserves the blob until the last reference leaves`() =
        withFixture { fixture ->
            fixture.seedBlob(withReference = true)
            fixture.addReference("other")

            fixture.manager.delete("owner", "download-1")

            assertNotNull(fixture.db.downloads().blob("blob-1"))
            assertNotNull(fixture.db.downloads().userDownload("download-other"))
            assertTrue(Files.exists(fixture.downloadPath))

            fixture.manager.delete("other", "download-other")

            assertNull(fixture.db.downloads().blob("blob-1"))
            assertFalse(Files.exists(fixture.downloadPath))
        }

    private fun withFixture(block: suspend (Fixture) -> Unit) = runBlocking {
        val fixture = Fixture()
        try {
            block(fixture)
        } finally {
            fixture.close()
        }
    }

    private class Fixture {
        private val persistence = ServerTestPersistence("download-cleanup-review")
        private val dir = persistence.directory
        val db = persistence.database
        val manager = DownloadManager(
            db = db,
            http = ServerHttp(),
            settings = ServerSettings(dir, pageSize = 50),
            dataDir = dir,
            connections = ProviderConnections(),
            connectionLimit = { Int.MAX_VALUE },
        )
        val playlistId = 1L
        val downloadPath = dir.resolve("user-downloads/movie.bin")

        init {
            persistence.closeBeforeDatabase(manager::close)
            runBlocking {
                db.playlistDao().insert(PlaylistRow(id = playlistId, name = "Provider", url = null))
                db.users().insert(
                    UserRow(
                        id = "owner",
                        username = "owner",
                        normalizedUsername = "owner",
                        displayName = "Owner",
                        status = UserStatus.ACTIVE,
                        manualRole = UserRole.USER,
                        oidcAdmin = false,
                        createdAtMs = 1,
                        updatedAtMs = 1,
                        lastLoginAtMs = null,
                    ),
                )
                db.users().insert(
                    UserRow(
                        id = "other",
                        username = "other",
                        normalizedUsername = "other",
                        displayName = "Other",
                        status = UserStatus.ACTIVE,
                        manualRole = UserRole.USER,
                        oidcAdmin = false,
                        createdAtMs = 1,
                        updatedAtMs = 1,
                        lastLoginAtMs = null,
                    ),
                )
                db.content().upsert(
                    ContentIdentityRow(
                        "content-1",
                        playlistId,
                        ChannelKind.MOVIE,
                        "provider-movie-1",
                        null,
                        1,
                        false,
                    ),
                )
            }
        }

        suspend fun seedBlob(
            withReference: Boolean,
            status: String = DownloadBlobStatus.DONE,
        ) {
            Files.createDirectories(downloadPath.parent)
            Files.writeString(downloadPath, "abc")
            db.downloads().upsertBlob(
                DownloadBlobRow(
                    id = "blob-1",
                    contentId = "content-1",
                    title = "Movie",
                    sourceUrl = "https://provider.invalid/movie",
                    filePath = downloadPath.toString(),
                    status = status,
                    totalBytes = 3,
                    downloadedBytes = 3,
                    error = null,
                    createdAtMs = 1,
                    updatedAtMs = 1,
                ),
            )
            if (withReference) addReference("owner")
        }

        suspend fun addReference(userId: String = "owner") {
            db.downloads().upsertUserDownload(
                UserDownloadRow(
                    "download-${if (userId == "owner") "1" else userId}",
                    userId,
                    "blob-1",
                    true,
                    false,
                    1,
                    1,
                ),
            )
        }

        fun close() {
            persistence.close()
        }
    }
}
