package com.buco7854.opentv.serverdata

import com.buco7854.opentv.serverdata.db.ContentIdentityRow
import com.buco7854.opentv.serverdata.db.AuthChallengeRow
import com.buco7854.opentv.serverdata.db.AuthSessionRow
import com.buco7854.opentv.serverdata.db.DownloadBlobRow
import com.buco7854.opentv.serverdata.db.MfaCompletionWrite
import com.buco7854.opentv.serverdata.db.OidcIdentityRow
import com.buco7854.opentv.serverdata.db.RecoveryCodeRow
import com.buco7854.opentv.serverdata.db.UserFavoriteRow
import com.buco7854.opentv.serverdata.db.UserDownloadRow
import com.buco7854.opentv.serverdata.db.UserPlaylistGrantRow
import com.buco7854.opentv.serverdata.db.UserResumeRow
import com.buco7854.opentv.serverdata.db.UserRow
import com.buco7854.opentv.serverdata.db.replaceUserPlaylistGrants
import com.buco7854.opentv.serverdata.db.completeMfa
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
