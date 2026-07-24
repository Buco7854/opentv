package com.buco7854.opentv.serverdata

import com.buco7854.opentv.serverdata.db.ContentIdentityRow
import com.buco7854.opentv.serverdata.db.DownloadBlobRow
import com.buco7854.opentv.serverdata.db.OidcIdentityRow
import com.buco7854.opentv.serverdata.db.UserFavoriteRow
import com.buco7854.opentv.serverdata.db.UserDownloadRow
import com.buco7854.opentv.serverdata.db.UserPlaylistGrantRow
import com.buco7854.opentv.serverdata.db.UserResumeRow
import com.buco7854.opentv.serverdata.db.UserRow
import com.buco7854.opentv.serverdata.db.replaceUserPlaylistGrants
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFails
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ServerUserDatabaseTest {
    @Test
    fun uniqueUsernamesAndHardDeleteCascadesAreEnforced() = runTest {
        val dir = Files.createTempDirectory("opentv-server-users-test")
        val db = createServerUserDatabase(dir.resolve("users.db").toString())
        try {
            val user = UserRow(
                "u1", "Alice", "alice", "Alice", UserStatus.ACTIVE, UserRole.USER,
                false, 1, 1, null,
            )
            db.users().insert(user)
            assertFails { db.users().insert(user.copy(id = "u2", username = "ALICE")) }
            db.content().upsert(ContentIdentityRow("c1", 1, 1, "fingerprint", 1, 1, false))
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
            db.users().insert(user())
            db.content().upsert(ContentIdentityRow("c1", 1, 1, "opaque", 10, 1, false))
            db.activity().addFavorite(UserFavoriteRow("u1", "c1", 1))
            db.activity().upsertResume(UserResumeRow("u1", "c1", 20_000, 60_000, 1))
            db.downloads().upsertBlob(blob())
            db.downloads().upsertUserDownload(
                UserDownloadRow("ud1", "u1", "b1", true, false, 1, 1)
            )

            db.content().upsert(ContentIdentityRow("c1", 1, 1, "opaque", 11, 2, false))
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
            db.users().insert(user())
            db.content().upsert(ContentIdentityRow("c1", 1, 1, "opaque", 10, 1, false))
            db.downloads().upsertBlob(blob())
            db.downloads().upsertUserDownload(
                UserDownloadRow("ud1", "u1", "b1", true, false, 1, 1)
            )
            db.grants().grant(UserPlaylistGrantRow("u1", 1, 1))

            val removed = db.replaceUserPlaylistGrants("u1", listOf(2), 2)

            assertEquals(setOf(1L), removed.removed)
            assertEquals(listOf(2L), db.grants().forUser("u1"))
            val association = assertNotNull(db.downloads().userDownload("ud1"))
            assertTrue(association.suspended)
            assertTrue(!association.active)
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

    private suspend fun withDatabase(
        block: suspend (com.buco7854.opentv.serverdata.db.ServerUserDatabase) -> Unit,
    ) {
        val dir = Files.createTempDirectory("opentv-server-users-test")
        val db = createServerUserDatabase(dir.resolve("users.db").toString())
        try {
            block(db)
        } finally {
            db.close()
            dir.toFile().deleteRecursively()
        }
    }

    private fun user() = UserRow(
        "u1", "Alice", "alice", "Alice", UserStatus.ACTIVE, UserRole.USER,
        false, 1, 1, null,
    )

    private fun blob() = DownloadBlobRow(
        "b1", "c1", "Title", "https://provider.invalid/media", "/tmp/file",
        DownloadBlobStatus.QUEUED, 0, 0, null, 1, 1,
    )
}
