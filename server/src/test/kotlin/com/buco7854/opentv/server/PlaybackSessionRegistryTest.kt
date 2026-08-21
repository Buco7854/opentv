package com.buco7854.opentv.server

import com.buco7854.opentv.contract.SessionCommandDto
import com.buco7854.opentv.contract.SyncStateDto
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class PlaybackSessionRegistryTest {
    private class MutableClock(var value: Long = 0) : ServerClock {
        override fun nowMs() = value
    }

    private fun actor(id: String) = Actor(id, "auth-$id", id, id, setOf("USER"), "PASSWORD", "BROWSER")

    private fun device(userId: String, authSessionId: String, displayName: String) = Actor(
        userId,
        authSessionId,
        userId,
        displayName,
        setOf("USER"),
        "PASSWORD",
        "NATIVE",
    )

    private fun create(
        sessions: PlaybackSessionRegistry,
        id: String,
        capabilities: MediaCapabilities = MediaCapabilities.BROWSER,
    ) = sessions.create(
        actor(id), 1, "same", "https://example.test/stream", "", "", capabilities,
    ).id

    private fun join(sessions: PlaybackSessionRegistry, host: String, guest: String): Boolean {
        val requestId = sessions.requestJoin(host, guest, "Guest", "same") ?: return false
        return sessions.answerJoin(host, requestId, true)
    }

    @Test
    fun acceptedJoinCreatesSharedRoomAndPromotesRemainingHost() {
        val sessions = PlaybackSessionRegistry(reapInBackground = false)
        val host = create(sessions, "host")
        val guest = create(sessions, "guest")

        assertTrue(join(sessions, host, guest))
        assertEquals("r-$host", sessions.shareGroup(guest))
        assertEquals(setOf(host, guest), sessions.roomMembers(host))

        sessions.leaveRoom(host)
        val room = assertNotNull(sessions.roomOf(guest))
        assertEquals(1, room.second)
        assertTrue(sessions.setRoomAudio(guest, 2))
    }

    @Test
    fun sameAccountDuplicateIsBlockedUntilItsJoinIsAdmittedWithoutHostApproval() {
        val sessions = PlaybackSessionRegistry(reapInBackground = false)
        val phoneActor = device("viewer", "phone-auth", "Phone")
        val televisionActor = device("viewer", "tv-auth", "Television")
        val phone = sessions.create(
            phoneActor, 1, "movie", "https://example.test/movie.mkv", "", "",
        )
        val television = sessions.create(
            televisionActor, 1, "movie", "https://example.test/movie.mkv", "", "",
        )
        val grants = PlaybackMediaGrants(sessions)
        val televisionGrant = grants.issue(televisionActor, television.id)

        assertEquals(listOf(phone.id), sessions.sameContentPeers(television.id, "movie").map { it.id })
        assertEquals(phone.id, sessions.sameAccountConflict(television.id)?.id)
        assertEquals(null, sessions.sameAccountConflict(phone.id))
        assertFailsWith<SameContentAlreadyPlayingException> {
            grants.validate(television.id, televisionGrant.token)
        }

        assertNotNull(
            sessions.requestJoin(phone.id, television.id, "Television", "movie"),
        )

        assertEquals(sessions.shareGroup(phone.id), sessions.shareGroup(television.id))
        assertEquals(null, sessions.sameAccountConflict(television.id))
        assertFalse(sessions.drainCommands(phone.id).any { it.type == "join-request" })
        assertTrue(sessions.drainCommands(television.id).any {
            it.type == "join-response" && it.accepted == true
        })
        assertTrue(sessions.setRoomAudio(phone.id, 1))
        assertTrue(sessions.setRoomAudio(television.id, 2))
        sessions.drainCommands(phone.id)
        assertTrue(sessions.requestControl(television.id, "Television"))
        sessions.syncRoom(
            television.id,
            SyncStateDto(positionMs = 42_000, paused = true, rate = 1.0, seek = true),
        )
        assertTrue(sessions.drainCommands(phone.id).any {
            it.type == "sync" && it.sync?.let { sync ->
                sync.positionMs == 42_000L && sync.seek
            } == true
        })
        sessions.close()
    }

    @Test
    fun browserReloadDoesNotOfferThePreviousPageAsAWatchTogetherPeer() {
        val sessions = PlaybackSessionRegistry(reapInBackground = false)
        val browser = device("viewer", "one-browser-session", "Chrome")
        val previousPage = sessions.create(
            browser, 1, "movie", "https://example.test/movie.mkv", "", "",
        )
        val reloadedPage = sessions.create(
            browser, 1, "movie", "https://example.test/movie.mkv", "", "",
        )
        val grants = PlaybackMediaGrants(sessions)

        assertTrue(sessions.sameContentPeers(reloadedPage.id, "movie").isEmpty())
        assertEquals(null, sessions.sameAccountConflict(reloadedPage.id))
        assertEquals(
            reloadedPage.id,
            grants.validate(reloadedPage.id, grants.issue(browser, reloadedPage.id).token).id,
        )
        // Clients without tab correlation keep the old lease until page unload or the reaper.
        assertEquals(previousPage.id, sessions.owned(browser, previousPage.id).id)
        sessions.close()
    }

    @Test
    fun browserReloadReleasesItsSoloLeaseBeforeReturningTheReplacement() {
        val terminated = mutableListOf<String>()
        val cleanup = object : PlaybackLeaseCleanup {
            override fun memberLeaving(leaseId: String) = Unit
            override fun shareGroupUnused(group: String) = Unit
            override fun leaseTerminated(leaseId: String, unusedShareGroup: String?) {
                terminated += leaseId
            }
        }
        val sessions = PlaybackSessionRegistry(cleanup = cleanup, reapInBackground = false)
        val browser = device("viewer", "one-browser-session", "Chrome")
        val previousPage = sessions.create(
            browser, 1, "movie", "https://example.test/movie.mkv", "", "",
            clientInstanceId = "tab-instance-1234",
        )
        val otherSession = device("viewer", "another-browser-session", "Chrome")
        val otherSessionPage = sessions.create(
            otherSession, 1, "movie", "https://example.test/movie.mkv", "", "",
            clientInstanceId = "tab-instance-1234",
        )

        val reloadedPage = sessions.create(
            browser, 1, "movie", "https://example.test/movie.mkv", "", "",
            clientInstanceId = "tab-instance-1234",
        )

        assertFailsWith<PlaybackRevokedException> {
            sessions.owned(browser, previousPage.id)
        }
        assertEquals(listOf(previousPage.id), terminated)
        assertEquals(reloadedPage.id, sessions.owned(browser, reloadedPage.id).id)
        // The tab identifier is correlation, not authority: even an exact collision cannot
        // revoke a lease authenticated by a different session.
        assertEquals(otherSessionPage.id, sessions.owned(otherSession, otherSessionPage.id).id)

        val otherTab = sessions.create(
            browser, 1, "movie", "https://example.test/movie.mkv", "", "",
            clientInstanceId = "other-instance-1234",
        )
        assertEquals(reloadedPage.id, sessions.owned(browser, reloadedPage.id).id)
        assertEquals(otherTab.id, sessions.owned(browser, otherTab.id).id)
        sessions.close()
    }

    @Test
    fun anotherAuthSessionStillRepresentsAnotherDevice() {
        val sessions = PlaybackSessionRegistry(reapInBackground = false)
        val first = sessions.create(
            device("viewer", "phone-session", "Phone"),
            1, "movie", "https://example.test/movie.mkv", "", "",
        )
        val second = sessions.create(
            device("viewer", "browser-session", "Browser"),
            1, "movie", "https://example.test/movie.mkv", "", "",
        )

        assertEquals(listOf(first.id), sessions.sameContentPeers(second.id, "movie").map { it.id })
        assertEquals(first.id, sessions.sameAccountConflict(second.id)?.id)
        sessions.close()
    }

    @Test
    fun thirdOwnDeviceJoinCollapsesEveryDuplicateLeaseIntoOneAdmittedRoom() {
        val sessions = PlaybackSessionRegistry(reapInBackground = false)
        val phoneActor = device("viewer", "phone-auth", "Phone")
        val tabletActor = device("viewer", "tablet-auth", "Tablet")
        val televisionActor = device("viewer", "tv-auth", "Television")
        val phone = sessions.create(
            phoneActor, 1, "movie", "https://example.test/movie.mkv", "", "",
        )
        val tablet = sessions.create(
            tabletActor, 1, "movie", "https://example.test/movie.mkv", "", "",
        )
        val television = sessions.create(
            televisionActor, 1, "movie", "https://example.test/movie.mkv", "", "",
        )
        val grants = PlaybackMediaGrants(sessions)
        val televisionGrant = grants.issue(televisionActor, television.id)

        assertEquals(
            setOf(phone.id, tablet.id),
            sessions.sameContentPeers(television.id, "movie").mapTo(mutableSetOf()) { it.id },
        )
        assertNotNull(sessions.requestJoin(tablet.id, television.id, "Television", "movie"))

        assertEquals(
            setOf(phone.id, tablet.id, television.id),
            sessions.roomMembers(television.id),
        )
        assertEquals(null, sessions.sameAccountConflict(television.id))
        assertEquals(television.id, grants.validate(television.id, televisionGrant.token).id)
        listOf(phone.id, tablet.id, television.id).forEach { member ->
            val roster = sessions.drainCommands(member)
                .last { it.type == "room-state" }
                .members.orEmpty()
            assertEquals(3, roster.size)
            assertTrue(roster.all { it.controller })
            assertEquals(phone.id, roster.single { it.host }.id)
        }
        // A concurrent own-device request can arrive after the first one already admitted the
        // whole batch. Treat that observation as idempotent success, not a contradictory 404.
        assertNotNull(sessions.requestJoin(phone.id, tablet.id, "Tablet", "movie"))
        assertTrue(sessions.drainCommands(tablet.id).isEmpty())

        sessions.leaveRoom(television.id)
        assertNotNull(sessions.sameAccountConflict(television.id))
        assertFailsWith<SameContentAlreadyPlayingException> {
            grants.validate(television.id, televisionGrant.token)
        }
        sessions.close()
    }

    @Test
    fun declinedRequiredJoinTombstonesItsLeaseAndMediaGrantImmediately() {
        val sessions = PlaybackSessionRegistry(reapInBackground = false)
        val phoneActor = device("viewer", "phone-auth", "Phone")
        val televisionActor = device("viewer", "tv-auth", "Television")
        sessions.create(
            phoneActor, 1, "movie", "https://example.test/movie.mkv", "", "",
        )
        val television = sessions.create(
            televisionActor, 1, "movie", "https://example.test/movie.mkv", "", "",
        )
        val grants = PlaybackMediaGrants(sessions)
        val grant = grants.issue(televisionActor, television.id)

        assertFalse(sessions.watchAlone(television.id))

        assertFailsWith<PlaybackRevokedException> { sessions.lease(television.id) }
        assertFailsWith<PlaybackRevokedException> {
            grants.validate(television.id, grant.token)
        }
        sessions.close()
    }

    @Test
    fun differentAccountCannotBypassHostApprovalByTargetingAGuest() {
        val sessions = PlaybackSessionRegistry(reapInBackground = false)
        val host = create(sessions, "host")
        val guest = create(sessions, "guest")
        val outsider = create(sessions, "outsider")
        assertTrue(join(sessions, host, guest))
        sessions.drainCommands(host)
        sessions.drainCommands(guest)

        assertEquals(null, sessions.requestJoin(guest, outsider, "Outsider", "same"))

        assertEquals(setOf(host, guest), sessions.roomMembers(host))
        assertEquals(null, sessions.roomOf(outsider))
        assertFalse(sessions.drainCommands(guest).any { it.type == "join-request" })
        sessions.close()
    }

    @Test
    fun ownDeviceFollowingAViewerIntoAnotherAccountsRoomDoesNotGainControl() {
        val sessions = PlaybackSessionRegistry(reapInBackground = false)
        val host = create(sessions, "host")
        val viewerActor = device("viewer", "phone-auth", "Phone")
        val secondDeviceActor = device("viewer", "tv-auth", "Television")
        val viewer = sessions.create(
            viewerActor, 1, "same", "https://example.test/stream", "", "",
        )
        val secondDevice = sessions.create(
            secondDeviceActor, 1, "same", "https://example.test/stream", "", "",
        )
        val firstRequest = assertNotNull(
            sessions.requestJoin(host, viewer.id, "Phone", "same"),
        )
        assertTrue(sessions.answerJoin(host, firstRequest, true))
        sessions.drainCommands(host)
        sessions.drainCommands(viewer.id)

        assertNotNull(
            sessions.requestJoin(viewer.id, secondDevice.id, "Television", "same"),
        )

        assertEquals(sessions.roomOf(host), sessions.roomOf(secondDevice.id))
        assertFalse(sessions.drainCommands(host).any { it.type == "join-request" })
        assertFalse(sessions.setRoomAudio(viewer.id, 1))
        assertFalse(sessions.setRoomAudio(secondDevice.id, 1))
        assertTrue(sessions.setRoomAudio(host, 1))
        sessions.close()
    }

    @Test
    fun differentAccountJoinStillWaitsForHostApprovalAndDoesNotGrantControl() {
        val sessions = PlaybackSessionRegistry(reapInBackground = false)
        val host = create(sessions, "host")
        val guest = create(sessions, "guest")

        val requestId = assertNotNull(sessions.requestJoin(host, guest, "Guest", "same"))

        assertEquals(null, sessions.roomOf(host))
        assertEquals(null, sessions.roomOf(guest))
        assertTrue(sessions.drainCommands(host).any {
            it.type == "join-request" && it.requestId == requestId
        })
        assertTrue(sessions.answerJoin(host, requestId, true))
        assertFalse(sessions.setRoomAudio(guest, 1))
        assertTrue(sessions.setRoomAudio(host, 1))
        sessions.close()
    }

    @Test
    fun approvedUnrelatedRoomCannotHideAnOlderOwnDeviceConflict() {
        val sessions = PlaybackSessionRegistry(reapInBackground = false)
        val phoneActor = device("viewer", "phone-auth", "Phone")
        val televisionActor = device("viewer", "tv-auth", "Television")
        sessions.create(
            phoneActor, 1, "movie", "https://example.test/movie.mkv", "", "",
        )
        val television = sessions.create(
            televisionActor, 1, "movie", "https://example.test/movie.mkv", "", "",
        )
        val unrelatedHost = sessions.create(
            actor("friend"), 1, "movie", "https://example.test/movie.mkv", "", "",
        )
        val request = assertNotNull(
            sessions.requestJoin(unrelatedHost.id, television.id, "Television", "movie"),
        )

        assertFalse(sessions.answerJoin(unrelatedHost.id, request, true))
        assertEquals(null, sessions.roomOf(television.id))
        assertNotNull(sessions.sameAccountConflict(television.id))
        sessions.close()
    }

    @Test
    fun approvedHostJoinMayResolveConflictWhenTheOlderOwnDeviceIsAlreadyThere() {
        val sessions = PlaybackSessionRegistry(reapInBackground = false)
        val unrelatedHost = sessions.create(
            actor("friend"), 1, "movie", "https://example.test/movie.mkv", "", "",
        )
        val phone = sessions.create(
            device("viewer", "phone-auth", "Phone"),
            1, "movie", "https://example.test/movie.mkv", "", "",
        )
        val phoneRequest = assertNotNull(
            sessions.requestJoin(unrelatedHost.id, phone.id, "Phone", "movie"),
        )
        assertTrue(sessions.answerJoin(unrelatedHost.id, phoneRequest, true))
        val television = sessions.create(
            device("viewer", "tv-auth", "Television"),
            1, "movie", "https://example.test/movie.mkv", "", "",
        )
        val televisionRequest = assertNotNull(
            sessions.requestJoin(unrelatedHost.id, television.id, "Television", "movie"),
        )

        assertTrue(sessions.answerJoin(unrelatedHost.id, televisionRequest, true))
        assertEquals(sessions.roomOf(phone.id), sessions.roomOf(television.id))
        assertEquals(null, sessions.sameAccountConflict(television.id))
        sessions.close()
    }

    @Test
    fun sameAccountDifferentTitlesRemainIndependentAndMediaAdmitted() {
        val sessions = PlaybackSessionRegistry(reapInBackground = false)
        val phoneActor = device("viewer", "phone-auth", "Phone")
        val televisionActor = device("viewer", "tv-auth", "Television")
        val movie = sessions.create(
            phoneActor, 1, "movie", "https://example.test/movie.mkv", "", "",
        )
        val news = sessions.create(
            televisionActor, 1, "news", "https://example.test/news.ts", "", "",
        )
        val grants = PlaybackMediaGrants(sessions)

        val movieGrant = grants.issue(phoneActor, movie.id)
        val newsGrant = grants.issue(televisionActor, news.id)

        assertEquals(movie.id, grants.validate(movie.id, movieGrant.token).id)
        assertEquals(news.id, grants.validate(news.id, newsGrant.token).id)
        assertEquals(null, sessions.sameAccountConflict(movie.id))
        assertEquals(null, sessions.sameAccountConflict(news.id))
        assertTrue(sessions.sameContentPeers(movie.id, movie.contentId).isEmpty())
        sessions.close()
    }

    @Test
    fun duplicateKeyIncludesTheResolvedPlaybackSourceVariant() {
        val sessions = PlaybackSessionRegistry(reapInBackground = false)
        val phoneActor = device("viewer", "phone-auth", "Phone")
        val televisionActor = device("viewer", "tv-auth", "Television")
        val live = sessions.create(
            phoneActor,
            1,
            "channel-content",
            "https://example.test/live/channel.ts",
            "",
            "",
        )
        val catchup = sessions.create(
            televisionActor,
            1,
            "channel-content",
            "https://example.test/timeshift/2026-08-01/channel.ts",
            "",
            "",
        )
        assertEquals(null, sessions.sameAccountConflict(catchup.id))
        assertTrue(sessions.sameContentPeers(catchup.id, "channel-content").isEmpty())
        assertEquals(
            null,
            sessions.requestJoin(live.id, catchup.id, "Television", "channel-content"),
        )
        assertFalse(sessions.shareGroup(live.id) == sessions.shareGroup(catchup.id))
        val grants = PlaybackMediaGrants(sessions)
        assertEquals(live.id, grants.validate(live.id, grants.issue(phoneActor, live.id).token).id)
        assertEquals(
            catchup.id,
            grants.validate(catchup.id, grants.issue(televisionActor, catchup.id).token).id,
        )

        val sameCatchup = sessions.create(
            phoneActor,
            1,
            "channel-content",
            catchup.sourceUrl,
            "",
            "",
        )
        assertEquals(catchup.id, sessions.sameAccountConflict(sameCatchup.id)?.id)
        assertEquals(
            listOf(catchup.id),
            sessions.sameContentPeers(sameCatchup.id, "channel-content").map { it.id },
        )
        assertNotNull(
            sessions.requestJoin(catchup.id, sameCatchup.id, "Phone", "channel-content"),
        )
        assertEquals(sessions.shareGroup(catchup.id), sessions.shareGroup(sameCatchup.id))
        assertFalse(sessions.shareGroup(live.id) == sessions.shareGroup(catchup.id))

        val otherPlaylistIdentity = sessions.create(
            televisionActor,
            2,
            "other-content-id",
            live.sourceUrl,
            "",
            "",
        )
        assertEquals(null, sessions.sameAccountConflict(otherPlaylistIdentity.id))
        sessions.close()
    }

    @Test
    fun racingDuplicateLeaseCreationLeavesExactlyOneIndependentWinner() {
        val sessions = PlaybackSessionRegistry(reapInBackground = false)
        val executor = Executors.newFixedThreadPool(2)
        val start = CountDownLatch(1)
        try {
            val actors = listOf(
                device("viewer", "phone-auth", "Phone"),
                device("viewer", "tv-auth", "Television"),
            )
            val leases = actors.map { owner ->
                executor.submit<PlaybackSessionRegistry.Live> {
                    start.await(1, TimeUnit.SECONDS)
                    sessions.create(
                        owner, 1, "movie", "https://example.test/movie.mkv", "", "",
                    )
                }
            }.also { start.countDown() }.map { it.get(1, TimeUnit.SECONDS) }
            val grants = PlaybackMediaGrants(sessions)

            val admitted = leases.zip(actors).count { (lease, owner) ->
                val grant = grants.issue(owner, lease.id)
                runCatching { grants.validate(lease.id, grant.token) }.isSuccess
            }

            assertEquals(1, admitted)
            assertEquals(1, leases.count { sessions.sameAccountConflict(it.id) != null })
        } finally {
            executor.shutdownNow()
            sessions.close()
        }
    }

    @Test
    fun reapingOlderRoomMemberLeavesTheOtherOwnDeviceAdmitted() {
        val clock = MutableClock()
        val sessions = PlaybackSessionRegistry(clock, staleMs = 100, reapInBackground = false)
        val phoneActor = device("viewer", "phone-auth", "Phone")
        val televisionActor = device("viewer", "tv-auth", "Television")
        val phone = sessions.create(
            phoneActor, 1, "movie", "https://example.test/movie.mkv", "", "",
        )
        val television = sessions.create(
            televisionActor, 1, "movie", "https://example.test/movie.mkv", "", "",
        )
        val grants = PlaybackMediaGrants(sessions)
        val grant = grants.issue(televisionActor, television.id)
        assertNotNull(sessions.requestJoin(phone.id, television.id, "Television", "movie"))
        clock.value = 90
        sessions.touch(television.id)
        clock.value = 101

        assertEquals(listOf(television.id), sessions.active().map { it.id })
        assertEquals(null, sessions.sameAccountConflict(television.id))
        assertEquals(television.id, grants.validate(television.id, grant.token).id)
        sessions.close()
    }

    @Test
    fun joinedLiveRoomRejectsEachMembersIndependentMediaTransport() {
        val sessions = PlaybackSessionRegistry(reapInBackground = false)
        val phoneActor = device("viewer", "phone-auth", "Phone")
        val televisionActor = device("viewer", "tv-auth", "Television")
        val target = "https://example.test/live/channel.ts"
        val phone = sessions.create(
            phoneActor, 1, "channel", target, "", "", liveSource = true,
        )
        val television = sessions.create(
            televisionActor, 1, "channel", target, "", "", liveSource = true,
        )
        val grants = PlaybackMediaGrants(sessions)
        val phoneGrant = grants.issue(phoneActor, phone.id)
        val televisionGrant = grants.issue(televisionActor, television.id)
        assertNotNull(sessions.requestJoin(phone.id, television.id, "Television", "channel"))

        listOf(phone to phoneGrant, television to televisionGrant).forEach { (lease, grant) ->
            val capability = StreamCapability(target, lease.id)
            assertFailsWith<SameContentAlreadyPlayingException> {
                grants.validateCapability(
                    lease.id,
                    grant.token,
                    capability,
                    PlaybackMediaTransport.SOLO,
                )
            }
            grants.validateCapability(
                lease.id,
                grant.token,
                capability,
                PlaybackMediaTransport.RELAY,
            )
        }
        sessions.close()
    }

    @Test
    fun soloAudioRescueOwnsTheLeaseTransportAndLateHlsCannotTakeItBack() {
        val sessions = PlaybackSessionRegistry(reapInBackground = false)
        val browser = device("viewer", "browser-auth", "Chrome")
        val target = "https://example.test/live/channel.m3u8"
        val lease = sessions.create(
            browser, 1, "channel", target, "", "", liveSource = true,
        )
        val grants = PlaybackMediaGrants(sessions)
        val grant = grants.issue(browser, lease.id)
        val capability = StreamCapability(target, lease.id)

        grants.validateCapability(
            lease.id,
            grant.token,
            capability,
            PlaybackMediaTransport.AUDIO_TRANSCODE,
        )
        sessions.activateAudioRescue(lease.id)
        grants.validateCapability(
            lease.id,
            grant.token,
            capability,
            PlaybackMediaTransport.AUDIO_TRANSCODE,
        )
        assertFailsWith<SameContentAlreadyPlayingException> {
            grants.validateCapability(
                lease.id,
                grant.token,
                capability,
                PlaybackMediaTransport.SOLO,
            )
        }
        sessions.close()
    }

    @Test
    fun aRoomMemberCannotReplaceTheSharedReadWithPrivateAudioRescue() {
        val sessions = PlaybackSessionRegistry(reapInBackground = false)
        val phoneActor = device("viewer", "phone-auth", "Phone")
        val televisionActor = device("viewer", "tv-auth", "Television")
        val target = "https://example.test/live/channel.m3u8"
        val phone = sessions.create(phoneActor, 1, "channel", target, "", "", liveSource = true)
        val television = sessions.create(
            televisionActor, 1, "channel", target, "", "", liveSource = true,
        )
        val grants = PlaybackMediaGrants(sessions)
        val grant = grants.issue(televisionActor, television.id)
        assertNotNull(sessions.requestJoin(phone.id, television.id, "Television", "channel"))

        assertFailsWith<SameContentAlreadyPlayingException> {
            grants.validateCapability(
                television.id,
                grant.token,
                StreamCapability(target, television.id, hlsResource = true),
                PlaybackMediaTransport.AUDIO_TRANSCODE,
            )
        }
        assertFailsWith<SameContentAlreadyPlayingException> {
            sessions.activateAudioRescue(television.id)
        }
        sessions.close()
    }

    @Test
    fun liveRoomAcceptsOnlyTheSingleSourceAppropriateSharedTransport() {
        val sessions = PlaybackSessionRegistry(reapInBackground = false)
        val phoneActor = device("viewer", "phone-auth", "Phone")
        val televisionActor = device("viewer", "tv-auth", "Television")
        val phone = sessions.create(
            phoneActor, 1, "channel", "https://example.test/live/channel.ts", "", "",
            liveSource = true,
        )
        val television = sessions.create(
            televisionActor, 1, "channel", "https://example.test/live/channel.ts", "", "",
            liveSource = true,
        )
        val grants = PlaybackMediaGrants(sessions)
        val grant = grants.issue(televisionActor, television.id)
        assertNotNull(sessions.requestJoin(phone.id, television.id, "Television", "channel"))

        val raw = StreamCapability(television.sourceUrl, television.id, hlsResource = false)
        grants.validateCapability(
            television.id,
            grant.token,
            raw,
            PlaybackMediaTransport.RELAY,
        )
        listOf(PlaybackMediaTransport.SHARED_HLS, PlaybackMediaTransport.REMUX).forEach { transport ->
            assertFailsWith<SameContentAlreadyPlayingException> {
                grants.validateCapability(television.id, grant.token, raw, transport)
            }
        }

        val hls = raw.copy(hlsResource = true)
        grants.validateCapability(
            television.id,
            grant.token,
            hls,
            PlaybackMediaTransport.SHARED_HLS,
        )
        listOf(PlaybackMediaTransport.RELAY, PlaybackMediaTransport.REMUX).forEach { transport ->
            assertFailsWith<SameContentAlreadyPlayingException> {
                grants.validateCapability(television.id, grant.token, hls, transport)
            }
        }
        sessions.close()
    }

    @Test
    fun joiningRoomInvalidatesPreviouslyBoundSoloRemux() {
        val sessions = PlaybackSessionRegistry(reapInBackground = false)
        val phoneActor = device("viewer", "phone-auth", "Phone")
        val televisionActor = device("viewer", "tv-auth", "Television")
        val phone = sessions.create(
            phoneActor, 1, "movie", "https://example.test/movie.mkv", "", "",
        )
        val grants = PlaybackMediaGrants(sessions)
        val phoneGrant = grants.issue(phoneActor, phone.id)
        grants.bindResource(phone.id, "solo-remux")
        grants.validateResource(phone.id, phoneGrant.token, "solo-remux")
        val television = sessions.create(
            televisionActor, 1, "movie", "https://example.test/movie.mkv", "", "",
        )

        assertNotNull(sessions.requestJoin(phone.id, television.id, "Television", "movie"))

        assertFailsWith<PlaybackRevokedException> {
            grants.validateResource(phone.id, phoneGrant.token, "solo-remux")
        }
        sessions.close()
    }

    @Test
    fun nonControllerCannotDriveRoom() {
        val sessions = PlaybackSessionRegistry(reapInBackground = false)
        val host = create(sessions, "host")
        val guest = create(sessions, "guest")
        join(sessions, host, guest)

        assertFalse(sessions.setRoomAudio(guest, 1))
        assertTrue(sessions.setRoomAudio(host, 1))
        assertEquals(1, sessions.roomAudio(guest))
    }

    @Test
    fun ownDeviceControllersCoalesceSeekStormsToTheLatestServerArrival() {
        val sessions = PlaybackSessionRegistry(reapInBackground = false)
        val phone = sessions.create(
            device("viewer", "phone-auth", "Phone"),
            1, "movie", "https://example.test/movie.mkv", "", "",
        )
        val tablet = sessions.create(
            device("viewer", "tablet-auth", "Tablet"),
            1, "movie", "https://example.test/movie.mkv", "", "",
        )
        val television = sessions.create(
            device("viewer", "tv-auth", "Television"),
            1, "movie", "https://example.test/movie.mkv", "", "",
        )
        assertNotNull(sessions.requestJoin(phone.id, television.id, "Television", "movie"))
        listOf(phone.id, tablet.id, television.id).forEach(sessions::drainCommands)

        sessions.syncRoom(
            phone.id,
            SyncStateDto(positionMs = 20_000, paused = false, rate = 1.0, seek = true),
        )
        sessions.syncRoom(
            tablet.id,
            SyncStateDto(positionMs = 45_000, paused = true, rate = 1.0, seek = true),
        )

        val televisionSync = sessions.drainCommands(television.id).single { it.type == "sync" }
        assertEquals(45_000L, televisionSync.sync?.positionMs)
        assertEquals(true, televisionSync.sync?.paused)
        sessions.close()
    }

    @Test
    fun staleSessionIsPrunedUsingInjectedClock() {
        val clock = MutableClock()
        val sessions = PlaybackSessionRegistry(clock, staleMs = 100, reapInBackground = false)
        create(sessions, "old")
        clock.value = 101

        assertTrue(sessions.active().isEmpty())
    }

    @Test
    fun mediaActivityKeepsALeaseAliveWhenItsAuthenticatedHeartbeatIsDelayed() {
        val clock = MutableClock()
        val sessions = PlaybackSessionRegistry(clock, staleMs = 100, reapInBackground = false)
        val lease = create(sessions, "viewer")

        clock.value = 90
        sessions.touch(lease)
        clock.value = 150

        assertEquals(listOf(lease), sessions.active().map { it.id })
        sessions.close()
    }

    @Test
    fun invalidMediaGrantCannotKeepALeaseAlive() {
        val clock = MutableClock()
        val sessions = PlaybackSessionRegistry(clock, staleMs = 100, reapInBackground = false)
        val owner = actor("viewer")
        val lease = sessions.create(
            owner, 1, "same", "https://example.test/stream", "", "",
        )
        val grants = PlaybackMediaGrants(sessions, clock = clock::nowMs)

        clock.value = 90
        assertFailsWith<PlaybackRevokedException> {
            grants.validate(lease.id, "not-a-grant")
        }
        clock.value = 101

        assertTrue(sessions.active().isEmpty())
        sessions.close()
    }

    @Test
    fun staleReaperSnapshotCannotRemoveALeaseRevivedByAHeartbeat() {
        val clock = MutableClock()
        lateinit var sessions: PlaybackSessionRegistry
        lateinit var first: PlaybackSessionRegistry.Live
        lateinit var second: PlaybackSessionRegistry.Live
        var revivedId: String? = null
        val cleanup = object : PlaybackLeaseCleanup {
            override fun memberLeaving(leaseId: String) = Unit
            override fun shareGroupUnused(group: String) = Unit

            override fun leaseTerminated(leaseId: String, unusedShareGroup: String?) {
                if (revivedId != null) return
                val revived = if (leaseId == first.id) second else first
                val revivedActor = if (revived.id == first.id) actor("first") else actor("second")
                revivedId = revived.id
                sessions.update(
                    revivedActor,
                    "",
                    "",
                    revived.state.copy(id = revived.id),
                )
            }
        }
        sessions = PlaybackSessionRegistry(
            clock,
            staleMs = 100,
            cleanup = cleanup,
            reapInBackground = false,
        )
        first = sessions.create(
            actor("first"), 1, "same", "https://example.test/stream", "", "",
        )
        second = sessions.create(
            actor("second"), 1, "same", "https://example.test/stream", "", "",
        )
        clock.value = 101

        val active = sessions.active()
        assertEquals(revivedId, active.single().id)
        sessions.close()
    }

    @Test
    fun mediaGrantIsBoundToOwnerSessionAndRevokedWithLease() {
        val sessions = PlaybackSessionRegistry(reapInBackground = false)
        val owner = actor("owner")
        val lease = sessions.create(
            owner, 1, "content", "https://example.test/stream", "", "",
        )
        val grants = PlaybackMediaGrants(sessions)
        val grant = grants.issue(owner, lease.id)

        grants.validateSource(lease.id, grant.token, "https://example.test/stream")
        assertFailsWith<PlaybackRevokedException> {
            sessions.owned(owner, "never-issued")
        }
        sessions.remove(lease.id)
        assertFailsWith<PlaybackRevokedException> {
            grants.validate(lease.id, grant.token)
        }
        sessions.close()
    }

    @Test
    fun refreshingMediaGrantDoesNotInvalidateAnInFlightPreviousGrant() {
        val sessions = PlaybackSessionRegistry(reapInBackground = false)
        val owner = actor("owner")
        val lease = sessions.create(
            owner, 1, "content", "https://example.test/stream", "", "",
        )
        val grants = PlaybackMediaGrants(sessions)
        val previous = grants.issue(owner, lease.id)
        val replacement = grants.issue(owner, lease.id)

        assertEquals(lease.id, grants.validate(lease.id, previous.token).id)
        assertEquals(lease.id, grants.validate(lease.id, replacement.token).id)
        sessions.close()
    }

    @Test
    fun revokingAnAuthSessionTerminatesItsPlaybackLeaseAndGrant() {
        val sessions = PlaybackSessionRegistry(reapInBackground = false)
        val owner = actor("owner")
        val lease = sessions.create(
            owner, 1, "content", "https://example.test/stream", "", "",
        )
        val grants = PlaybackMediaGrants(sessions)
        val grant = grants.issue(owner, lease.id)

        sessions.terminateSession(owner.authSessionId)

        assertFailsWith<PlaybackRevokedException> {
            grants.validate(lease.id, grant.token)
        }
        sessions.close()
    }

    @Test
    fun roomKickDeliversNoticeThenTombstonesOnlyTargetLease() = runBlocking {
        val sessions = PlaybackSessionRegistry(
            kickNoticeGraceMs = 25,
            reapInBackground = false,
        )
        val hostActor = actor("host")
        val guestActor = actor("guest")
        val host = sessions.create(
            hostActor, 1, "same", "https://example.test/stream", "", "",
        )
        val guest = sessions.create(
            guestActor, 1, "same", "https://example.test/stream", "", "",
        )
        assertTrue(join(sessions, host.id, guest.id))
        sessions.drainCommands(host.id)
        sessions.drainCommands(guest.id)

        assertTrue(sessions.kick(host.id, guest.id))

        assertEquals("room-ended", sessions.drainCommands(guest.id).single().type)
        assertTrue(guest.id !in sessions.roomMembers(host.id))
        assertEquals(guest.id, sessions.owned(guestActor, guest.id).id)
        delay(100)
        assertFailsWith<PlaybackRevokedException> { sessions.owned(guestActor, guest.id) }
        assertEquals(host.id, sessions.owned(hostActor, host.id).id)
        sessions.close()
    }

    @Test
    fun kickedOwnDeviceCannotAutoRejoinOrOpenMediaDuringNoticeGrace() = runBlocking {
        val sessions = PlaybackSessionRegistry(
            kickNoticeGraceMs = 250,
            reapInBackground = false,
        )
        val phoneActor = device("viewer", "phone-auth", "Phone")
        val televisionActor = device("viewer", "tv-auth", "Television")
        val phone = sessions.create(
            phoneActor, 1, "channel", "https://example.test/live/channel.ts", "", "",
            liveSource = true,
        )
        val television = sessions.create(
            televisionActor, 1, "channel", "https://example.test/live/channel.ts", "", "",
            liveSource = true,
        )
        val grants = PlaybackMediaGrants(sessions)
        val grant = grants.issue(televisionActor, television.id)
        assertNotNull(sessions.requestJoin(phone.id, television.id, "Television", "channel"))
        sessions.drainCommands(phone.id)
        sessions.drainCommands(television.id)

        assertTrue(sessions.kick(phone.id, television.id))

        assertEquals(
            null,
            sessions.requestJoin(phone.id, television.id, "Television", "channel"),
        )
        assertFailsWith<PlaybackRevokedException> {
            grants.validate(television.id, grant.token)
        }
        assertEquals("room-ended", sessions.drainCommands(television.id).single().type)
        sessions.close()
    }

    @Test
    fun kickedDeviceReplacementMustAskWhileAnotherAccountDeviceAutoJoins() {
        val sessions = PlaybackSessionRegistry(
            kickNoticeGraceMs = 10_000,
            reapInBackground = false,
        )
        val phoneActor = device("viewer", "phone-auth", "Phone")
        val televisionActor = device("viewer", "tv-auth", "Television")
        val host = sessions.create(
            actor("host"), 1, "movie", "https://example.test/movie.mkv", "", "",
        )
        val phone = sessions.create(
            phoneActor,
            1, "movie", "https://example.test/movie.mkv", "", "",
        )
        val phoneRequest = assertNotNull(
            sessions.requestJoin(host.id, phone.id, "Phone", "movie"),
        )
        assertTrue(sessions.answerJoin(host.id, phoneRequest, true))
        val television = sessions.create(
            televisionActor,
            1, "movie", "https://example.test/movie.mkv", "", "",
        )
        assertNotNull(sessions.requestJoin(phone.id, television.id, "Television", "movie"))
        listOf(host.id, phone.id, television.id).forEach(sessions::drainCommands)

        assertTrue(sessions.kick(host.id, television.id))
        sessions.drainCommands(host.id)
        sessions.drainCommands(phone.id)
        sessions.drainCommands(television.id)

        val replacement = sessions.create(
            device("viewer", televisionActor.authSessionId, "Replacement TV"),
            1, "movie", "https://example.test/movie.mkv", "", "",
        )
        val replacementRequest = assertNotNull(
            sessions.requestJoin(phone.id, replacement.id, "Replacement TV", "movie"),
        )

        assertEquals(null, sessions.roomOf(replacement.id))
        assertTrue(sessions.drainCommands(phone.id).none { it.type == "join-request" })
        assertTrue(sessions.drainCommands(host.id).any {
            it.type == "join-request" &&
                it.requestId == replacementRequest &&
                it.peerId == replacement.id
        })

        val tabletActor = device("viewer", "tablet-auth", "Tablet")
        val tablet = sessions.create(
            tabletActor,
            1, "movie", "https://example.test/movie.mkv", "", "",
        )
        listOf(host.id, phone.id, replacement.id).forEach(sessions::drainCommands)
        assertNotNull(sessions.requestJoin(phone.id, tablet.id, "Tablet", "movie"))
        assertEquals(sessions.roomOf(host.id), sessions.roomOf(tablet.id))
        assertEquals(null, sessions.roomOf(replacement.id))
        assertEquals(null, sessions.sameAccountConflict(tablet.id))
        assertTrue(sessions.drainCommands(host.id).none { it.type == "join-request" })

        assertTrue(sessions.answerJoin(host.id, replacementRequest, true))
        assertEquals(sessions.roomOf(host.id), sessions.roomOf(replacement.id))
        assertEquals(null, sessions.sameAccountConflict(replacement.id))

        val sameDeviceAgain = sessions.create(
            device("viewer", televisionActor.authSessionId, "Replacement TV again"),
            1, "movie", "https://example.test/movie.mkv", "", "",
        )
        listOf(host.id, phone.id, tablet.id, replacement.id).forEach(sessions::drainCommands)
        assertNotNull(
            sessions.requestJoin(replacement.id, sameDeviceAgain.id, "Replacement TV again", "movie"),
        )
        assertEquals(sessions.roomOf(host.id), sessions.roomOf(sameDeviceAgain.id))
        assertTrue(sessions.drainCommands(host.id).none { it.type == "join-request" })
        sessions.close()
    }

    @Test
    fun closingDuringKickGraceRevokesImmediatelyAndCancelsTheTimer() = runBlocking {
        val terminated = mutableListOf<String>()
        val cleanup = object : PlaybackLeaseCleanup {
            override fun memberLeaving(leaseId: String) = Unit
            override fun shareGroupUnused(group: String) = Unit

            override fun leaseTerminated(leaseId: String, unusedShareGroup: String?) {
                terminated += leaseId
            }
        }
        val sessions = PlaybackSessionRegistry(
            cleanup = cleanup,
            kickNoticeGraceMs = 250,
            reapInBackground = false,
        )
        val host = create(sessions, "host")
        val guest = create(sessions, "guest")
        assertTrue(join(sessions, host, guest))

        assertTrue(sessions.kick(host, guest))
        assertFalse(sessions.kick(host, guest))
        sessions.close()
        delay(300)

        assertEquals(listOf(host, guest).sorted(), terminated.sorted())
        assertEquals(terminated.distinct().size, terminated.size)
    }

    @Test
    fun sharedReadCleanupFollowsHostHandoffKickLastLeaveAndLeaseRevocation() {
        val leaving = mutableListOf<String>()
        val unusedGroups = mutableListOf<String>()
        val terminated = mutableListOf<String>()
        val cleanup = object : PlaybackLeaseCleanup {
            override fun memberLeaving(leaseId: String) {
                leaving += leaseId
            }

            override fun shareGroupUnused(group: String) {
                unusedGroups += group
            }

            override fun leaseTerminated(leaseId: String, unusedShareGroup: String?) {
                terminated += leaseId
            }
        }
        val sessions = PlaybackSessionRegistry(cleanup = cleanup, reapInBackground = false)
        try {
            val originalHost = create(sessions, "host")
            val first = create(sessions, "first")
            val second = create(sessions, "second")
            assertTrue(join(sessions, originalHost, first))
            assertTrue(join(sessions, originalHost, second))
            val firstGroup = sessions.shareGroup(originalHost)
            sessions.drainCommands(first)
            sessions.drainCommands(second)

            sessions.leaveRoom(originalHost)

            assertEquals(listOf(originalHost), leaving)
            assertTrue(unusedGroups.isEmpty(), "host handoff tore down the remaining room")
            assertEquals(firstGroup, sessions.shareGroup(first))
            val roster = sessions.drainCommands(first)
                .last { it.type == "room-state" }
                .members.orEmpty()
            val newHost = roster.single { it.host }.id
            val kicked = roster.single { !it.host }.id

            assertTrue(sessions.kick(newHost, kicked))
            assertEquals(listOf(originalHost, kicked), leaving)
            assertTrue(unusedGroups.isEmpty(), "kick tore down the remaining host's read")

            sessions.leaveRoom(newHost)
            assertEquals(listOf(firstGroup), unusedGroups)
            assertEquals(newHost, sessions.shareGroup(newHost))

            val revocationHost = create(sessions, "revocation-host")
            val revoked = create(sessions, "revoked")
            assertTrue(join(sessions, revocationHost, revoked))
            val revocationGroup = sessions.shareGroup(revocationHost)

            sessions.remove(revoked)
            assertTrue(revoked in leaving)
            assertTrue(revoked in terminated)
            assertTrue(revocationGroup !in unusedGroups)
            assertEquals(revocationGroup, sessions.shareGroup(revocationHost))

            // Ending the final lease (the channel-switch/lease-revocation path) releases the
            // room group; a replacement lease starts with an independent solo share id.
            sessions.remove(revocationHost)
            assertTrue(revocationHost in leaving)
            assertTrue(revocationHost in terminated)
            assertTrue(revocationGroup in unusedGroups)
            val replacement = create(sessions, "replacement-channel")
            assertEquals(replacement, sessions.shareGroup(replacement))
        } finally {
            sessions.close()
        }
    }

    @Test
    fun sharedMediaResourceStopsOnlyAfterFinalViewerReleasesIt() {
        val sessions = PlaybackSessionRegistry(reapInBackground = false)
        val firstActor = actor("first")
        val secondActor = actor("second")
        val first = sessions.create(
            firstActor, 1, "same", "https://example.test/stream", "", "",
        )
        val second = sessions.create(
            secondActor, 1, "same", "https://example.test/stream", "", "",
        )
        val grants = PlaybackMediaGrants(sessions)
        val firstGrant = grants.issue(firstActor, first.id)
        val secondGrant = grants.issue(secondActor, second.id)
        grants.bindResource(first.id, "shared-remux")
        grants.bindResource(second.id, "shared-remux")

        assertFalse(
            grants.releaseResource(first.id, firstGrant.token, "shared-remux")
        )
        assertTrue(grants.hasAttachments("shared-remux"))
        assertTrue(
            grants.releaseResource(second.id, secondGrant.token, "shared-remux")
        )
        assertFalse(grants.hasAttachments("shared-remux"))
        sessions.close()
    }

    @Test
    fun resourceAttachmentCannotPublishAfterLeaseRevocationCleanup() {
        lateinit var grants: PlaybackMediaGrants
        val cleanup = object : PlaybackLeaseCleanup {
            override fun memberLeaving(leaseId: String) = Unit
            override fun shareGroupUnused(group: String) = Unit
            override fun leaseTerminated(leaseId: String, unusedShareGroup: String?) {
                grants.revokeLease(leaseId)
            }
        }
        val sessions = PlaybackSessionRegistry(cleanup = cleanup, reapInBackground = false)
        val owner = actor("owner")
        val lease = sessions.create(
            owner, 1, "same", "https://example.test/stream", "", "",
        )
        grants = PlaybackMediaGrants(sessions)
        val resourceLock = PlaybackMediaGrants::class.java
            .getDeclaredField("resourceLock")
            .also { it.isAccessible = true }
            .get(grants)
        val executor = Executors.newFixedThreadPool(2)
        val bindingThread = java.util.concurrent.atomic.AtomicReference<Thread>()
        val removalThread = java.util.concurrent.atomic.AtomicReference<Thread>()

        try {
            val (binding, removal) = synchronized(resourceLock) {
                val binding = executor.submit {
                    bindingThread.set(Thread.currentThread())
                    sessions.withLiveLease(lease.id) {
                        grants.bindResource(lease.id, "late-remux")
                    }
                }
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1)
                while (bindingThread.get()?.state != Thread.State.BLOCKED &&
                    System.nanoTime() < deadline
                ) {
                    Thread.yield()
                }
                assertEquals(Thread.State.BLOCKED, bindingThread.get()?.state)

                val removal = executor.submit {
                    removalThread.set(Thread.currentThread())
                    sessions.remove(lease.id)
                }
                val removalDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1)
                while (removalThread.get()?.state != Thread.State.BLOCKED &&
                    System.nanoTime() < removalDeadline
                ) {
                    Thread.yield()
                }
                assertEquals(Thread.State.BLOCKED, removalThread.get()?.state)
                binding to removal
            }
            binding.get(1, TimeUnit.SECONDS)
            removal.get(1, TimeUnit.SECONDS)

            assertFalse(grants.hasAttachments("late-remux"))
        } finally {
            executor.shutdownNow()
            sessions.close()
        }
    }

    @Test
    fun joinAnswerRequiresPendingRequestAndMovesPeerAtomicallyBetweenRooms() {
        val sessions = PlaybackSessionRegistry(reapInBackground = false)
        val firstHost = create(sessions, "first-host")
        val secondHost = create(sessions, "second-host")
        val guest = create(sessions, "guest")

        assertFalse(sessions.answerJoin(firstHost, "invented", true))
        assertTrue(join(sessions, firstHost, guest))
        assertTrue(join(sessions, secondHost, guest))
        assertEquals("r-$secondHost", sessions.shareGroup(guest))
        assertEquals(setOf(firstHost), sessions.roomMembers(firstHost))
        assertEquals(setOf(secondHost, guest), sessions.roomMembers(secondHost))
        sessions.close()
    }

    @Test
    fun joinRequestExpiresAndCanOnlyBeConsumedOnce() {
        val clock = MutableClock()
        val sessions = PlaybackSessionRegistry(clock, reapInBackground = false)
        val host = create(sessions, "host")
        val guest = create(sessions, "guest")
        val expired = assertNotNull(sessions.requestJoin(host, guest, "Guest", "same"))
        clock.value = 60_001
        assertFalse(sessions.answerJoin(host, expired, true))

        val current = assertNotNull(sessions.requestJoin(host, guest, "Guest", "same"))
        assertTrue(sessions.answerJoin(host, current, true))
        assertFalse(sessions.answerJoin(host, current, true))
        sessions.close()
    }

    @Test
    fun roomCapabilitiesIntersectOnJoinAndExpandOnLeave() {
        val sessions = PlaybackSessionRegistry(reapInBackground = false)
        val native = MediaCapabilities(
            video = setOf("h264", "hevc", "av1"),
            audio = MediaCapabilities.BROWSER.audio + setOf("ac3", "eac3"),
            selectsTracksInBand = true,
        )
        val host = create(sessions, "host", native)
        val guest = create(sessions, "guest")

        assertEquals(native, sessions.roomCapabilities(host))
        assertTrue(join(sessions, host, guest))
        assertEquals(MediaCapabilities.BROWSER, sessions.roomCapabilities(host))
        assertEquals(MediaCapabilities.BROWSER, sessions.roomCapabilities(guest))
        assertFalse(sessions.roomCapabilities(host).selectsTracksInBand)
        assertTrue(sessions.drainCommands(host).any { it.type == "room-audio" })
        sessions.drainCommands(guest)

        sessions.leaveRoom(guest)

        assertEquals(native, sessions.roomCapabilities(host))
        assertTrue(sessions.drainCommands(host).any { it.type == "room-audio" })
        sessions.close()
    }

    @Test
    fun roomOfTwoInBandClientsRetainsInBandSelectionAndStillChangesShareGroup() {
        val sessions = PlaybackSessionRegistry(reapInBackground = false)
        val native = MediaCapabilities(
            video = setOf("h264", "hevc"),
            audio = setOf("aac", "eac3"),
            selectsTracksInBand = true,
        )
        val host = create(sessions, "host", native)
        val guest = create(sessions, "guest", native)

        assertTrue(join(sessions, host, guest))

        assertEquals(native, sessions.roomCapabilities(host))
        assertEquals(native, sessions.roomCapabilities(guest))
        assertTrue(sessions.roomCapabilities(host).selectsTracksInBand)
        // The format is unchanged, but membership changes the remux share group:
        // both clients must reopen onto the room's one provider read.
        assertTrue(sessions.drainCommands(host).any { it.type == "room-audio" })
        assertTrue(sessions.drainCommands(guest).any { it.type == "room-audio" })
        sessions.close()
    }

    @Test
    fun hostHandoffRecomputesIntersectionAndSignalsFormatReload() {
        val sessions = PlaybackSessionRegistry(reapInBackground = false)
        val native = MediaCapabilities(
            video = setOf("h264", "hevc"),
            audio = MediaCapabilities.BROWSER.audio + "eac3",
            selectsTracksInBand = true,
        )
        val host = create(sessions, "host")
        val guest = create(sessions, "guest", native)
        assertTrue(join(sessions, host, guest))
        sessions.drainCommands(host)
        sessions.drainCommands(guest)

        sessions.leaveRoom(host)

        assertEquals(native, sessions.roomCapabilities(guest))
        assertTrue(sessions.drainCommands(guest).any { it.type == "room-audio" })
        assertTrue(sessions.setRoomAudio(guest, 2))
        sessions.close()
    }

    @Test
    fun memberJoiningDuringReloadIsIncludedInANewBarrier() {
        val sessions = PlaybackSessionRegistry(reapInBackground = false)
        val host = create(sessions, "host")
        val firstGuest = create(sessions, "first")
        val lateGuest = create(sessions, "late")
        assertTrue(join(sessions, host, firstGuest))
        sessions.drainCommands(host)
        sessions.drainCommands(firstGuest)

        assertTrue(sessions.setRoomAudio(host, 2))
        val generation = assertNotNull(
            sessions.drainCommands(host)
                .single { it.type == "room-audio" }
                .generation,
        )
        sessions.drainCommands(firstGuest)
        assertTrue(sessions.markReady(host, generation))

        assertTrue(join(sessions, host, lateGuest))

        val nextHost = sessions.drainCommands(host).single { it.type == "room-audio" }
        val nextFirst = sessions.drainCommands(firstGuest).single { it.type == "room-audio" }
        val nextLate = sessions.drainCommands(lateGuest).single { it.type == "room-audio" }
        assertTrue(assertNotNull(nextHost.generation) > generation)
        assertEquals(nextHost.generation, nextFirst.generation)
        assertEquals(nextHost.generation, nextLate.generation)
        sessions.close()
    }

    @Test
    fun leavingMemberCannotStrandTheRemainingReadyMembers() {
        val sessions = PlaybackSessionRegistry(reapInBackground = false)
        val host = create(sessions, "host")
        val guest = create(sessions, "guest")
        assertTrue(join(sessions, host, guest))
        sessions.drainCommands(host)
        sessions.drainCommands(guest)
        assertTrue(sessions.setRoomAudio(host, 1))
        val generation = assertNotNull(
            sessions.drainCommands(host).single { it.type == "room-audio" }.generation,
        )
        sessions.drainCommands(guest)
        assertTrue(sessions.markReady(host, generation))

        sessions.leaveRoom(guest)

        assertTrue(sessions.drainCommands(host).any { it.type == "room-go" })
        sessions.close()
    }

    @Test
    fun unreadyMemberCannotStrandTheRoomReloadBarrier() = runBlocking {
        val sessions = PlaybackSessionRegistry(staleMs = 25, reapInBackground = false)
        val host = create(sessions, "host")
        val guest = create(sessions, "guest")
        assertTrue(join(sessions, host, guest))
        val generation = assertNotNull(
            sessions.drainCommands(host).single { it.type == "room-audio" }.generation,
        )
        sessions.drainCommands(guest)
        assertTrue(sessions.markReady(host, generation))

        val roomGo = withTimeout(1_000) {
            while (true) {
                sessions.drainCommands(host).firstOrNull { it.type == "room-go" }?.let {
                    return@withTimeout it
                }
                delay(5)
            }
            error("unreachable")
        }

        assertEquals(generation, roomGo.generation)
        sessions.close()
    }

    @Test
    fun readyIsGenerationBoundAndIdempotentAndLeaveIsIdempotent() {
        val sessions = PlaybackSessionRegistry(reapInBackground = false)
        val host = create(sessions, "host")
        val guest = create(sessions, "guest")
        assertTrue(join(sessions, host, guest))
        val hostBarrier = sessions.drainCommands(host).single { it.type == "room-audio" }
        val guestBarrier = sessions.drainCommands(guest).single { it.type == "room-audio" }
        val generation = assertNotNull(hostBarrier.generation)
        assertEquals(generation, guestBarrier.generation)

        assertTrue(sessions.markReady(host, generation))
        assertTrue(sessions.markReady(host, generation))
        assertFalse(sessions.markReady(host, 0))
        assertFalse(sessions.markReady(host, -1))
        assertFalse(sessions.markReady(guest, generation - 1))
        assertTrue(sessions.drainCommands(host).none { it.type == "room-go" })
        assertTrue(sessions.drainCommands(guest).none { it.type == "room-go" })

        assertTrue(sessions.markReady(guest, generation))
        val hostGo = sessions.drainCommands(host).single { it.type == "room-go" }
        val guestGo = sessions.drainCommands(guest).single { it.type == "room-go" }
        assertEquals(generation, hostGo.generation)
        assertEquals(generation, guestGo.generation)
        assertTrue(sessions.markReady(guest, generation))
        assertTrue(sessions.drainCommands(host).isEmpty())

        sessions.leaveRoom(guest)
        sessions.leaveRoom(guest)
        assertEquals(setOf(host), sessions.roomMembers(host))
        sessions.close()
    }

    @Test
    fun lateReadyFromSupersededBarrierCannotReleaseTheNewerBarrier() {
        val sessions = PlaybackSessionRegistry(reapInBackground = false)
        val host = create(sessions, "host")
        val guest = create(sessions, "guest")
        assertTrue(join(sessions, host, guest))
        val firstGeneration = assertNotNull(
            sessions.drainCommands(host).single { it.type == "room-audio" }.generation,
        )
        sessions.drainCommands(guest)

        assertTrue(sessions.setRoomAudio(host, 2))
        val secondGeneration = assertNotNull(
            sessions.drainCommands(host).single { it.type == "room-audio" }.generation,
        )
        sessions.drainCommands(guest)
        assertTrue(secondGeneration > firstGeneration)

        assertFalse(sessions.markReady(host, firstGeneration))
        assertFalse(sessions.markReady(guest, firstGeneration))
        assertTrue(sessions.drainCommands(host).none { it.type == "room-go" })
        assertTrue(sessions.markReady(host, secondGeneration))
        assertTrue(sessions.drainCommands(host).none { it.type == "room-go" })
        assertTrue(sessions.markReady(guest, secondGeneration))

        assertEquals(
            secondGeneration,
            sessions.drainCommands(host).single { it.type == "room-go" }.generation,
        )
        sessions.close()
    }

    @Test
    fun everyEmittedCommandHasAPerLeaseIncreasingSequence() {
        val sessions = PlaybackSessionRegistry(reapInBackground = false)
        val host = create(sessions, "host")
        val guest = create(sessions, "guest")
        assertTrue(join(sessions, host, guest))
        assertTrue(sessions.setRoomAudio(host, 2))

        listOf(host, guest).forEach { member ->
            val sequences = sessions.drainCommands(member).map { assertNotNull(it.sequence) }
            assertTrue(sequences.isNotEmpty())
            assertTrue(sequences.all { it > 0 })
            assertEquals(sequences.sorted(), sequences)
            assertEquals(sequences.distinct(), sequences)
        }
        sessions.close()
    }

    @Test
    fun stalledLeaseRetainsOnlyABoundedCommandBacklog() {
        val sessions = PlaybackSessionRegistry(reapInBackground = false)
        val lease = create(sessions, "stalled")
        val offered = 10_000

        repeat(offered) { index ->
            assertTrue(
                sessions.enqueue(
                    lease,
                    SessionCommandDto(type = "message", text = "message-$index"),
                ),
            )
        }

        val retained = sessions.drainCommands(lease)
        println("PLAYBACK_COMMAND_BACKLOG offered=$offered retained=${retained.size}")
        assertEquals(PlaybackSessionRegistry.MAX_QUEUED_COMMANDS_PER_LEASE, retained.size)
        assertEquals(
            "message-${offered - PlaybackSessionRegistry.MAX_QUEUED_COMMANDS_PER_LEASE}",
            retained.first().text,
        )
        assertEquals("message-${offered - 1}", retained.last().text)
        assertTrue(retained.zipWithNext().all { (first, second) ->
            requireNotNull(first.sequence) < requireNotNull(second.sequence)
        })
        sessions.close()
    }

    @Test
    fun reconnectResendsAnActiveReloadBarrierAfterTheRoster() {
        val sessions = PlaybackSessionRegistry(reapInBackground = false)
        val host = create(sessions, "host")
        val guest = create(sessions, "guest")
        assertTrue(join(sessions, host, guest))
        sessions.drainCommands(host)
        sessions.drainCommands(guest)
        assertTrue(sessions.setRoomAudio(host, 2))
        sessions.drainCommands(guest)

        sessions.resendRoomState(guest)

        assertEquals(
            listOf("room-state", "room-audio"),
            sessions.drainCommands(guest).map { it.type },
        )
        sessions.close()
    }

    @Test
    fun reconnectReplaysACompletedBarrierAfterADelayedFallbackDrain() {
        val sessions = PlaybackSessionRegistry(reapInBackground = false)
        val host = create(sessions, "host")
        val guest = create(sessions, "guest")
        assertTrue(join(sessions, host, guest))
        sessions.drainCommands(host)
        sessions.drainCommands(guest)
        assertTrue(sessions.setRoomAudio(host, 2))
        val hostBarrier = sessions.drainCommands(host).single { it.type == "room-audio" }
        val guestBarrier = sessions.drainCommands(guest).single { it.type == "room-audio" }
        val generation = assertNotNull(hostBarrier.generation)
        assertEquals(generation, guestBarrier.generation)
        assertTrue(sessions.markReady(host, generation))
        assertTrue(sessions.markReady(guest, generation))
        sessions.drainCommands(host)

        val delayedFallback = sessions.drainCommands(guest).single { it.type == "room-go" }
        sessions.resendRoomState(guest)

        val reconnect = sessions.drainCommands(guest)
        assertEquals(listOf("room-state", "room-go"), reconnect.map { it.type })
        assertEquals(delayedFallback.generation, reconnect.last().generation)
        assertTrue(assertNotNull(reconnect.first().sequence) > assertNotNull(delayedFallback.sequence))
        sessions.close()
    }

    @Test
    fun commandDrainCannotSplitAnAtomicProtocolBatchAcrossTransports() {
        val sessions = PlaybackSessionRegistry(reapInBackground = false)
        val lease = create(sessions, "viewer")
        val started = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        var drained = emptyList<SessionCommandDto>()
        try {
            synchronized(sessions) {
                sessions.enqueue(
                    lease,
                    SessionCommandDto(
                        type = "room-state",
                        members = emptyList(),
                    ),
                )
                executor.execute {
                    started.countDown()
                    drained = sessions.drainCommands(lease)
                    finished.countDown()
                }
                assertTrue(started.await(1, TimeUnit.SECONDS))
                assertFalse(
                    finished.await(100, TimeUnit.MILLISECONDS),
                    "a heartbeat drain escaped the room protocol transaction",
                )
                sessions.enqueue(
                    lease,
                    SessionCommandDto(
                        type = "room-audio",
                        audioIndex = 0,
                        generation = 1,
                    ),
                )
            }
            assertTrue(finished.await(1, TimeUnit.SECONDS))
            assertEquals(listOf("room-state", "room-audio"), drained.map { it.type })
        } finally {
            executor.shutdownNow()
            sessions.close()
        }
    }
}
