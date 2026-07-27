package com.buco7854.opentv.server

import com.buco7854.opentv.serverdata.AuthMethod
import com.buco7854.opentv.serverdata.ClientKind
import com.buco7854.opentv.serverdata.UserRole
import com.buco7854.opentv.serverdata.UserStatus
import com.buco7854.opentv.serverdata.createServerUserDatabase
import com.buco7854.opentv.serverdata.db.ServerUserDatabase
import kotlinx.coroutines.test.runTest
import java.net.URI
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DeviceLinkServiceTest {
    @Test
    fun approveThenPollIssuesOneLinkedSessionWithApproverMethod() = runTest {
        withFixture {
            // The QR has to send the phone to the address the waiting device reached us
            // on, not to whatever the server was configured with.
            val started = links.start(
                DeviceLinkStartRequestDto("Model\u00003"),
                "Tesla Browser",
                "192.0.2.40",
                URI("http://opentv.local:8080"),
            )
            assertEquals(
                "http://opentv.local:8080/link#t=${started.linkToken}",
                started.verificationUriComplete,
            )
            links.lookup(
                actor,
                DeviceLinkTokenRequestDto(started.linkToken),
                "198.51.100.10",
            )
            links.approve(
                actor,
                DeviceLinkTokenRequestDto(started.linkToken),
                "198.51.100.10",
            )

            val claimed = links.poll(DeviceLinkPollRequestDto(started.pollToken))

            assertEquals("APPROVED", claimed.status.status)
            assertEquals(actor.displayName, claimed.status.preview?.displayName)
            assertEquals(actor.username, claimed.status.preview?.username)
            assertEquals("AUTHENTICATED", claimed.status.flow?.status)
            assertEquals(AuthMethod.PASSWORD, claimed.status.flow?.user?.authMethod)
            assertEquals(ClientKind.LINKED_DEVICE, claimed.status.flow?.user?.clientKind)
            val session = assertNotNull(
                db.sessions().byTokenHash(AuthCrypto.hashToken(assertNotNull(claimed.sessionToken)))
            )
            assertEquals(ClientKind.LINKED_DEVICE, session.clientKind)
            assertEquals("Model3", session.deviceName)
            assertEquals(actor.authMethod, session.authMethod)
            assertNotNull(auth.authenticate(claimed.sessionToken))
        }
    }

    @Test
    fun lookupClaimsTheRequestAndMovesItToScanned() = runTest {
        withFixture {
            val started = links.start(
                DeviceLinkStartRequestDto("Roadster"),
                "Tesla Browser",
                "192.0.2.41",
            )
            val pending = links.poll(DeviceLinkPollRequestDto(started.pollToken))
            assertEquals("PENDING", pending.status.status)
            assertNull(pending.status.preview)
            assertNull(pending.status.flow)
            clock.now += pending.status.intervalMs

            val lookup = links.lookup(
                actor,
                DeviceLinkTokenRequestDto(started.linkToken),
                "198.51.100.11",
            )
            links.lookup(
                actor,
                DeviceLinkTokenRequestDto(started.linkToken),
                "198.51.100.11",
            )
            val scanned = links.poll(DeviceLinkPollRequestDto(started.pollToken))

            assertEquals("Roadster", lookup.deviceName)
            assertEquals("Tesla Browser", lookup.userAgent)
            assertEquals("192.0.2.41", lookup.ip)
            assertEquals("SCANNED", scanned.status.status)
            assertEquals(actor.displayName, scanned.status.preview?.displayName)
            assertEquals(actor.username, scanned.status.preview?.username)
            assertNull(scanned.status.flow)
            assertEquals(1_000L, scanned.status.intervalMs)
        }
    }

    @Test
    fun aSecondUserCannotClaimAClaimedRequest() = runTest {
        withFixture {
            val started = links.start(DeviceLinkStartRequestDto(), null, "192.0.2.42")
            val second = secondActor()
            links.lookup(
                actor,
                DeviceLinkTokenRequestDto(started.linkToken),
                "198.51.100.12",
            )

            assertFailsWith<InvalidChallengeException> {
                links.lookup(
                    second,
                    DeviceLinkTokenRequestDto(started.linkToken),
                    "198.51.100.13",
                )
            }

            clock.now += started.intervalMs
            val scanned = links.poll(DeviceLinkPollRequestDto(started.pollToken))
            assertEquals(actor.username, scanned.status.preview?.username)
        }
    }

    @Test
    fun approveWithoutLookupIsRejected() = runTest {
        withFixture {
            val started = links.start(DeviceLinkStartRequestDto(), null, "192.0.2.43")

            assertFailsWith<InvalidChallengeException> {
                links.approve(
                    actor,
                    DeviceLinkTokenRequestDto(started.linkToken),
                    "198.51.100.14",
                )
            }
            val pending = links.poll(DeviceLinkPollRequestDto(started.pollToken))
            assertEquals("PENDING", pending.status.status)
            assertNull(pending.status.preview)
        }
    }

    @Test
    fun pendingPollsNeverRevealAUserAndPollingIntervalIsEnforced() = runTest {
        withFixture {
            val started = links.start(DeviceLinkStartRequestDto(), null, "192.0.2.44")

            val pending = links.poll(DeviceLinkPollRequestDto(started.pollToken))

            assertEquals("PENDING", pending.status.status)
            assertNull(pending.status.preview)
            assertNull(pending.sessionToken)
            assertFailsWith<AuthRateLimitedException> {
                links.poll(DeviceLinkPollRequestDto(started.pollToken))
            }
            assertEquals(
                "EXPIRED",
                links.poll(DeviceLinkPollRequestDto("not-the-real-poll-token")).status.status,
            )
        }
    }

    @Test
    fun approvedButExpiredLinkCannotMintASession() = runTest {
        withFixture {
            val started = links.start(DeviceLinkStartRequestDto("Car"), null, "192.0.2.45")
            val request = DeviceLinkTokenRequestDto(started.linkToken)
            links.lookup(actor, request, "198.51.100.15")
            links.approve(actor, request, "198.51.100.15")
            clock.now = started.expiresAtMs + 1

            val expired = links.poll(DeviceLinkPollRequestDto(started.pollToken))

            assertEquals("EXPIRED", expired.status.status)
            assertEquals(actor.username, expired.status.preview?.username)
            assertNull(expired.sessionToken)
            assertTrue(
                db.sessions().activeForUser(actor.userId)
                    .none { it.clientKind == ClientKind.LINKED_DEVICE },
            )
        }
    }

    @Test
    fun deactivatedApproverIsDeniedAtClaimTime() = runTest {
        withFixture {
            val started = links.start(DeviceLinkStartRequestDto("Car"), null, "192.0.2.46")
            val request = DeviceLinkTokenRequestDto(started.linkToken)
            links.lookup(actor, request, "198.51.100.16")
            links.approve(actor, request, "198.51.100.16")
            val user = assertNotNull(db.users().get(actor.userId))
            db.users().update(
                user.copy(status = UserStatus.DISABLED, updatedAtMs = clock.now),
            )

            val denied = links.poll(DeviceLinkPollRequestDto(started.pollToken))

            assertEquals("DENIED", denied.status.status)
            assertEquals(actor.username, denied.status.preview?.username)
            assertNull(denied.sessionToken)
            assertTrue(
                db.sessions().activeForUser(actor.userId)
                    .none { it.clientKind == ClientKind.LINKED_DEVICE },
            )
        }
    }

    @Test
    fun denialKeepsTheClaimingUserPreviewAndNeverIssuesASession() = runTest {
        withFixture {
            val started = links.start(DeviceLinkStartRequestDto("Car"), null, "192.0.2.47")
            val request = DeviceLinkTokenRequestDto(started.linkToken)
            links.lookup(actor, request, "198.51.100.17")
            links.deny(actor, request, "198.51.100.17")

            val denied = links.poll(DeviceLinkPollRequestDto(started.pollToken))

            assertEquals("DENIED", denied.status.status)
            assertEquals(actor.displayName, denied.status.preview?.displayName)
            assertNull(denied.status.flow)
            assertNull(denied.sessionToken)
        }
    }

    private suspend fun withFixture(block: suspend Fixture.() -> Unit) {
        val dir = Files.createTempDirectory("opentv-device-link-test")
        val db = createServerUserDatabase(dir.resolve("server-users.db").toString())
        val clock = MutableClock(1_700_000_000_000L)
        val config = config()
        val auth = AuthService(db, config, dir, clock::time)
        try {
            auth.initialize()
            val bootstrapToken = Files.readString(dir.resolve("bootstrap.token")).trim()
            val bootstrap = auth.bootstrap(
                BootstrapRequestDto(
                    bootstrapToken,
                    "Admin",
                    "a sufficiently long password",
                    "Administrator",
                ),
                "127.0.0.1",
            )
            val enrollment = auth.startTotpEnrollment(
                assertNotNull(bootstrap.flow.challenge),
                "127.0.0.1",
            )
            val code = AuthCrypto.totp(
                AuthCrypto.decodeBase32(enrollment.secret),
                clock.now / 30_000L,
            )
            val completed = auth.completeTotpEnrollment(
                TotpCompleteRequestDto(enrollment.challenge, code),
                "127.0.0.1",
            )
            val actor = assertNotNull(
                auth.authenticate(assertNotNull(completed.sessionToken))
            ).first
            Fixture(
                db,
                clock,
                config,
                auth,
                DeviceLinkService(db, auth, config, clock::time),
                actor,
            ).block()
        } finally {
            db.close()
            dir.toFile().deleteRecursively()
        }
    }

    private data class Fixture(
        val db: ServerUserDatabase,
        val clock: MutableClock,
        val config: AuthConfig,
        val auth: AuthService,
        val links: DeviceLinkService,
        val actor: Actor,
    ) {
        suspend fun secondActor(): Actor {
            val user = auth.createUser(
                "SecondUser",
                "Second User",
                UserStatus.ACTIVE,
                UserRole.USER,
                clock.now,
            )
            val session = PersistentSessionService(
                db,
                config,
                NoopUserStateCleanupCoordinator,
                clock::time,
            ).issue(user, AuthMethod.PASSWORD, mfa = true)
            return assertNotNull(auth.authenticate(session.token)).first
        }
    }

    private class MutableClock(var now: Long) {
        fun time() = now
    }

    private fun config() = AuthConfig(
        publicUrl = URI("http://localhost:8080"),
        passwordEnabled = true,
        encryptionKey = ByteArray(32) { it.toByte() },
        initialAdmin = null,
        mfaRequiredRoles = setOf("USER", "ADMIN"),
        oidc = null,
        secureCookies = false,
        webAuthnRpId = "localhost",
        webAuthnOrigin = "http://localhost:8080",
        sessionIdleMs = 24 * 60 * 60_000L,
        sessionAbsoluteMs = 30L * 24 * 60 * 60_000L,
    )
}
