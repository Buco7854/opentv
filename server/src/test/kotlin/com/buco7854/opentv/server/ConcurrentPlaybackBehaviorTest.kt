package com.buco7854.opentv.server

import com.buco7854.opentv.contract.SessionHeartbeatDto
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.application
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ConcurrentPlaybackBehaviorTest {
    private class MutableClock(var value: Long = 0) : ServerClock {
        override fun nowMs(): Long = value
    }

    private fun actor(authSessionId: String) = Actor(
        userId = "one-user",
        authSessionId = authSessionId,
        username = "viewer",
        displayName = "Viewer",
        roles = setOf("USER"),
        authMethod = "PASSWORD",
        clientKind = "NATIVE",
    )

    @Test
    fun `one user keeps independent leases and activity for different titles`() {
        val sessions = PlaybackSessionRegistry(reapInBackground = false)
        val phone = actor("phone-auth")
        val television = actor("tv-auth")
        try {
            val movie = sessions.create(
                phone, 1, "movie-content", "https://provider.example/movie/1.mkv", "phone", "android",
            )
            val channel = sessions.create(
                television, 1, "live-content", "https://provider.example/live/2.ts", "tv", "android-tv",
            )
            sessions.update(
                phone,
                "phone",
                "android",
                SessionHeartbeatDto(
                    id = movie.id,
                    title = "Movie",
                    kind = "movie",
                    positionMs = 42_000,
                    durationMs = 120_000,
                ),
            )
            sessions.update(
                television,
                "tv",
                "android-tv",
                SessionHeartbeatDto(id = channel.id, title = "News", kind = "live", live = true),
            )

            val active = sessions.active().associateBy { it.id }
            assertEquals(setOf(movie.id, channel.id), active.keys)
            assertEquals("movie-content", active.getValue(movie.id).contentId)
            assertEquals(42_000, active.getValue(movie.id).state.positionMs)
            assertEquals("live-content", active.getValue(channel.id).contentId)
            assertTrue(active.getValue(channel.id).state.live)

            sessions.remove(movie.id)

            assertFailsWith<PlaybackRevokedException> { sessions.owned(phone, movie.id) }
            assertEquals(channel.id, sessions.owned(television, channel.id).id)
        } finally {
            sessions.close()
        }
    }

    @Test
    fun `heartbeats reaping and auth session revocation are lease scoped`() {
        val clock = MutableClock()
        val sessions = PlaybackSessionRegistry(clock, staleMs = 100, reapInBackground = false)
        val phone = actor("phone-auth")
        val television = actor("tv-auth")
        try {
            val stalePhone = sessions.create(
                phone, 1, "movie-content", "https://provider.example/movie/1.mkv", "", "",
            )
            val liveTelevision = sessions.create(
                television, 1, "live-content", "https://provider.example/live/2.ts", "", "",
            )
            clock.value = 90
            sessions.update(
                television,
                "",
                "",
                SessionHeartbeatDto(id = liveTelevision.id, title = "News", live = true),
            )
            clock.value = 101

            assertEquals(listOf(liveTelevision.id), sessions.active().map { it.id })
            assertFailsWith<PlaybackRevokedException> { sessions.owned(phone, stalePhone.id) }

            val replacementPhone = sessions.create(
                phone, 1, "other-content", "https://provider.example/movie/3.mkv", "", "",
            )
            sessions.terminateSession(phone.authSessionId)

            assertFailsWith<PlaybackRevokedException> { sessions.owned(phone, replacementPhone.id) }
            assertEquals(liveTelevision.id, sessions.owned(television, liveTelevision.id).id)
        } finally {
            sessions.close()
        }
    }

    @Test
    fun `same-title leases acquire one share group when own-device join is admitted`() {
        val sessions = PlaybackSessionRegistry(reapInBackground = false)
        try {
            val phone = sessions.create(
                actor("phone-auth"),
                1,
                "same-live-content",
                "https://provider.example/live/7.ts",
                "",
                "",
            )
            val television = sessions.create(
                actor("tv-auth"),
                1,
                "same-live-content",
                "https://provider.example/live/7.ts",
                "",
                "",
            )

            assertEquals(phone.id, sessions.shareGroup(phone.id))
            assertEquals(television.id, sessions.shareGroup(television.id))
            assertNotEquals(sessions.shareGroup(phone.id), sessions.shareGroup(television.id))
            assertNotEquals(
                remuxSessionId(phone.sourceUrl, MediaCapabilities.BROWSER, 0, sessions.shareGroup(phone.id)),
                remuxSessionId(
                    television.sourceUrl,
                    MediaCapabilities.BROWSER,
                    0,
                    sessions.shareGroup(television.id),
                ),
            )

            val request = sessions.requestJoin(
                targetId = phone.id,
                requesterId = television.id,
                peerName = "Viewer's TV",
                contentKey = "same-live-content",
            )
            requireNotNull(request)

            assertEquals(sessions.shareGroup(phone.id), sessions.shareGroup(television.id))
            assertEquals(
                remuxSessionId(phone.sourceUrl, MediaCapabilities.BROWSER, 0, sessions.shareGroup(phone.id)),
                remuxSessionId(
                    television.sourceUrl,
                    MediaCapabilities.BROWSER,
                    0,
                    sessions.shareGroup(television.id),
                ),
            )
        } finally {
            sessions.close()
        }
    }

    @Test
    fun `different titles consume seats and a refused newcomer disturbs neither viewer`() {
        val connections = ProviderConnections()
        try {
            assertTrue(connections.tryOpenStream("phone", "provider", "movie", 2) {})
            assertTrue(connections.tryOpenStream("tv", "provider", "news", 2) {})

            assertFalse(connections.tryOpenStream("tablet", "provider", "sports", 2) {})
            assertTrue(connections.isOpen("phone"))
            assertTrue(connections.isOpen("tv"))
            assertFalse(connections.isOpen("tablet"))
            assertEquals(2, connections.distinctStreams("provider", null))
        } finally {
            connections.closeAll()
        }
    }

    @Test
    fun `fully capable direct VOD room still needs one provider seat per member`() {
        val sessions = PlaybackSessionRegistry(reapInBackground = false)
        val connections = ProviderConnections()
        try {
            val phoneActor = actor("phone-auth")
            val televisionActor = actor("tv-auth")
            val phone = sessions.create(
                phoneActor, 1, "movie", "https://provider.example/movie.mkv", "", "",
            )
            val television = sessions.create(
                televisionActor, 1, "movie", "https://provider.example/movie.mkv", "", "",
            )
            // Direct VOD remains lease-owned even in a room. Forcing it through the shared HLS
            // remux would reverse the in-band direct-play policy and spend server CPU unnecessarily.
            assertTrue(connections.tryOpenStream(phone.id, "provider", phone.id, 1) {})
            assertNotEquals(phone.id, sessions.shareGroup(television.id))
            requireNotNull(sessions.requestJoin(phone.id, television.id, "Television", "movie"))
            assertEquals(sessions.shareGroup(phone.id), sessions.shareGroup(television.id))
            val grants = PlaybackMediaGrants(sessions)
            assertEquals(television.id, grants.run {
                // A room has satisfied duplicate-play policy; provider capacity is the remaining
                // and intentionally separate admission decision for fully capable direct VOD.
                val issued = issue(televisionActor, television.id)
                validate(television.id, issued.token).id
            })
            // Room renegotiation may inspect whether conversion is needed without counting the
            // existing member's solo read against itself. If direct play wins, the newcomer's
            // later /stream admission is still the operation that receives provider_capacity.
            assertEquals(
                0,
                connections.distinctStreams(
                    "provider",
                    sessions.roomMembers(phone.id) + sessions.shareGroup(phone.id),
                ),
            )
            assertFalse(
                connections.tryOpenStream(television.id, "provider", television.id, 1) {},
            )
            assertEquals(1, connections.distinctStreams("provider", null))
        } finally {
            sessions.close()
            connections.closeAll()
        }
    }

    @Test
    fun `same-content independent-play refusal is a typed conflict`() = testApplication {
        application {
            install(ContentNegotiation) { json() }
            installOpenTvErrorResponses()
            routing {
                get("/") { throw SameContentAlreadyPlayingException() }
            }
        }

        val response = client.get("/")

        assertEquals(HttpStatusCode.Conflict, response.status)
        assertTrue("same_content_already_playing" in response.bodyAsText())
        assertTrue("another device" in response.bodyAsText())
    }

    @Test
    fun `a provider-full media request gets a typed 429 without taking or replacing a seat`() =
        testApplication {
            val connections = ProviderConnections()
            val gate = StreamGate(connections)
            val cipher = StreamCipher(
                Base64.getEncoder().encodeToString(ByteArray(32) { (it + 1).toByte() }),
            )
            val proxy = StreamProxy(ServerHttp(), cipher, gate) { 1 }
            val target = "https://provider.example/live/second.ts"
            assertTrue(
                connections.tryOpenStream(
                    "existing-viewer",
                    providerKeyOf(target),
                    "existing-content",
                    1,
                ) {},
            )
            application {
                install(ContentNegotiation) { json() }
                routing {
                    get("/") {
                        proxy.handle(
                            call,
                            StreamCapability(target, "new-viewer"),
                            mediaGrant = "grant",
                            leaseGuard = {},
                        )
                    }
                }
            }

            try {
                val response = client.get("/")
                assertEquals(HttpStatusCode.TooManyRequests, response.status)
                assertTrue("provider_capacity" in response.bodyAsText())
                assertTrue(connections.isOpen("existing-viewer"))
                assertFalse(connections.isOpen("new-viewer"))
            } finally {
                proxy.close()
                gate.close()
                connections.closeAll()
            }
        }

    @Test
    fun `playback takes the last provider seat from a download but never from a viewer`() {
        val connections = ProviderConnections()
        val evicted = mutableListOf<String>()
        try {
            assertTrue(
                connections.tryOpenDownload("download", "provider", "download-content", 1) {
                    evicted += "download"
                },
            )

            assertTrue(connections.tryOpenStream("viewer", "provider", "live-content", 1) {})
            assertEquals(listOf("download"), evicted)
            assertFalse(connections.isOpen("download"))
            assertTrue(connections.isOpen("viewer"))

            assertFalse(connections.tryOpenStream("second-viewer", "provider", "other-content", 1) {})
            assertTrue(connections.isOpen("viewer"))
            assertFalse(connections.isOpen("second-viewer"))
        } finally {
            connections.closeAll()
        }
    }
}
