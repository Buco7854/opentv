package com.buco7854.opentv.server

import com.buco7854.opentv.contract.AccountProgressDto
import com.buco7854.opentv.contract.AuthSessionDto
import com.buco7854.opentv.contract.CurrentUserDto
import com.buco7854.opentv.core.model.Channel
import com.buco7854.opentv.core.model.ChannelKind
import com.buco7854.opentv.core.model.Playlist
import com.buco7854.opentv.serverdata.AuthMethod
import com.buco7854.opentv.serverdata.UserRole
import com.buco7854.opentv.serverdata.UserStatus
import com.buco7854.opentv.serverdata.db.ContentIdentityRow
import com.buco7854.opentv.serverdata.db.UserResumeRow
import com.buco7854.opentv.serverdata.db.UserRow
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.net.URI
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json

class AccountManagementRoutesTest {
    @Test
    fun ordinaryUserManagesOwnHistoryIncludingRemovedAndInaccessibleContent() = withServer { fixture ->
        val response = client.get("/api/v1/auth/progress?userId=bob") { bearerAuth(fixture.current.token) }
        assertEquals(HttpStatusCode.OK, response.status)
        val progress = Json.decodeFromString<List<AccountProgressDto>>(response.bodyAsText())
        assertEquals(setOf(fixture.movieId, REMOVED_CONTENT), progress.map { it.contentId }.toSet())
        assertEquals("A movie", progress.single { it.contentId == fixture.movieId }.title)
        assertNull(progress.single { it.contentId == REMOVED_CONTENT }.title)
        assertEquals(1, fixture.titleReads, "Account titles must be resolved in one batch")
        assertTrue(fixture.db.grants().forUser(ALICE).isEmpty(), "History management must not require a current grant")

        assertEquals(HttpStatusCode.NoContent, client.delete("/api/v1/auth/progress/$BOB_CONTENT") {
            bearerAuth(fixture.current.token)
        }.status)
        assertNotNull(fixture.db.activity().resume(BOB, BOB_CONTENT))
        assertEquals(HttpStatusCode.NoContent, client.delete("/api/v1/auth/progress/$REMOVED_CONTENT") {
            bearerAuth(fixture.current.token)
        }.status)
        assertNull(fixture.db.activity().resume(ALICE, REMOVED_CONTENT))

        assertEquals(HttpStatusCode.NoContent, client.delete("/api/v1/auth/progress") {
            bearerAuth(fixture.current.token)
        }.status)
        assertTrue(fixture.db.activity().resumeForUser(ALICE).isEmpty())
        assertEquals(2, fixture.db.activity().resumeForUser(BOB).size)
    }

    @Test
    fun ordinaryUserSeesOnlyUsableOwnSessionsAndCanRevokeOneIncludingCurrent() = withServer { fixture ->
        val response = client.get("/api/v1/auth/sessions") { bearerAuth(fixture.current.token) }
        assertEquals(HttpStatusCode.OK, response.status)
        val sessions = Json.decodeFromString<List<AuthSessionDto>>(response.bodyAsText())
        assertEquals(setOf(fixture.current.row.id, fixture.other.row.id), sessions.map { it.id }.toSet())
        val me = Json.decodeFromString<CurrentUserDto>(client.get("/api/v1/auth/me") {
            bearerAuth(fixture.current.token)
        }.bodyAsText())
        assertEquals(UserRole.USER, me.role)
        assertEquals(fixture.current.row.id, me.authSessionId)
        assertTrue(sessions.any { it.id == me.authSessionId })

        assertEquals(HttpStatusCode.NotFound, client.delete("/api/v1/auth/sessions/${fixture.bob.row.id}") {
            bearerAuth(fixture.current.token)
        }.status)
        assertNotNull(fixture.auth.authenticate(fixture.bob.token))
        assertTrue(fixture.revocations.isEmpty())

        assertEquals(HttpStatusCode.NoContent, client.delete("/api/v1/auth/sessions/${fixture.other.row.id}") {
            bearerAuth(fixture.current.token)
        }.status)
        assertNull(fixture.auth.authenticate(fixture.other.token))
        assertTrue(ALICE to fixture.other.row.id in fixture.revocations)
        assertNotNull(fixture.auth.authenticate(fixture.current.token))

        assertEquals(HttpStatusCode.NoContent, client.delete("/api/v1/auth/sessions/${fixture.current.row.id}") {
            bearerAuth(fixture.current.token)
        }.status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/auth/me") {
            bearerAuth(fixture.current.token)
        }.status)
    }

    @Test
    fun revokeOthersKeepsCurrentSessionAndOtherAccountsAndUsesSessionCleanup() = withServer { fixture ->
        assertEquals(HttpStatusCode.NoContent, client.delete("/api/v1/auth/sessions") {
            bearerAuth(fixture.current.token)
        }.status)
        assertNull(fixture.db.sessions().get(fixture.current.row.id)?.revokedAtMs)
        assertNotNull(fixture.db.sessions().get(fixture.other.row.id)?.revokedAtMs)
        assertNull(fixture.db.sessions().get(fixture.bob.row.id)?.revokedAtMs)
        assertNotNull(fixture.auth.authenticate(fixture.current.token))
        assertNotNull(fixture.auth.authenticate(fixture.bob.token))
        assertTrue(fixture.revocations.isNotEmpty())
        assertTrue(fixture.revocations.all { (user, session) -> user == ALICE && session != null && session != fixture.current.row.id })
        val before = fixture.revocations.toList()
        assertEquals(HttpStatusCode.NoContent, client.delete("/api/v1/auth/sessions") {
            bearerAuth(fixture.current.token)
        }.status)
        assertEquals(before, fixture.revocations)
    }

    @Test
    fun accountManagementRequiresAuthentication() = withServer {
        for (path in listOf("/auth/progress", "/auth/sessions")) {
            assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1$path").status)
            assertEquals(HttpStatusCode.Unauthorized, client.delete("/api/v1$path").status)
        }
    }

    private fun withServer(block: suspend ApplicationTestBuilder.(Fixture) -> Unit) = testApplication {
        val fixture = Fixture()
        try {
            fixture.seed()
            application {
                install(ContentNegotiation) { json(Json { encodeDefaults = true }) }
                installOpenTvErrorResponses()
                routing {
                    route("/api/v1") {
                        apiSecurityBoundary(ApiSecurity.authenticated(fixture.auth, fixture.cipher), { "127.0.0.1" }) {
                            authenticatedAuthRoutes(
                                fixture.auth, WebAuthnService(fixture.db, fixture.auth, CONFIG),
                                DeviceLinkService(fixture.db, fixture.auth, CONFIG), PublicOrigin(CONFIG) { false },
                            ) { "127.0.0.1" }
                        }
                    }
                }
            }
            block(fixture)
        } finally {
            fixture.persistence.close()
        }
    }

    private class Fixture {
        val persistence = ServerTestPersistence("opentv-account-management")
        val db = persistence.database
        private val storage = persistence.storage
        val revocations = mutableListOf<Pair<String, String?>>()
        private val cleanup = object : UserStateCleanupCoordinator by NoopUserStateCleanupCoordinator {
            override suspend fun sessionRevoked(userId: String, authSessionId: String?) {
                revocations += userId to authSessionId
            }
        }
        private val content = ContentIdentityService(db, storage)
        var titleReads = 0
        val auth = AuthService(db, CONFIG, persistence.directory, { NOW }, cleanup = cleanup,
            resumeTitles = { ids -> titleReads++; content.titlesByContentId(ids) })
        val cipher = StreamCipher(Base64.getEncoder().encodeToString(ByteArray(32)))
        lateinit var current: IssuedSession
        lateinit var other: IssuedSession
        lateinit var bob: IssuedSession
        lateinit var movieId: String

        init { persistence.closeBeforeDatabase(auth::close) }

        suspend fun seed() {
            val sessions = PersistentSessionService(db, CONFIG, cleanup) { NOW }
            val alice = user(ALICE)
            val bobUser = user(BOB)
            db.users().insert(alice)
            db.users().insert(bobUser)
            current = sessions.issue(alice, AuthMethod.WEBAUTHN, true)
            other = sessions.issue(alice, AuthMethod.WEBAUTHN, true)
            bob = sessions.issue(bobUser, AuthMethod.WEBAUTHN, true)
            val expired = sessions.prepare(alice, AuthMethod.WEBAUTHN, true)
            db.sessions().insert(expired.row.copy(idleExpiresAtMs = NOW - 1))
            val revoked = sessions.prepare(alice, AuthMethod.WEBAUTHN, true)
            db.sessions().insert(revoked.row.copy(revokedAtMs = NOW - 1))

            val playlist = storage.playlists.insert(Playlist(name = "Former source", url = null))
            storage.channels.insertAll(listOf(Channel(
                playlistId = playlist, name = "A movie", url = "https://fixture.invalid/movie",
                logo = null, groupTitle = "Movies", tvgId = null, kind = ChannelKind.MOVIE,
                seriesKey = null, season = null, episode = null, position = 0,
            )))
            movieId = content.channel(assertNotNull(storage.channels.getByUrl(playlist, "https://fixture.invalid/movie"))).contentId
            for (id in listOf(REMOVED_CONTENT, BOB_CONTENT)) {
                db.content().insert(ContentIdentityRow(id, playlist, ChannelKind.MOVIE, id, null, NOW, true))
            }
            db.activity().upsertResume(UserResumeRow(ALICE, movieId, 30_000, 120_000, NOW))
            db.activity().upsertResume(UserResumeRow(ALICE, REMOVED_CONTENT, 20_000, 100_000, NOW))
            db.activity().upsertResume(UserResumeRow(BOB, movieId, 40_000, 120_000, NOW))
            db.activity().upsertResume(UserResumeRow(BOB, BOB_CONTENT, 40_000, 120_000, NOW))
        }

        private fun user(id: String) = UserRow(id, id, id, id, UserStatus.ACTIVE, UserRole.USER, false, NOW, NOW, null)
    }

    private companion object {
        const val ALICE = "alice"
        const val BOB = "bob"
        const val REMOVED_CONTENT = "removed-content"
        const val BOB_CONTENT = "bob-only-content"
        const val NOW = 1_700_000_000_000L
        val CONFIG = AuthConfig(
            publicUrl = URI("http://localhost:8080"), passwordEnabled = true, encryptionKey = ByteArray(32),
            initialAdmin = null, mfaRequiredRoles = emptySet(), oidc = null, secureCookies = false,
            webAuthnRpId = "localhost", webAuthnOrigin = "http://localhost:8080",
            sessionIdleMs = 86_400_000, sessionAbsoluteMs = 30L * 86_400_000,
        )
    }
}
