package com.buco7854.opentv.hub

import com.buco7854.opentv.contract.AuthCapabilitiesDto
import com.buco7854.opentv.contract.AuthFlowDto
import com.buco7854.opentv.contract.ChannelDto
import com.buco7854.opentv.contract.ClientCapabilitiesDto
import com.buco7854.opentv.contract.CurrentUserDto
import com.buco7854.opentv.contract.FavoritesResolvedDto
import com.buco7854.opentv.contract.MediaGrantDto
import com.buco7854.opentv.contract.PlaybackCreateRequest
import com.buco7854.opentv.contract.PlaybackLeaseDto
import com.buco7854.opentv.contract.ProgrammeDto
import com.buco7854.opentv.contract.ServerInfoDto
import com.buco7854.opentv.contract.SessionHeartbeatDto
import com.buco7854.opentv.contract.TotpEnrollmentDto
import com.buco7854.opentv.contract.XtreamSeriesListItemDto
import com.buco7854.opentv.contract.XtreamSeriesPageDto
import com.buco7854.opentv.core.net.HttpRequestSpec
import com.buco7854.opentv.core.net.HttpResponseSpec
import com.buco7854.opentv.core.net.HttpTransport
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val BASE = "https://tv.example"

/** Records the last request and replies with a queue of canned responses. */
private class FakeTransport(private vararg val replies: HttpResponseSpec) : HttpTransport {
    val seen = mutableListOf<HttpRequestSpec>()
    var failWith: Throwable? = null

    override suspend fun execute(request: HttpRequestSpec): HttpResponseSpec {
        seen += request
        failWith?.let { throw it }
        return replies[(seen.size - 1).coerceAtMost(replies.size - 1)]
    }
}

private fun ok(body: String) = HttpResponseSpec(200, emptyMap(), body)
private val SERVER_JSON = Json { encodeDefaults = true }
private fun <T> serverBody(serializer: KSerializer<T>, value: T) =
    SERVER_JSON.encodeToString(serializer, value)

class HubApiTest {

    @Test
    fun everyRequestIdentifiesAsNativeAndCarriesTheBearer() = runTest {
        val transport = FakeTransport(
            ok(
                serverBody(
                    CurrentUserDto.serializer(),
                    CurrentUserDto(
                        id = "u1",
                        username = "bo",
                        displayName = "Bo",
                        role = "USER",
                        authMethod = "PASSWORD",
                        clientKind = "NATIVE",
                        authSessionId = "s1",
                        playlistIds = listOf(1),
                        hasPassword = true,
                    ),
                ),
            ),
        )
        HubApi(transport).me(HubCredentials(BASE, "tok-123"))

        val request = transport.seen.single()
        assertEquals("Bearer tok-123", request.headers["Authorization"])
        assertEquals("native", request.headers["X-OpenTV-Client"])
        assertEquals("$BASE/api/v1/auth/me", request.url)
    }

    @Test
    fun unauthenticatedCallsOmitTheAuthorizationHeader() = runTest {
        val transport = FakeTransport(
            ok(serverBody(ServerInfoDto.serializer(), ServerInfoDto(version = "1.2.3"))),
        )
        val info = HubApi(transport).serverInfo(BASE)

        assertEquals("1.2.3", info.version)
        assertNull(transport.seen.single().headers["Authorization"])
    }

    @Test
    fun authCapabilitiesUsesTheUnauthenticatedCapabilitiesEndpoint() = runTest {
        val transport = FakeTransport(
            ok(
                serverBody(
                    AuthCapabilitiesDto.serializer(),
                    AuthCapabilitiesDto(
                        passwordEnabled = true,
                        oidcEnabled = false,
                        passkeyLoginEnabled = true,
                        deviceLinkEnabled = true,
                        bootstrapRequired = false,
                        webAuthnRpId = "tv.example",
                    ),
                ),
            ),
        )
        val capabilities = HubApi(transport).authCapabilities(BASE)

        assertTrue(capabilities.passwordEnabled)
        assertTrue(capabilities.passkeyLoginEnabled)
        assertEquals("tv.example", capabilities.webAuthnRpId)
        val request = transport.seen.single()
        assertEquals("GET", request.method)
        assertEquals("$BASE/api/v1/auth/capabilities", request.url)
        assertNull(request.headers["Authorization"])
    }

    @Test
    fun totpEnrollStartPostsTheChallengeAndDecodesTheEnrollment() = runTest {
        val transport = FakeTransport(
            ok(
                serverBody(
                    TotpEnrollmentDto.serializer(),
                    TotpEnrollmentDto(
                        challenge = "enroll-2",
                        secret = "SECRET",
                        uri = "otpauth://totp/OpenTV",
                        expiresAtMs = 12345,
                    ),
                ),
            ),
        )
        val enrollment = HubApi(transport).totpEnrollStart(BASE, "sign-in-1")

        assertEquals("enroll-2", enrollment.challenge)
        assertEquals("SECRET", enrollment.secret)
        assertEquals("otpauth://totp/OpenTV", enrollment.uri)
        val request = transport.seen.single()
        assertEquals("POST", request.method)
        assertEquals("$BASE/api/v1/auth/totp/enroll/start", request.url)
        assertEquals("""{"challenge":"sign-in-1"}""", request.body)
        assertNull(request.headers["Authorization"])
    }

    @Test
    fun totpEnrollCompleteReturnsAnAuthenticatedFlow() = runTest {
        val transport = FakeTransport(
            ok(
                serverBody(
                    AuthFlowDto.serializer(),
                    AuthFlowDto(status = "AUTHENTICATED", sessionToken = "native-token"),
                ),
            ),
        )
        val flow = HubApi(transport).totpEnrollComplete(BASE, "enroll-2", "123456")

        assertEquals("AUTHENTICATED", flow.status)
        assertEquals("native-token", flow.sessionToken)
        assertEquals("""{"challenge":"enroll-2","code":"123456"}""", transport.seen.single().body)
    }

    @Test
    fun totpEnrollCompleteDecodesAConflictAsAnAuthFlow() = runTest {
        val challenge = HttpResponseSpec(
            409,
            emptyMap(),
            serverBody(
                AuthFlowDto.serializer(),
                AuthFlowDto(
                    status = "MFA_REQUIRED",
                    code = "challenge_required",
                    challenge = "next-3",
                    methods = listOf("totp"),
                ),
            ),
        )
        val flow = HubApi(FakeTransport(challenge)).totpEnrollComplete(BASE, "enroll-2", "123456")

        assertEquals("MFA_REQUIRED", flow.status)
        assertEquals("next-3", flow.challenge)
        assertEquals(listOf("totp"), flow.methods)
    }

    @Test
    fun totpEnrollCompleteStillThrowsOnUnauthorized() = runTest {
        val rejected = HttpResponseSpec(401, emptyMap(), """{"code":"invalid_totp","message":"nope"}""")
        val error = assertFailsWith<HubUnauthorizedException> {
            HubApi(FakeTransport(rejected)).totpEnrollComplete(BASE, "enroll-2", "wrong")
        }

        assertEquals("invalid_totp", error.code)
    }

    @Test
    fun mfaChallengeIsDecodedRatherThanThrown() = runTest {
        val challenge = HttpResponseSpec(
            409,
            emptyMap(),
            serverBody(
                AuthFlowDto.serializer(),
                AuthFlowDto(
                    status = "MFA_REQUIRED",
                    code = "challenge_required",
                    challenge = "ch-1",
                    methods = listOf("totp", "recovery"),
                ),
            ),
        )
        val flow = HubApi(FakeTransport(challenge)).password(BASE, "bo", "pw")

        assertEquals("MFA_REQUIRED", flow.status)
        assertEquals("ch-1", flow.challenge)
        assertEquals(listOf("totp", "recovery"), flow.methods)
        assertNull(flow.sessionToken)
    }

    @Test
    fun completedFlowCarriesTheSessionToken() = runTest {
        val transport = FakeTransport(
            ok(
                serverBody(
                    AuthFlowDto.serializer(),
                    AuthFlowDto(status = "AUTHENTICATED", sessionToken = "native-token"),
                ),
            ),
        )
        val flow = HubApi(transport).totp(BASE, "ch-1", "123456")

        assertEquals("native-token", flow.sessionToken)
        assertTrue(transport.seen.single().body!!.contains("\"challenge\":\"ch-1\""))
    }

    @Test
    fun badCredentialsStillFailEvenOnAnAuthFlowRoute() = runTest {
        val rejected = HttpResponseSpec(401, emptyMap(), """{"code":"invalid_credentials","message":"nope"}""")
        val error = assertFailsWith<HubUnauthorizedException> {
            HubApi(FakeTransport(rejected)).password(BASE, "bo", "wrong")
        }
        assertEquals("invalid_credentials", error.code)
    }

    @Test
    fun revokedLeaseIsGoneNotNotFound() = runTest {
        val revoked = HttpResponseSpec(410, emptyMap(), """{"code":"playback_revoked","message":"gone"}""")
        assertFailsWith<HubGoneException> {
            HubApi(FakeTransport(revoked)).heartbeat(
                HubCredentials(BASE, "t"),
                "lease-1",
                SessionHeartbeatDto(id = "lease-1"),
            )
        }
    }

    @Test
    fun transportFailuresBecomeUnreachable() = runTest {
        val transport = FakeTransport(ok("{}")).apply { failWith = RuntimeException("connection refused") }
        assertFailsWith<HubUnreachableException> {
            HubApi(transport).playlists(HubCredentials(BASE, "t"))
        }
    }

    @Test
    fun cancellationIsNeverReclassifiedAsAnUnreachableHub() = runTest {
        val cancelled = CancellationException("cancelled")
        val transport = FakeTransport(ok("{}")).apply { failWith = cancelled }

        val thrown = assertFailsWith<CancellationException> {
            HubApi(transport).playlists(HubCredentials(BASE, "t"))
        }

        assertTrue(thrown === cancelled)
    }

    @Test
    fun leaseRequestSendsReportedCapabilities() = runTest {
        val transport = FakeTransport(
            ok(
                serverBody(
                    PlaybackLeaseDto.serializer(),
                    PlaybackLeaseDto(
                        id = "L1",
                        contentId = "c1",
                        playlistId = 2,
                        mediaGrant = "g",
                        mediaGrantExpiresAtMs = 99,
                        remuxStartUrl = "$BASE/api/v1/remux/start",
                    ),
                ),
            ),
        )
        val lease = HubApi(transport).createLease(
            HubCredentials(BASE, "t"),
            PlaybackCreateRequest(
                contentId = "c1",
                capabilities = ClientCapabilitiesDto(listOf("h264", "hevc"), listOf("aac", "eac3")),
            ),
        )

        assertEquals("L1", lease.id)
        val body = transport.seen.single().body!!
        assertTrue(body.contains("\"videoCodecs\":[\"h264\",\"hevc\"]"), body)
        assertTrue(body.contains("\"audioCodecs\":[\"aac\",\"eac3\"]"), body)
        assertTrue(body.contains("\"selectsTracksInBand\":false"), body)
    }

    @Test
    fun nowAiringDecodesTheServersTvgIdKeyedObject() = runTest {
        val transport = FakeTransport(
            ok(
                serverBody(
                    MapSerializer(String.serializer(), ProgrammeDto.serializer()),
                    mapOf(
                        "news" to ProgrammeDto(
                            id = 9,
                            playlistId = 7,
                            tvgId = "news",
                            title = "Headlines",
                            description = null,
                            startMs = 1_000,
                            endMs = 2_000,
                        ),
                    ),
                ),
            ),
        )

        val programmes = HubApi(transport).nowAiring(HubCredentials(BASE, "t"), 7)

        assertEquals("Headlines", programmes.single().title)
        val request = transport.seen.single()
        assertEquals("GET", request.method)
        assertEquals("$BASE/api/v1/playlists/7/now-airing", request.url)
    }

    @Test
    fun mediaGrantUsesTheSharedResponseContract() = runTest {
        val transport = FakeTransport(
            ok(
                serverBody(
                    MediaGrantDto.serializer(),
                    MediaGrantDto(token = "grant-2", expiresAtMs = 123456),
                ),
            ),
        )

        val grant = HubApi(transport).mediaGrant(HubCredentials(BASE, "t"), "lease/1")

        assertEquals("grant-2", grant.token)
        assertEquals(123456, grant.expiresAtMs)
        val request = transport.seen.single()
        assertEquals("POST", request.method)
        assertEquals("$BASE/api/v1/playback/lease%2F1/media-grant", request.url)
        assertNull(request.body)
    }

    @Test
    fun favoriteRemovalUsesContentIdQueryAndDelete() = runTest {
        val transport = FakeTransport(HttpResponseSpec(204, emptyMap(), ""))
        HubApi(transport).removeFavorite(HubCredentials(BASE, "t"), 7, "content/1")

        val request = transport.seen.single()
        assertEquals("DELETE", request.method)
        assertEquals("$BASE/api/v1/playlists/7/favorites?contentId=content%2F1", request.url)
    }

    @Test
    fun resolvedFavoritesUseTheTypedHubEndpoint() = runTest {
        val transport = FakeTransport(
            ok(
                serverBody(
                    FavoritesResolvedDto.serializer(),
                    FavoritesResolvedDto(
                        movies = listOf(
                            ChannelDto(
                                contentId = "movie-1",
                                id = 1,
                                playlistId = 7,
                                name = "Movie",
                                logo = null,
                                groupTitle = "Movies",
                                tvgId = null,
                                kind = 1,
                                seriesKey = null,
                                season = null,
                                episode = null,
                                position = 0,
                                xtreamStreamId = "4",
                                catchupDays = 0,
                                hasCatchup = false,
                                description = null,
                                durationSecs = 90,
                                airDate = null,
                            ),
                        ),
                    ),
                ),
            ),
        )

        val resolved = HubApi(transport).favoritesResolved(HubCredentials(BASE, "t"), 7)

        assertEquals("movie-1", resolved.movies.single().contentId)
        val request = transport.seen.single()
        assertEquals("GET", request.method)
        assertEquals("$BASE/api/v1/playlists/7/favorites/resolved", request.url)
        assertEquals("Bearer t", request.headers["Authorization"])
        assertEquals("native", request.headers["X-OpenTV-Client"])
    }

    @Test
    fun providerIdsAboveJavascriptSafeIntegerDecodeWithoutChangingValue() = runTest {
        val providerId = "9007199254740993"
        val transport = FakeTransport(
            ok(
                serverBody(
                    XtreamSeriesPageDto.serializer(),
                    XtreamSeriesPageDto(
                        items = listOf(
                            XtreamSeriesListItemDto(
                                contentId = "series-1",
                                seriesId = providerId,
                                name = "Precise",
                                cover = null,
                                genre = null,
                                rating = null,
                            ),
                        ),
                        total = 1,
                        offset = 0,
                        limit = 50,
                    ),
                ),
            ),
        )

        val page = HubApi(transport).xtreamSeries(
            HubCredentials(BASE, "t"),
            playlistId = 7,
            category = "Drama",
            offset = 0,
            limit = 50,
        )

        assertEquals(providerId, page.items.single().seriesId)
    }

    @Test
    fun unknownResponseFieldsAreToleratedButMissingRequiredFieldsAreNot() = runTest {
        // The hub may add fields; a client must not break when it does.
        val transport = FakeTransport(ok("""{"product":"opentv","apiVersion":1,"version":"9","futureField":true}"""))
        assertEquals("9", HubApi(transport).serverInfo(BASE).version)

        assertFailsWith<SerializationException> {
            HubApi(FakeTransport(ok("""{"product":"opentv","apiVersion":1}"""))).serverInfo(BASE)
        }
    }
}
