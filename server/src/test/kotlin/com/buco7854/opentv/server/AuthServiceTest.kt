package com.buco7854.opentv.server

import com.buco7854.opentv.contract.*
import com.buco7854.opentv.data.db.PlaylistRow
import com.buco7854.opentv.serverdata.db.DefaultPlaylistRow
import com.buco7854.opentv.serverdata.createOpenTvServerDatabase
import com.buco7854.opentv.serverdata.AuthMethod
import com.buco7854.opentv.serverdata.ChallengeKind
import com.buco7854.opentv.serverdata.UserRole
import com.buco7854.opentv.serverdata.UserStatus
import com.buco7854.opentv.serverdata.db.OidcIdentityRow
import com.buco7854.opentv.serverdata.db.RecoveryCodeRow
import com.buco7854.opentv.serverdata.db.TotpCredentialRow
import com.buco7854.opentv.serverdata.db.WebAuthnCredentialRow
import androidx.room.useWriterConnection
import kotlinx.coroutines.test.runTest
import java.net.URI
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class AuthServiceTest {
    @Test
    fun bootstrapRequiresMfaCopiesTemplateAndIssuesRevocableSession() = runTest {
        val dir = Files.createTempDirectory("opentv-auth-test")
        val db = createOpenTvServerDatabase(dir.resolve("opentv.db").toString())
        var now = 1_700_000_000_000L
        var revokedSession: String? = null
        val cleanup = object : UserStateCleanupCoordinator {
            override suspend fun sessionRevoked(userId: String, authSessionId: String?) {
                revokedSession = authSessionId
            }
            override suspend fun playlistGrantRevoked(userId: String, playlistId: Long) = Unit
            override suspend fun userDeleted(userId: String) = Unit
            override suspend fun playlistDeleting(playlistId: Long) = Unit
            override suspend fun <T> admitPlayback(block: suspend () -> T): T = block()
        }
        val service = AuthService(db, authConfig(), dir, { now }, cleanup = cleanup)
        try {
            db.playlistDao().insert(PlaylistRow(id = 42, name = "Default", url = null))
            db.grants().addDefault(DefaultPlaylistRow(42))
            service.initialize()
            val bootstrapToken = Files.readString(dir.resolve("bootstrap.token")).trim()
            val first = service.bootstrap(
                BootstrapRequestDto(
                    bootstrapToken,
                    "Admin",
                    "a sufficiently long password",
                    "Administrator",
                ),
                "127.0.0.1",
            )
            assertEquals("ENROLLMENT_REQUIRED", first.flow.status)
            assertEquals("challenge_required", first.flow.code)
            assertNull(first.sessionToken)

            val enrollment = service.startTotpEnrollment(
                requireNotNull(first.flow.challenge),
                "127.0.0.1",
            )
            val code = AuthCrypto.totp(
                AuthCrypto.decodeBase32(enrollment.secret),
                now / 30_000L,
            )
            val completed = service.completeTotpEnrollment(
                TotpCompleteRequestDto(enrollment.challenge, code),
                "127.0.0.1",
            )
            assertEquals("AUTHENTICATED", completed.flow.status)
            assertEquals(10, completed.flow.recoveryCodes.size)
            assertEquals(listOf(42L), completed.flow.user?.playlistIds)
            assertTrue(!Files.exists(dir.resolve("bootstrap.token")))

            val authenticated = service.authenticate(assertNotNull(completed.sessionToken))
            assertEquals("Admin", authenticated?.first?.username)
            val selfDisable = assertFailsWith<SelfLockoutForbiddenException> {
                service.adminUpdateUser(
                    requireNotNull(authenticated).first,
                    authenticated.first.userId,
                    UpdateUserRequestDto(status = UserStatus.DISABLED),
                )
            }
            assertEquals("status", selfDisable.field)
            assertFailsWith<LastAdminException> {
                service.adminResetUser(
                    requireNotNull(authenticated).first,
                    authenticated.first.userId,
                )
            }
            service.logout(requireNotNull(authenticated).first, all = false)
            assertEquals(authenticated.first.authSessionId, revokedSession)
            assertNull(service.authenticate(completed.sessionToken))

            val secondLogin = service.passwordLogin(
                PasswordLoginRequestDto("Admin", "a sufficiently long password"),
                "127.0.0.1",
            )
            assertEquals("MFA_REQUIRED", secondLogin.flow.status)
            assertFailsWith<InvalidCredentialsException> {
                service.completeTotp(
                    TotpCompleteRequestDto(requireNotNull(secondLogin.flow.challenge), code),
                    "127.0.0.1",
                )
            }
            now += 30_000
            val thirdLogin = service.passwordLogin(
                PasswordLoginRequestDto("Admin", "a sufficiently long password"),
                "127.0.0.1",
            )
            val freshCode = AuthCrypto.totp(
                AuthCrypto.decodeBase32(enrollment.secret),
                now / 30_000L,
            )
            val completedTotp = service.completeTotp(
                TotpCompleteRequestDto(requireNotNull(thirdLogin.flow.challenge), freshCode),
                "127.0.0.1",
            )
            assertEquals("AUTHENTICATED", completedTotp.flow.status)
            assertNotNull(completedTotp.sessionToken)
        } finally {
            db.close()
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun invalidMfaChallengesContributeToTheIpRateLimit() = runTest {
        val dir = Files.createTempDirectory("opentv-auth-limit-test")
        val db = createOpenTvServerDatabase(dir.resolve("opentv.db").toString())
        val service = AuthService(db, authConfig(), dir)
        try {
            repeat(5) {
                assertFailsWith<InvalidChallengeException> {
                    service.completeTotp(
                        TotpCompleteRequestDto("not-a-real-challenge-$it", "000000"),
                        "203.0.113.10",
                    )
                }
            }
            assertFailsWith<AuthRateLimitedException> {
                service.completeRecovery(
                    RecoveryCompleteRequestDto("another-invalid-challenge", "INVALID"),
                    "203.0.113.10",
                )
            }
        } finally {
            db.close()
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun accountSecurityIsReachableWithoutMfaButOnlyRightAfterSigningIn() = runTest {
        val dir = Files.createTempDirectory("opentv-auth-stepup-test")
        val db = createOpenTvServerDatabase(dir.resolve("opentv.db").toString())
        var now = 1_700_000_000_000L
        val service = AuthService(db, authConfig(mfaRequiredRoles = emptySet()), dir, { now })
        try {
            service.initialize()
            val bootstrapToken = Files.readString(dir.resolve("bootstrap.token")).trim()
            val created = service.bootstrap(
                BootstrapRequestDto(bootstrapToken, "Admin", "a sufficiently long password", "Administrator"),
                "127.0.0.1",
            )
            assertEquals("AUTHENTICATED", created.flow.status)
            val actor = requireNotNull(service.authenticate(assertNotNull(created.sessionToken))).first

            val changed = service.changePassword(
                actor,
                PasswordChangeRequestDto("another sufficiently long password"),
            )
            assertEquals("AUTHENTICATED", changed.flow.status)

            val fresh = requireNotNull(service.authenticate(assertNotNull(changed.sessionToken))).first
            now += 5 * 60_000L + 1
            assertFailsWith<ForbiddenApiException> {
                service.changePassword(fresh, PasswordChangeRequestDto("a third sufficiently long password"))
            }

            val again = service.passwordLogin(
                PasswordLoginRequestDto("Admin", "another sufficiently long password"),
                "127.0.0.1",
            )
            assertEquals("AUTHENTICATED", again.flow.status)
            val reauthenticated =
                requireNotNull(service.authenticate(assertNotNull(again.sessionToken))).first
            service.changePassword(
                reauthenticated,
                PasswordChangeRequestDto("a third sufficiently long password"),
            )
        } finally {
            db.close()
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun accountCredentialsGateTotpAndRecoveryWithoutRestrictingPasskeys() = runTest {
        val dir = Files.createTempDirectory("opentv-auth-account-credentials-test")
        val db = createOpenTvServerDatabase(dir.resolve("opentv.db").toString())
        val now = 1_700_000_000_000L
        val config = authConfig(mfaRequiredRoles = emptySet(), oidc = oidcConfig())
        try {
            val service = AuthService(db, config, dir, { now })
            val user = service.createUser(
                "oidc-user",
                "OIDC user",
                UserStatus.ACTIVE,
                UserRole.USER,
                now,
            )
            db.oidc().upsert(
                OidcIdentityRow(
                    issuer = config.oidc!!.issuer.toString(),
                    subject = "subject",
                    userId = user.id,
                    usernameClaim = user.username,
                    displayNameClaim = user.displayName,
                    groupsJson = "[]",
                    adminMapped = false,
                    updatedAtMs = now,
                ),
            )
            val session = PersistentSessionService(
                db,
                config,
                NoopUserStateCleanupCoordinator,
                { now },
            ).issue(user, AuthMethod.OIDC, mfa = true)
            val actor = assertNotNull(service.authenticate(session.token)).first

            val passwordless = service.current(actor)
            assertEquals(AuthMethod.OIDC, passwordless.authMethod)
            assertFalse(passwordless.hasPassword)
            assertFailsWith<PasswordCredentialRequiredException> {
                service.startAdditionalTotpEnrollment(actor)
            }
            assertFailsWith<PasswordCredentialRequiredException> {
                service.regenerateRecoveryCodes(actor)
            }

            val passkeyOptions = WebAuthnService(db, service, config)
                .additionalRegistrationOptions(actor)
            assertEquals(user.username, passkeyOptions.user?.name)
            assertTrue(passkeyOptions.serverChallenge.isNotBlank())

            AuthCredentialService(db, config, { now }).setPassword(
                user.id,
                "a sufficiently long password",
                now,
            )
            val withPassword = service.current(actor)
            assertEquals(AuthMethod.OIDC, withPassword.authMethod)
            assertTrue(withPassword.hasPassword)
            assertTrue(service.startAdditionalTotpEnrollment(actor).challenge.isNotBlank())
        } finally {
            db.close()
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun lastPasskeyCannotBeDeletedWhenPasswordLoginIsDisabledAndWebAuthnSessionsSurvive() = runTest {
        val dir = Files.createTempDirectory("opentv-auth-passkey-disabled-test")
        val db = createOpenTvServerDatabase(dir.resolve("opentv.db").toString())
        var now = 1_700_000_000_000L
        try {
            val enabledConfig = authConfig(mfaRequiredRoles = emptySet())
            val enabled = AuthService(db, enabledConfig, dir, { now })
            enabled.initialize()
            val bootstrapToken = Files.readString(dir.resolve("bootstrap.token")).trim()
            val passwordLogin = enabled.bootstrap(
                BootstrapRequestDto(
                    bootstrapToken,
                    "Admin",
                    "a sufficiently long password",
                    "Administrator",
                ),
                "127.0.0.1",
            )
            val user = assertNotNull(db.users().byNormalizedUsername("admin"))
            val credential = passkey(user.id, now)
            db.credentials().upsertWebAuthn(credential)
            val passkeySession = PersistentSessionService(
                db,
                enabledConfig,
                NoopUserStateCleanupCoordinator,
                { now },
            ).issue(user, AuthMethod.WEBAUTHN, mfa = true)

            val disabledConfig = authConfig(
                mfaRequiredRoles = emptySet(),
                passwordEnabled = false,
                oidc = oidcConfig(),
            )
            val disabled = AuthService(db, disabledConfig, dir, { now })
            disabled.initialize()

            assertNull(disabled.authenticate(passwordLogin.sessionToken))
            val actor = assertNotNull(disabled.authenticate(passkeySession.token)).first
            assertEquals(AuthMethod.WEBAUTHN, actor.authMethod)
            assertFailsWith<LastFactorException> {
                disabled.deleteWebAuthnCredential(actor, credential.credentialId)
            }
            assertEquals(1, db.credentials().webAuthn(user.id).size)
            assertNotNull(disabled.authenticate(passkeySession.token))
        } finally {
            db.close()
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun aPersistedOidcIdentityIsNotASignInFactorWhenOidcIsDisabled() = runTest {
        val dir = Files.createTempDirectory("opentv-auth-disabled-oidc-factor-test")
        val db = createOpenTvServerDatabase(dir.resolve("opentv.db").toString())
        val now = 1_700_000_000_000L
        try {
            val enabledConfig = authConfig(mfaRequiredRoles = emptySet())
            val enabled = AuthService(db, enabledConfig, dir, { now })
            enabled.initialize()
            val bootstrapToken = Files.readString(dir.resolve("bootstrap.token")).trim()
            enabled.bootstrap(
                BootstrapRequestDto(
                    bootstrapToken,
                    "Admin",
                    "a sufficiently long password",
                    "Administrator",
                ),
                "127.0.0.1",
            )
            val user = assertNotNull(db.users().byNormalizedUsername("admin"))
            val credential = passkey(user.id, now)
            db.credentials().upsertWebAuthn(credential)
            db.oidc().upsert(
                OidcIdentityRow(
                    issuer = "https://disabled-issuer.example",
                    subject = "persisted",
                    userId = user.id,
                    usernameClaim = user.username,
                    displayNameClaim = user.displayName,
                    groupsJson = "[]",
                    adminMapped = false,
                    updatedAtMs = now,
                ),
            )
            val disabledConfig = authConfig(
                mfaRequiredRoles = emptySet(),
                passwordEnabled = false,
                oidc = null,
            )
            val session = PersistentSessionService(
                db,
                disabledConfig,
                NoopUserStateCleanupCoordinator,
                { now },
            ).issue(user, AuthMethod.WEBAUTHN, mfa = true)
            val disabled = AuthService(db, disabledConfig, dir, { now })
            disabled.initialize()
            val actor = assertNotNull(disabled.authenticate(session.token)).first

            assertFailsWith<LastFactorException> {
                disabled.deleteWebAuthnCredential(actor, credential.credentialId)
            }

            assertEquals(1, db.credentials().webAuthn(user.id).size)
            assertNotNull(disabled.authenticate(session.token))
        } finally {
            db.close()
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun oidcGroupRemappingRollsBackTheIdentityWhenTheEffectiveRoleCannotBeStored() = runTest {
        val dir = Files.createTempDirectory("opentv-auth-oidc-role-transaction-test")
        val db = createOpenTvServerDatabase(dir.resolve("opentv.db").toString())
        val now = 1_700_000_000_000L
        val config = authConfig(mfaRequiredRoles = emptySet(), oidc = oidcConfig())
        try {
            val service = AuthService(db, config, dir, { now })
            val user = service.createUser(
                "oidc-admin",
                "OIDC administrator",
                UserStatus.ACTIVE,
                UserRole.USER,
                now,
            ).copy(oidcAdmin = true)
            db.users().update(user)
            db.oidc().upsert(
                OidcIdentityRow(
                    issuer = config.oidc!!.issuer.toString(),
                    subject = "subject",
                    userId = user.id,
                    usernameClaim = user.username,
                    displayNameClaim = user.displayName,
                    groupsJson = "[\"admins\"]",
                    adminMapped = true,
                    updatedAtMs = now,
                ),
            )
            db.useWriterConnection {
                it.usePrepared(
                    """
                    CREATE TRIGGER reject_oidc_role_update
                    BEFORE UPDATE ON users
                    BEGIN
                        SELECT RAISE(ABORT, 'role update rejected');
                    END
                    """.trimIndent(),
                ) { statement -> statement.step() }
            }

            assertFailsWith<Exception> {
                service.completeOidc(
                    issuer = config.oidc.issuer.toString(),
                    subject = "subject",
                    usernameClaim = user.username,
                    displayNameClaim = user.displayName,
                    groups = emptyList(),
                    adminMapped = false,
                )
            }

            assertTrue(
                assertNotNull(db.oidc().get(config.oidc.issuer.toString(), "subject")).adminMapped,
            )
            assertTrue(assertNotNull(db.users().get(user.id)).oidcAdmin)
        } finally {
            db.close()
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun deletingPasskeyRotatesTheActingSessionAndRevokesTheOthers() = runTest {
        val dir = Files.createTempDirectory("opentv-auth-passkey-delete-test")
        val db = createOpenTvServerDatabase(dir.resolve("opentv.db").toString())
        var now = 1_700_000_000_000L
        try {
            val config = authConfig(mfaRequiredRoles = emptySet())
            val service = AuthService(db, config, dir, { now })
            service.initialize()
            val bootstrapToken = Files.readString(dir.resolve("bootstrap.token")).trim()
            val login = service.bootstrap(
                BootstrapRequestDto(
                    bootstrapToken,
                    "Admin",
                    "a sufficiently long password",
                    "Administrator",
                ),
                "127.0.0.1",
            )
            val actor = assertNotNull(service.authenticate(assertNotNull(login.sessionToken))).first
            val credential = passkey(actor.userId, now)
            db.credentials().upsertWebAuthn(credential)
            val otherLogin = service.passwordLogin(
                PasswordLoginRequestDto("Admin", "a sufficiently long password"),
                "127.0.0.2",
            )

            val removed = service.deleteWebAuthnCredential(actor, credential.credentialId)

            assertTrue(db.credentials().webAuthn(actor.userId).isEmpty())
            assertNull(service.authenticate(login.sessionToken))
            assertNull(service.authenticate(otherLogin.sessionToken))
            assertEquals("AUTHENTICATED", removed.flow.status)
            assertTrue(removed.flow.recoveryCodes.isEmpty())
            assertNotNull(service.authenticate(assertNotNull(removed.sessionToken)))
        } finally {
            db.close()
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun passkeyListingDoesNotRequireRecentAuthenticationButDeletionDoes() = runTest {
        val dir = Files.createTempDirectory("opentv-auth-passkey-list-test")
        val db = createOpenTvServerDatabase(dir.resolve("opentv.db").toString())
        var now = 1_700_000_000_000L
        try {
            val config = authConfig(mfaRequiredRoles = emptySet())
            val service = AuthService(db, config, dir, { now })
            service.initialize()
            val bootstrapToken = Files.readString(dir.resolve("bootstrap.token")).trim()
            val login = service.bootstrap(
                BootstrapRequestDto(
                    bootstrapToken,
                    "Admin",
                    "a sufficiently long password",
                    "Administrator",
                ),
                "127.0.0.1",
            )
            val actor = assertNotNull(service.authenticate(assertNotNull(login.sessionToken))).first
            val credential = passkey(actor.userId, now)
            db.credentials().upsertWebAuthn(credential)
            now += 5 * 60_000L + 1

            assertEquals("Test passkey", service.webAuthnCredentials(actor).single().label)
            assertFailsWith<ForbiddenApiException> {
                service.deleteWebAuthnCredential(actor, credential.credentialId)
            }
            assertEquals(1, db.credentials().webAuthn(actor.userId).size)
        } finally {
            db.close()
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun additionalTotpWorksWithAPasskeyAndPreservesRecoveryCodes() = runTest {
        val dir = Files.createTempDirectory("opentv-auth-totp-add-test")
        val db = createOpenTvServerDatabase(dir.resolve("opentv.db").toString())
        var now = 1_700_000_000_000L
        try {
            val config = authConfig(mfaRequiredRoles = emptySet())
            val service = AuthService(db, config, dir, { now })
            service.initialize()
            val bootstrapToken = Files.readString(dir.resolve("bootstrap.token")).trim()
            val login = service.bootstrap(
                BootstrapRequestDto(
                    bootstrapToken,
                    "Admin",
                    "a sufficiently long password",
                    "Administrator",
                ),
                "127.0.0.1",
            )
            val originalToken = assertNotNull(login.sessionToken)
            val actor = assertNotNull(service.authenticate(originalToken)).first
            db.credentials().upsertWebAuthn(passkey(actor.userId, now))
            db.credentials().insertRecoveryCodes(
                listOf(
                    RecoveryCodeRow(
                        id = "existing-recovery",
                        userId = actor.userId,
                        codeHash = AuthCrypto.hashToken("EXISTING-RECOVERY"),
                        createdAtMs = now,
                        usedAtMs = null,
                    ),
                ),
            )
            assertEquals(TotpStatusDto(false, null), service.totpStatus(actor))
            val recoveryBefore = db.credentials().unusedRecoveryCodes(actor.userId)

            val enrollment = service.startAdditionalTotpEnrollment(actor)
            val challenge = assertNotNull(
                db.challenges().byToken(
                    ChallengeKind.TOTP_ENROLL,
                    AuthCrypto.hashToken(enrollment.challenge),
                ),
            )
            val credentialId = challenge.payloadJson.substringBefore('|')
            val pending = assertNotNull(db.credentials().totp(credentialId))
            assertContentEquals(
                AuthCrypto.decodeBase32(enrollment.secret),
                AuthCrypto.decrypt(
                    requireNotNull(config.encryptionKey),
                    "totp:${actor.userId}:$credentialId",
                    pending.encryptedSecret,
                ),
            )
            val correctCode = AuthCrypto.totp(
                AuthCrypto.decodeBase32(enrollment.secret),
                now / 30_000L,
            )
            val wrongCode = if (correctCode == "000000") "000001" else "000000"
            assertFailsWith<InvalidCredentialsException> {
                service.completeAdditionalTotpEnrollment(
                    actor,
                    TotpCompleteRequestDto(enrollment.challenge, wrongCode),
                    "127.0.0.1",
                )
            }

            val completed = service.completeAdditionalTotpEnrollment(
                actor,
                TotpCompleteRequestDto(enrollment.challenge, correctCode),
                "127.0.0.1",
            )

            assertEquals("AUTHENTICATED", completed.flow.status)
            assertTrue(completed.flow.recoveryCodes.isEmpty())
            assertNull(service.authenticate(originalToken))
            val freshActor = assertNotNull(
                service.authenticate(assertNotNull(completed.sessionToken))
            ).first
            val confirmed = db.credentials().confirmedTotp(actor.userId).single()
            assertEquals(
                TotpStatusDto(true, confirmed.createdAtMs),
                service.totpStatus(freshActor),
            )
            val recoveryAfter = db.credentials().unusedRecoveryCodes(actor.userId)
            assertEquals(recoveryBefore.map { it.id }, recoveryAfter.map { it.id })
            assertContentEquals(recoveryBefore.single().codeHash, recoveryAfter.single().codeHash)
            assertFailsWith<TotpExistsException> {
                service.startAdditionalTotpEnrollment(freshActor)
            }
            assertFailsWith<InvalidChallengeException> {
                service.completeAdditionalTotpEnrollment(
                    actor,
                    TotpCompleteRequestDto(enrollment.challenge, correctCode),
                    "127.0.0.1",
                )
            }
        } finally {
            db.close()
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun additionalTotpStartRequiresARecentSession() = runTest {
        val dir = Files.createTempDirectory("opentv-auth-totp-add-recent-test")
        val db = createOpenTvServerDatabase(dir.resolve("opentv.db").toString())
        var now = 1_700_000_000_000L
        try {
            val config = authConfig(mfaRequiredRoles = emptySet())
            val service = AuthService(db, config, dir, { now })
            service.initialize()
            val bootstrapToken = Files.readString(dir.resolve("bootstrap.token")).trim()
            val login = service.bootstrap(
                BootstrapRequestDto(
                    bootstrapToken,
                    "Admin",
                    "a sufficiently long password",
                    "Administrator",
                ),
                "127.0.0.1",
            )
            val actor = assertNotNull(
                service.authenticate(assertNotNull(login.sessionToken))
            ).first
            now += 5 * 60_000L + 1

            assertEquals(TotpStatusDto(false, null), service.totpStatus(actor))
            assertFailsWith<ForbiddenApiException> {
                service.startAdditionalTotpEnrollment(actor)
            }
        } finally {
            db.close()
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun deletingTotpWithAPasskeyRotatesTheActingSessionAndRevokesTheOthers() = runTest {
        val dir = Files.createTempDirectory("opentv-auth-totp-delete-test")
        val db = createOpenTvServerDatabase(dir.resolve("opentv.db").toString())
        val now = 1_700_000_000_000L
        try {
            val config = authConfig(mfaRequiredRoles = emptySet())
            val service = AuthService(db, config, dir, { now })
            service.initialize()
            val bootstrapToken = Files.readString(dir.resolve("bootstrap.token")).trim()
            val login = service.bootstrap(
                BootstrapRequestDto(
                    bootstrapToken,
                    "Admin",
                    "a sufficiently long password",
                    "Administrator",
                ),
                "127.0.0.1",
            )
            val actor = assertNotNull(service.authenticate(assertNotNull(login.sessionToken))).first
            db.credentials().upsertWebAuthn(passkey(actor.userId, now))
            db.credentials().upsertTotp(totp(actor.userId, now))
            val otherLogin = service.passwordLogin(
                PasswordLoginRequestDto("Admin", "a sufficiently long password"),
                "127.0.0.2",
            )

            val removed = service.deleteTotp(actor)

            assertTrue(db.credentials().confirmedTotp(actor.userId).isEmpty())
            assertNull(service.authenticate(login.sessionToken))
            assertNull(service.authenticate(otherLogin.sessionToken))
            assertEquals("AUTHENTICATED", removed.flow.status)
            assertTrue(removed.flow.recoveryCodes.isEmpty())
            assertEquals(actor.authMethod, removed.flow.user?.authMethod)
            assertNotNull(service.authenticate(assertNotNull(removed.sessionToken)))
        } finally {
            db.close()
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun deletingTotpIsRefusedWhenItIsTheLastRequiredMfaFactor() = runTest {
        val dir = Files.createTempDirectory("opentv-auth-totp-delete-mfa-test")
        val db = createOpenTvServerDatabase(dir.resolve("opentv.db").toString())
        val now = 1_700_000_000_000L
        try {
            val initialConfig = authConfig(mfaRequiredRoles = emptySet())
            val initial = AuthService(db, initialConfig, dir, { now })
            initial.initialize()
            val bootstrapToken = Files.readString(dir.resolve("bootstrap.token")).trim()
            initial.bootstrap(
                BootstrapRequestDto(
                    bootstrapToken,
                    "Admin",
                    "a sufficiently long password",
                    "Administrator",
                ),
                "127.0.0.1",
            )
            val user = assertNotNull(db.users().byNormalizedUsername("admin"))
            db.credentials().upsertTotp(totp(user.id, now))

            val requiredConfig = authConfig()
            val service = AuthService(db, requiredConfig, dir, { now })
            service.initialize()
            val session = PersistentSessionService(
                db,
                requiredConfig,
                NoopUserStateCleanupCoordinator,
                { now },
            ).issue(user, AuthMethod.PASSWORD, mfa = true)
            val actor = assertNotNull(service.authenticate(session.token)).first

            assertFailsWith<LastFactorException> {
                service.deleteTotp(actor)
            }
            assertEquals(1, db.credentials().confirmedTotp(user.id).size)
            assertNotNull(service.authenticate(session.token))
        } finally {
            db.close()
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun deletingTotpIsRefusedWhenNoUsableSignInMethodRemains() = runTest {
        val dir = Files.createTempDirectory("opentv-auth-totp-delete-signin-test")
        val db = createOpenTvServerDatabase(dir.resolve("opentv.db").toString())
        val now = 1_700_000_000_000L
        try {
            val initialConfig = authConfig(mfaRequiredRoles = emptySet())
            val initial = AuthService(db, initialConfig, dir, { now })
            initial.initialize()
            val bootstrapToken = Files.readString(dir.resolve("bootstrap.token")).trim()
            initial.bootstrap(
                BootstrapRequestDto(
                    bootstrapToken,
                    "Admin",
                    "a sufficiently long password",
                    "Administrator",
                ),
                "127.0.0.1",
            )
            val user = assertNotNull(db.users().byNormalizedUsername("admin"))
            db.credentials().upsertTotp(totp(user.id, now))

            val disabledConfig = authConfig(
                mfaRequiredRoles = emptySet(),
                passwordEnabled = false,
                oidc = oidcConfig(),
            )
            val service = AuthService(db, disabledConfig, dir, { now })
            service.initialize()
            val session = PersistentSessionService(
                db,
                disabledConfig,
                NoopUserStateCleanupCoordinator,
                { now },
            ).issue(user, AuthMethod.OIDC, mfa = true)
            val actor = assertNotNull(service.authenticate(session.token)).first

            assertFailsWith<LastFactorException> {
                service.deleteTotp(actor)
            }
            assertEquals(1, db.credentials().confirmedTotp(user.id).size)
            assertNotNull(service.authenticate(session.token))
        } finally {
            db.close()
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun deletingTotpRequiresARecentSession() = runTest {
        val dir = Files.createTempDirectory("opentv-auth-totp-delete-recent-test")
        val db = createOpenTvServerDatabase(dir.resolve("opentv.db").toString())
        var now = 1_700_000_000_000L
        try {
            val config = authConfig(mfaRequiredRoles = emptySet())
            val service = AuthService(db, config, dir, { now })
            service.initialize()
            val bootstrapToken = Files.readString(dir.resolve("bootstrap.token")).trim()
            val login = service.bootstrap(
                BootstrapRequestDto(
                    bootstrapToken,
                    "Admin",
                    "a sufficiently long password",
                    "Administrator",
                ),
                "127.0.0.1",
            )
            val actor = assertNotNull(service.authenticate(assertNotNull(login.sessionToken))).first
            db.credentials().upsertWebAuthn(passkey(actor.userId, now))
            db.credentials().upsertTotp(totp(actor.userId, now))
            now += 5 * 60_000L + 1

            assertFailsWith<ForbiddenApiException> {
                service.deleteTotp(actor)
            }
            assertEquals(1, db.credentials().confirmedTotp(actor.userId).size)
        } finally {
            db.close()
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun deletingTotpInvalidatesAnEnrollmentInFlight() = runTest {
        val dir = Files.createTempDirectory("opentv-auth-totp-delete-enrollment-test")
        val db = createOpenTvServerDatabase(dir.resolve("opentv.db").toString())
        val now = 1_700_000_000_000L
        try {
            val config = authConfig(mfaRequiredRoles = emptySet())
            val service = AuthService(db, config, dir, { now })
            service.initialize()
            val bootstrapToken = Files.readString(dir.resolve("bootstrap.token")).trim()
            val login = service.bootstrap(
                BootstrapRequestDto(
                    bootstrapToken,
                    "Admin",
                    "a sufficiently long password",
                    "Administrator",
                ),
                "127.0.0.1",
            )
            val actor = assertNotNull(service.authenticate(assertNotNull(login.sessionToken))).first
            db.credentials().upsertWebAuthn(passkey(actor.userId, now))
            val enrollment = service.startAdditionalTotpEnrollment(actor)
            val challenge = assertNotNull(
                db.challenges().byToken(
                    ChallengeKind.TOTP_ENROLL,
                    AuthCrypto.hashToken(enrollment.challenge),
                ),
            )
            val code = AuthCrypto.totp(
                AuthCrypto.decodeBase32(enrollment.secret),
                now / 30_000L,
            )

            val removed = service.deleteTotp(actor)

            assertNotNull(db.challenges().get(challenge.id)?.consumedAtMs)
            assertNull(db.credentials().totp(challenge.payloadJson.substringBefore('|')))
            assertFailsWith<InvalidChallengeException> {
                service.completeAdditionalTotpEnrollment(
                    actor,
                    TotpCompleteRequestDto(enrollment.challenge, code),
                    "127.0.0.1",
                )
            }
            assertNotNull(service.authenticate(assertNotNull(removed.sessionToken)))
        } finally {
            db.close()
            dir.toFile().deleteRecursively()
        }
    }

    private fun totp(userId: String, now: Long) = TotpCredentialRow(
        id = "test-totp",
        userId = userId,
        encryptedSecret = byteArrayOf(1, 2, 3),
        label = "Authenticator",
        confirmed = true,
        lastAcceptedStep = null,
        createdAtMs = now,
    )

    private fun passkey(userId: String, now: Long) = WebAuthnCredentialRow(
        credentialId = byteArrayOf(1, 3, 3, 7),
        userId = userId,
        publicKeyCose = byteArrayOf(1),
        signCount = 0,
        transportsJson = "[]",
        backupEligible = true,
        backedUp = true,
        label = "Test passkey",
        createdAtMs = now,
        lastUsedAtMs = null,
    )

    private fun oidcConfig() = OidcConfig(
        issuer = URI("https://issuer.example.test"),
        clientId = "client",
        clientSecret = "secret",
        scopes = listOf("openid"),
        usernameClaim = "preferred_username",
        displayNameClaim = "name",
        groupsClaim = "groups",
        adminGroups = setOf("admins"),
        autoProvision = false,
    )

    private fun authConfig(
        mfaRequiredRoles: Set<String> = setOf("USER", "ADMIN"),
        passwordEnabled: Boolean = true,
        oidc: OidcConfig? = null,
    ) = AuthConfig(
        publicUrl = URI("http://localhost:8080"),
        passwordEnabled = passwordEnabled,
        encryptionKey = ByteArray(32) { it.toByte() },
        initialAdmin = null,
        mfaRequiredRoles = mfaRequiredRoles,
        oidc = oidc,
        secureCookies = false,
        webAuthnRpId = "localhost",
        webAuthnOrigin = "http://localhost:8080",
        sessionIdleMs = 24 * 60 * 60_000L,
        sessionAbsoluteMs = 30L * 24 * 60 * 60_000L,
    )
}
