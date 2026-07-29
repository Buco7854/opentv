package com.buco7854.opentv.server

import com.buco7854.opentv.contract.*
import com.buco7854.opentv.serverdata.ChallengeKind
import com.buco7854.opentv.serverdata.AuthMethod
import com.buco7854.opentv.serverdata.UserStatus
import com.buco7854.opentv.serverdata.createServerUserDatabase
import com.buco7854.opentv.serverdata.db.ContentIdentityRow
import com.buco7854.opentv.serverdata.db.DefaultPlaylistRow
import com.buco7854.opentv.serverdata.db.PendingOidcIdentityRow
import com.buco7854.opentv.serverdata.db.ServerUserDatabase
import com.buco7854.opentv.serverdata.db.UserResumeRow
import androidx.room.useWriterConnection
import java.net.URI
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class UserAdministrationLifecycleTest {
    @Test
    fun createWithPasswordIsActiveAndHasNoActivationChallenge() = runTest {
        withAdmin { service, actor, db, _ ->
            val password = "a new sufficiently long password"
            db.grants().addDefault(DefaultPlaylistRow(42))
            val created = service.adminCreateUser(
                actor,
                CreateUserRequestDto("viewer", "Viewer", password = password),
            )

            assertNull(created.activationToken)
            assertEquals(UserStatus.ACTIVE, created.user.status)
            assertEquals(listOf("password"), created.user.authMethods)
            assertEquals(listOf(42L), created.user.playlistIds)
            assertEquals(listOf(UserStatus.ACTIVE, UserStatus.DISABLED), created.user.settableStatuses)
            assertEquals(0, db.challenges().activeCount(ChallengeKind.ACTIVATION, 1_700_000_000_000L))
            val credential = assertNotNull(db.credentials().password(created.user.id))
            assertTrue(
                AuthCrypto.verifyPassword(
                    password,
                    credential.hash,
                    credential.salt,
                    credential.memoryKb,
                    credential.iterations,
                    credential.parallelism,
                )
            )
        }
    }

    @Test
    fun invitationCreationRollsBackTheUserWhenItsChallengeCannotBeStored() = runTest {
        withAdmin { service, actor, db, _ ->
            db.useWriterConnection {
                it.usePrepared(
                    """
                    CREATE TRIGGER reject_activation
                    BEFORE INSERT ON auth_challenges
                    WHEN NEW.kind = 'ACTIVATION'
                    BEGIN
                        SELECT RAISE(ABORT, 'activation rejected');
                    END
                    """.trimIndent(),
                ) { statement -> statement.step() }
            }

            assertFailsWith<Exception> {
                service.adminCreateUser(actor, CreateUserRequestDto("viewer", "Viewer"))
            }

            assertNull(db.users().byNormalizedUsername("viewer"))
        }
    }

    @Test
    fun createWithoutPasswordRemainsAnInvitation() = runTest {
        withAdmin { service, actor, db, _ ->
            val created = service.adminCreateUser(
                actor,
                CreateUserRequestDto("viewer", "Viewer"),
            )

            assertNotNull(created.activationToken)
            assertEquals(UserStatus.INVITED, created.user.status)
            assertNull(db.credentials().password(created.user.id))
            assertEquals(1, db.challenges().activeCount(ChallengeKind.ACTIVATION, 1_700_000_000_000L))
        }
    }

    @Test
    fun disabledPasswordAuthenticationRejectsPasswordUserBeforeCreatingIt() = runTest {
        withAdmin(passwordEnabled = false) { service, actor, db, _ ->
            val before = db.users().all()
            val failure = assertFailsWith<LocalAccountProvisioningDisabledException> {
                service.adminCreateUser(
                    actor,
                    CreateUserRequestDto(
                        "viewer",
                        "Viewer",
                        password = "a new sufficiently long password",
                    ),
                )
            }

            assertEquals(
                "Local account creation and credential reset require password authentication to be enabled",
                failure.message,
            )
            assertEquals(before, db.users().all())
            assertNull(db.users().byNormalizedUsername("viewer"))
        }
    }

    @Test
    fun disabledPasswordAuthenticationRejectsInvitationBeforeCreatingIt() = runTest {
        withAdmin(passwordEnabled = false) { service, actor, db, _ ->
            val before = db.users().all()

            assertFailsWith<LocalAccountProvisioningDisabledException> {
                service.adminCreateUser(
                    actor,
                    CreateUserRequestDto("viewer", "Viewer"),
                )
            }

            assertEquals(before, db.users().all())
            assertNull(db.users().byNormalizedUsername("viewer"))
            assertEquals(
                0,
                db.challenges().activeCount(ChallengeKind.ACTIVATION, 1_700_000_000_000L),
            )
        }
    }

    @Test
    fun disabledPasswordAuthenticationRejectsResetWithoutDeletingTheCredential() = runTest {
        withAdmin(passwordEnabled = false) { service, actor, db, enabledService ->
            val password = "a new sufficiently long password"
            val created = enabledService.adminCreateUser(
                actor,
                CreateUserRequestDto("viewer", "Viewer", password = password),
            )
            assertNotNull(db.credentials().password(created.user.id))

            assertFailsWith<LocalAccountProvisioningDisabledException> {
                service.adminResetUser(actor, created.user.id)
            }

            val credential = assertNotNull(db.credentials().password(created.user.id))
            assertTrue(
                AuthCrypto.verifyPassword(
                    password,
                    credential.hash,
                    credential.salt,
                    credential.memoryKb,
                    credential.iterations,
                    credential.parallelism,
                )
            )
            assertEquals(UserStatus.ACTIVE, assertNotNull(db.users().get(created.user.id)).status)
            assertEquals(
                0,
                db.challenges().activeCount(ChallengeKind.PASSWORD_RESET, 1_700_000_000_000L),
            )
        }
    }

    @Test
    fun enabledPasswordAuthenticationStillResetsCredentials() = runTest {
        withAdmin { service, actor, db, _ ->
            val created = service.adminCreateUser(
                actor,
                CreateUserRequestDto(
                    "viewer",
                    "Viewer",
                    password = "a new sufficiently long password",
                ),
            )

            val reset = service.adminResetUser(actor, created.user.id)

            assertTrue(reset.setupToken.isNotBlank())
            assertNull(db.credentials().password(created.user.id))
            assertEquals(UserStatus.INVITED, assertNotNull(db.users().get(created.user.id)).status)
            assertEquals(
                1,
                db.challenges().activeCount(ChallengeKind.PASSWORD_RESET, 1_700_000_000_000L),
            )
        }
    }

    @Test
    fun credentialResetRollsBackWhenItsSetupChallengeCannotBeStored() = runTest {
        withAdmin { service, actor, db, _ ->
            val created = service.adminCreateUser(
                actor,
                CreateUserRequestDto(
                    "viewer",
                    "Viewer",
                    password = "a new sufficiently long password",
                ),
            )
            db.useWriterConnection {
                it.usePrepared(
                    """
                    CREATE TRIGGER reject_password_reset
                    BEFORE INSERT ON auth_challenges
                    WHEN NEW.kind = 'PASSWORD_RESET'
                    BEGIN
                        SELECT RAISE(ABORT, 'reset rejected');
                    END
                    """.trimIndent(),
                ) { statement -> statement.step() }
            }

            assertFailsWith<Exception> {
                service.adminResetUser(actor, created.user.id)
            }

            assertNotNull(db.credentials().password(created.user.id))
            assertEquals(
                UserStatus.ACTIVE,
                assertNotNull(db.users().get(created.user.id)).status,
            )
        }
    }

    @Test
    fun disablingAUserRollsBackWhenTheirSessionsCannotBeRevoked() = runTest {
        withAdmin { service, actor, db, _ ->
            val created = service.adminCreateUser(
                actor,
                CreateUserRequestDto(
                    "viewer",
                    "Viewer",
                    password = "a new sufficiently long password",
                ),
            )
            val user = assertNotNull(db.users().get(created.user.id))
            val session = PersistentSessionService(
                db,
                authConfig(passwordEnabled = true),
                NoopUserStateCleanupCoordinator,
                { 1_700_000_000_000L },
            ).issue(user, AuthMethod.PASSWORD, mfa = true)
            db.useWriterConnection {
                it.usePrepared(
                    """
                    CREATE TRIGGER reject_session_revocation
                    BEFORE UPDATE ON auth_sessions
                    BEGIN
                        SELECT RAISE(ABORT, 'revocation rejected');
                    END
                    """.trimIndent(),
                ) { statement -> statement.step() }
            }

            assertFailsWith<Exception> {
                service.adminUpdateUser(
                    actor,
                    user.id,
                    UpdateUserRequestDto(status = UserStatus.DISABLED),
                )
            }

            assertEquals(UserStatus.ACTIVE, assertNotNull(db.users().get(user.id)).status)
            assertNull(assertNotNull(db.sessions().get(session.row.id)).revokedAtMs)
        }
    }

    @Test
    fun oidcApprovalRollsBackAProvisionedUserWhenTheIdentityCannotBeStored() = runTest {
        withAdmin { service, actor, db, _ ->
            db.oidc().upsertPending(
                PendingOidcIdentityRow(
                    issuer = "https://issuer.example",
                    subject = "subject",
                    usernameClaim = "sso-viewer",
                    displayNameClaim = "SSO Viewer",
                    groupsJson = "[]",
                    adminMapped = false,
                    createdAtMs = 1_700_000_000_000L,
                    updatedAtMs = 1_700_000_000_000L,
                ),
            )
            db.useWriterConnection {
                it.usePrepared(
                    """
                    CREATE TRIGGER reject_oidc_identity
                    BEFORE INSERT ON oidc_identities
                    BEGIN
                        SELECT RAISE(ABORT, 'identity rejected');
                    END
                    """.trimIndent(),
                ) { statement -> statement.step() }
            }

            assertFailsWith<Exception> {
                service.approveOidc(
                    actor,
                    ApproveOidcRequestDto(
                        issuer = "https://issuer.example",
                        subject = "subject",
                    ),
                )
            }

            assertNull(db.users().byNormalizedUsername("sso-viewer"))
            assertNotNull(db.oidc().pending("https://issuer.example", "subject"))
        }
    }

    @Test
    fun invitedAndPendingAreRejectedAsAdministratorSetStatuses() = runTest {
        withAdmin { service, actor, db, _ ->
            val invited = service.adminCreateUser(
                actor,
                CreateUserRequestDto("viewer", "Viewer"),
            ).user

            val invitedFailure = assertFailsWith<UserStatusNotSettableException> {
                service.adminUpdateUser(
                    actor,
                    invited.id,
                    UpdateUserRequestDto(status = UserStatus.INVITED),
                )
            }
            assertTrue(invitedFailure.message.orEmpty().contains("invitation and reset flows"))

            val pendingFailure = assertFailsWith<UserStatusNotSettableException> {
                service.adminUpdateUser(
                    actor,
                    invited.id,
                    UpdateUserRequestDto(status = UserStatus.PENDING),
                )
            }
            assertTrue(pendingFailure.message.orEmpty().contains("legacy value"))
            assertEquals(UserStatus.INVITED, assertNotNull(db.users().get(invited.id)).status)
        }
    }

    @Test
    fun adminResumeTitlesAreResolvedOnceForTheWholeListAndMissingTitlesRemain() = runTest {
        var calls = 0
        var requested = emptySet<String>()
        val resolver: suspend (Collection<String>) -> Map<String, String> = { contentIds ->
            calls++
            requested = contentIds.toSet()
            mapOf("resolved-content" to "A human title")
        }
        withAdmin(resumeTitles = resolver) { service, actor, db, _ ->
            val user = service.adminCreateUser(
                actor,
                CreateUserRequestDto("viewer", "Viewer"),
            ).user
            listOf("resolved-content", "missing-content").forEachIndexed { index, contentId ->
                db.content().insert(
                    ContentIdentityRow(
                        contentId = contentId,
                        playlistId = 1,
                        kind = 1,
                        providerFingerprint = "fingerprint-$index",
                        currentChannelId = null,
                        lastSeenAtMs = 1_700_000_000_000L,
                        retired = index == 1,
                    )
                )
                db.activity().upsertResume(
                    UserResumeRow(
                        userId = user.id,
                        contentId = contentId,
                        positionMs = 10_000L + index,
                        durationMs = 100_000L,
                        updatedAtMs = 1_700_000_000_000L + index,
                    )
                )
            }

            val rows = service.adminResume(actor, user.id).associateBy { it.contentId }

            assertEquals(1, calls)
            assertEquals(setOf("resolved-content", "missing-content"), requested)
            assertEquals("A human title", rows.getValue("resolved-content").title)
            assertNull(rows.getValue("missing-content").title)
            assertEquals(2, rows.size)
        }
    }

    private suspend fun withAdmin(
        passwordEnabled: Boolean = true,
        resumeTitles: suspend (Collection<String>) -> Map<String, String> = { emptyMap() },
        block: suspend (AuthService, Actor, ServerUserDatabase, AuthService) -> Unit,
    ) {
        val dir = Files.createTempDirectory("opentv-admin-lifecycle-test")
        val db = createServerUserDatabase(dir.resolve("server-users.db").toString())
        val now = 1_700_000_000_000L
        val enabledConfig = authConfig(passwordEnabled = true)
        try {
            val bootstrapService = AuthService(db, enabledConfig, dir, { now })
            bootstrapService.initialize()
            val bootstrapToken = Files.readString(dir.resolve("bootstrap.token")).trim()
            val result = bootstrapService.bootstrap(
                BootstrapRequestDto(
                    bootstrapToken,
                    "Admin",
                    "a sufficiently long password",
                    "Administrator",
                ),
                "127.0.0.1",
            )
            val actor = assertNotNull(
                bootstrapService.authenticate(assertNotNull(result.sessionToken))
            ).first
            val service = if (passwordEnabled) {
                AuthService(
                    db,
                    enabledConfig,
                    dir,
                    { now },
                    resumeTitles = resumeTitles,
                )
            } else {
                AuthService(
                    db,
                    authConfig(passwordEnabled = false),
                    dir,
                    { now },
                    resumeTitles = resumeTitles,
                )
            }
            block(service, actor, db, bootstrapService)
        } finally {
            db.close()
            dir.toFile().deleteRecursively()
        }
    }

    private fun authConfig(passwordEnabled: Boolean) = AuthConfig(
        publicUrl = URI("https://tv.example.com"),
        passwordEnabled = passwordEnabled,
        encryptionKey = ByteArray(32) { it.toByte() },
        initialAdmin = null,
        mfaRequiredRoles = emptySet(),
        oidc = null,
        secureCookies = true,
        webAuthnRpId = "tv.example.com",
        webAuthnOrigin = "https://tv.example.com",
        sessionIdleMs = 24 * 60 * 60_000L,
        sessionAbsoluteMs = 30L * 24 * 60 * 60_000L,
    )
}
