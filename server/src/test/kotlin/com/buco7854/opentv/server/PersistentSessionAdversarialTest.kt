package com.buco7854.opentv.server

import com.buco7854.opentv.serverdata.AuthMethod
import com.buco7854.opentv.serverdata.ClientKind
import com.buco7854.opentv.serverdata.UserRole
import com.buco7854.opentv.serverdata.UserStatus
import com.buco7854.opentv.serverdata.createOpenTvServerDatabase
import com.buco7854.opentv.serverdata.db.AuthSessionRow
import com.buco7854.opentv.serverdata.db.UserRow
import kotlinx.coroutines.runBlocking
import java.net.URI
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class PersistentSessionAdversarialTest {
    @Test
    fun `capability authentication touch is capped at absolute expiry`() = withDatabase { db ->
        var now = 120_000L
        val absoluteExpiry = 150_000L
        db.users().insert(user("owner"))
        db.sessions().insert(
            session(
                id = "session-1",
                userId = "owner",
                lastSeenAtMs = 0,
                idleExpiresAtMs = 130_000,
                absoluteExpiresAtMs = absoluteExpiry,
            ),
        )
        val service = PersistentSessionService(
            db,
            authConfig(sessionIdleMs = 60 * 60_000L),
            NoopUserStateCleanupCoordinator,
            clock = { now },
        )

        assertNotNull(service.authenticateSession("session-1"))
        val touched = requireNotNull(db.sessions().get("session-1"))
        assertEquals(now, touched.lastSeenAtMs)
        assertEquals(absoluteExpiry, touched.idleExpiresAtMs)

        now = absoluteExpiry
        assertNull(service.authenticateSession("session-1"))
        assertEquals(absoluteExpiry, db.sessions().get("session-1")?.revokedAtMs)
    }

    @Test
    fun `MFA pending password session is rejected while linked MFA session remains live`() =
        withDatabase { db ->
            val now = 120_000L
            db.users().insert(user("admin", UserRole.ADMIN))
            db.sessions().insert(
                session(
                    id = "pending",
                    userId = "admin",
                    mfaSatisfiedAtMs = null,
                ),
            )
            db.sessions().insert(
                session(
                    id = "linked",
                    userId = "admin",
                    clientKind = ClientKind.LINKED_DEVICE,
                    mfaSatisfiedAtMs = now,
                    tokenByte = 2,
                ),
            )
            val service = PersistentSessionService(
                db,
                authConfig(mfaRequiredRoles = setOf(UserRole.ADMIN)),
                NoopUserStateCleanupCoordinator,
                clock = { now },
            )

            assertNull(service.authenticateSession("pending"))
            assertNotNull(service.authenticateSession("linked"))
            assertEquals(now, db.sessions().get("pending")?.revokedAtMs)
            assertNull(db.sessions().get("linked")?.revokedAtMs)
        }

    @Test
    fun `password disablement rejects password session without rejecting OIDC session`() =
        withDatabase { db ->
            val now = 120_000L
            db.users().insert(user("owner"))
            db.sessions().insert(session("password", "owner"))
            db.sessions().insert(
                session(
                    id = "oidc",
                    userId = "owner",
                    authMethod = AuthMethod.OIDC,
                    tokenByte = 2,
                ),
            )
            val service = PersistentSessionService(
                db,
                authConfig(passwordEnabled = false),
                NoopUserStateCleanupCoordinator,
                clock = { now },
            )

            assertNull(service.authenticateSession("password"))
            assertNotNull(service.authenticateSession("oidc"))
            assertEquals(now, db.sessions().get("password")?.revokedAtMs)
            assertNull(db.sessions().get("oidc")?.revokedAtMs)
        }

    private fun withDatabase(
        block: suspend (com.buco7854.opentv.serverdata.db.OpenTvServerDatabase) -> Unit,
    ) = runBlocking {
        val dir = Files.createTempDirectory("persistent-session-review")
        val db = createOpenTvServerDatabase(dir.resolve("opentv.db").toString())
        try {
            block(db)
        } finally {
            db.close()
            dir.toFile().deleteRecursively()
        }
    }

    private fun user(id: String, role: String = UserRole.USER) = UserRow(
        id = id,
        username = id,
        normalizedUsername = id,
        displayName = id,
        status = UserStatus.ACTIVE,
        manualRole = role,
        oidcAdmin = false,
        createdAtMs = 1,
        updatedAtMs = 1,
        lastLoginAtMs = null,
    )

    private fun session(
        id: String,
        userId: String,
        authMethod: String = AuthMethod.PASSWORD,
        clientKind: String = ClientKind.BROWSER,
        mfaSatisfiedAtMs: Long? = null,
        lastSeenAtMs: Long = 120_000,
        idleExpiresAtMs: Long = 180_000,
        absoluteExpiresAtMs: Long = 240_000,
        tokenByte: Int = 1,
    ) = AuthSessionRow(
        id = id,
        userId = userId,
        tokenHash = ByteArray(32) { tokenByte.toByte() },
        csrfToken = "",
        authMethod = authMethod,
        clientKind = clientKind,
        tokenFamilyId = "family-$id",
        credentialVersion = 0,
        deviceId = null,
        deviceName = null,
        mfaSatisfiedAtMs = mfaSatisfiedAtMs,
        createdAtMs = 1,
        lastSeenAtMs = lastSeenAtMs,
        idleExpiresAtMs = idleExpiresAtMs,
        absoluteExpiresAtMs = absoluteExpiresAtMs,
        revokedAtMs = null,
    )

    private fun authConfig(
        passwordEnabled: Boolean = true,
        mfaRequiredRoles: Set<String> = emptySet(),
        sessionIdleMs: Long = 60 * 60_000L,
    ) = AuthConfig(
        publicUrl = URI("https://tv.example.com"),
        passwordEnabled = passwordEnabled,
        encryptionKey = ByteArray(32) { it.toByte() },
        initialAdmin = null,
        mfaRequiredRoles = mfaRequiredRoles,
        oidc = null,
        secureCookies = true,
        webAuthnRpId = "tv.example.com",
        webAuthnOrigin = "https://tv.example.com",
        sessionIdleMs = sessionIdleMs,
        sessionAbsoluteMs = 30L * 24 * 60 * 60_000L,
    )
}
