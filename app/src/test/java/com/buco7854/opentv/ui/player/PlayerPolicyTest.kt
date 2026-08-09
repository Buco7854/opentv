package com.buco7854.opentv.ui.player

import android.view.KeyEvent
import androidx.media3.ui.AspectRatioFrameLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerPolicyTest {

    @Test
    fun `clock formatting handles negative short and long positions`() {
        assertEquals("0:00", formatPlaybackClock(-1))
        assertEquals("1:05", formatPlaybackClock(65_000))
        assertEquals("1:01:01", formatPlaybackClock(3_661_000))
    }

    @Test
    fun `resize mode cycles through supported modes`() {
        assertEquals(
            AspectRatioFrameLayout.RESIZE_MODE_ZOOM,
            nextResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FIT),
        )
        assertEquals(
            AspectRatioFrameLayout.RESIZE_MODE_FILL,
            nextResizeMode(AspectRatioFrameLayout.RESIZE_MODE_ZOOM),
        )
        assertEquals(
            AspectRatioFrameLayout.RESIZE_MODE_FIT,
            nextResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FILL),
        )
    }

    @Test
    fun `resume is applied only before the end guard`() {
        assertFalse(shouldApplyResume(0, 120_000))
        assertTrue(shouldApplyResume(10_000, 120_000))
        // Persistence retains this exact boundary, so playback must honor it too.
        assertTrue(shouldApplyResume(105_000, 120_000))
        assertFalse(shouldApplyResume(105_001, 120_000))
        assertFalse(shouldApplyResume(10_000, Long.MIN_VALUE + 1))
        assertFalse(shouldApplyResume(1, 15_000))
    }

    @Test
    fun `progress is not persisted before the duration is known`() {
        // C.TIME_UNSET, reported until the timeline arrives.
        assertFalse(shouldPersistProgress(Long.MIN_VALUE + 1, live = false))
        assertFalse(shouldPersistProgress(0, live = false))
        assertFalse(shouldPersistProgress(120_000, live = true))
        assertTrue(shouldPersistProgress(120_000, live = false))
    }

    @Test
    fun `only stable on-demand targets participate in resume storage`() {
        assertTrue(shouldTrackProgress(LOCAL_VOD_TARGET))
        assertTrue(shouldTrackProgress(HUB_VOD_TARGET))
        assertFalse(shouldTrackProgress(LOCAL_VOD_TARGET.copy(live = true)))
        assertFalse(
            shouldTrackProgress(
                PlayerTarget.HubCatchUp(
                    hubId = 1,
                    playlistId = 2,
                    contentId = "channel",
                    title = "Programme",
                    startMs = 1_000,
                    durationMs = 60_000,
                ),
            ),
        )
    }

    @Test
    fun `pip ratio ignores empty video and clamps system limits`() {
        assertNull(pipAspectRatio(0, 1080))
        assertEquals(2.39f, pipAspectRatio(4_000, 1_000)!!, 0.001f)
        assertEquals(0.42f, pipAspectRatio(1_000, 4_000)!!, 0.001f)
        assertEquals(16f / 9f, pipAspectRatio(1920, 1080)!!, 0.001f)
    }

    @Test
    fun `configuration disposal retains the hub lease but real navigation ends it`() {
        assertFalse(shouldClosePlayerOnDispose(isChangingConfigurations = true))
        assertTrue(shouldClosePlayerOnDispose(isChangingConfigurations = false))
    }

    @Test
    fun `auto pip follows playback intent and refuses failed playback`() {
        assertTrue(shouldAutoEnterPip(playWhenReady = true, hasError = false))
        assertFalse(shouldAutoEnterPip(playWhenReady = false, hasError = false))
        assertFalse(shouldAutoEnterPip(playWhenReady = true, hasError = true))
    }

    @Test
    fun `player failures expose the recovery that can actually help`() {
        assertEquals(PlayerErrorAction.RETRY_SESSION, playerErrorAction(null))
        assertEquals(
            PlayerErrorAction.RETRY_HUB,
            playerErrorAction(PlayerProblem.FAILED),
        )
        assertEquals(
            PlayerErrorAction.RETRY_HUB,
            playerErrorAction(PlayerProblem.AT_CAPACITY),
        )
        assertEquals(
            PlayerErrorAction.SIGN_IN,
            playerErrorAction(PlayerProblem.SIGNED_OUT),
        )
        // A lease ends when an administrator ends it and equally when the server
        // reclaims one that stopped reporting in, which is what a spell in the
        // background does. Offering nothing told a viewer who took a phone call that
        // the film was over.
        assertEquals(
            PlayerErrorAction.RETRY_HUB,
            playerErrorAction(PlayerProblem.PLAYBACK_ENDED),
        )
    }

    @Test
    fun `a stream lost while the app was away is taken back on return`() {
        assertTrue(
            shouldReclaimStreamOnReturn(PlayerProblem.PLAYBACK_ENDED, endedWhileAway = true),
        )
    }

    @Test
    fun `a close delivered just after returning still counts as lost while away`() {
        // The usual order: the server drops the lease while the app is frozen, and the
        // socket close only arrives once Android lets it run again. By then we are back,
        // so asking "are we away right now" answers no and the recovery never fires.
        assertTrue(endedByBeingAway(hostAway = false, sinceReturnMs = 0))
        assertTrue(endedByBeingAway(hostAway = false, sinceReturnMs = RETURN_GRACE_MS))
        assertTrue(endedByBeingAway(hostAway = true, sinceReturnMs = Long.MAX_VALUE / 2))
    }

    @Test
    fun `a lease ending long after returning is not blamed on the absence`() {
        assertFalse(endedByBeingAway(hostAway = false, sinceReturnMs = RETURN_GRACE_MS + 1))
        // Never having left: the elapsed measure starts far in the past so that an
        // administrator ending a stream moments after the player opens is left alone.
        assertFalse(endedByBeingAway(hostAway = false, sinceReturnMs = Long.MAX_VALUE / 2))
    }

    @Test
    fun `a stream lost in front of the viewer waits for them to ask`() {
        // Somebody decided this: an administrator, or another device. It stays on
        // screen with a retry rather than being silently reclaimed under them.
        assertFalse(
            shouldReclaimStreamOnReturn(PlayerProblem.PLAYBACK_ENDED, endedWhileAway = false),
        )
        // And returning with any other problem, or none, reclaims nothing.
        assertFalse(shouldReclaimStreamOnReturn(PlayerProblem.SIGNED_OUT, endedWhileAway = true))
        assertFalse(shouldReclaimStreamOnReturn(PlayerProblem.AT_CAPACITY, endedWhileAway = true))
        assertFalse(shouldReclaimStreamOnReturn(null, endedWhileAway = true))
    }

    @Test
    fun `watch choice remains visible before media exists`() {
        assertTrue(shouldShowWatchTogetherBeforeMedia(WatchTogetherState(choosing = true)))
        assertTrue(
            shouldShowWatchTogetherBeforeMedia(WatchTogetherState(duplicateRefused = true)),
        )
        assertFalse(shouldShowWatchTogetherBeforeMedia(WatchTogetherState(checking = true)))
    }

    @Test
    fun `back peels notice then controls before exiting playback`() {
        assertEquals(
            PlayerBackAction.DISMISS_NOTICE,
            playerBackAction(hasNotice = true, controlsVisible = true),
        )
        assertEquals(
            PlayerBackAction.HIDE_CONTROLS,
            playerBackAction(hasNotice = false, controlsVisible = true),
        )
        assertEquals(
            PlayerBackAction.EXIT,
            playerBackAction(hasNotice = false, controlsVisible = false),
        )
    }

    @Test
    fun `an admin message gets reading time while our own copy keeps its beat`() {
        // Our own one-liners are unaffected by length: they are fixed copy.
        assertEquals(3_500L, noticeDwellMs(WatchTogetherNoticeKind.JOINED, 0))
        assertEquals(3_500L, noticeDwellMs(WatchTogetherNoticeKind.CONTROL_GRANTED, 400))
        assertEquals(6_000L, noticeDwellMs(WatchTogetherNoticeKind.ROOM_ENDED, 0))

        // A short admin message still gets at least the old six seconds, so the
        // scaling never makes a notice briefer than it used to be.
        assertEquals(6_000L, noticeDwellMs(WatchTogetherNoticeKind.ADMIN_MESSAGE, 0))
        assertEquals(6_000L, noticeDwellMs(WatchTogetherNoticeKind.ADMIN_MESSAGE, 20))

        // The case this exists for: the server allows 1000 characters, which cannot
        // be read in six seconds. It must scale well past that, and stay bounded.
        val long = noticeDwellMs(WatchTogetherNoticeKind.ADMIN_MESSAGE, 1_000)
        assertTrue("1000 chars needs real reading time, got $long", long >= 45_000L)
        assertEquals(60_000L, noticeDwellMs(WatchTogetherNoticeKind.ADMIN_MESSAGE, 10_000))

        // Monotonic in length, so a longer message is never shown for less time.
        val lengths = listOf(0, 50, 200, 500, 1_000, 5_000)
        val dwells = lengths.map { noticeDwellMs(WatchTogetherNoticeKind.ADMIN_MESSAGE, it) }
        assertEquals(dwells.sorted(), dwells)
    }

    @Test
    fun `a drifting admin message always has room to travel inside its dwell`() {
        // The overlay scrolls a long message to its end over the dwell, less a lead-in
        // and a tail. If the constants ever drift past each other that subtraction goes
        // negative and the animation would be clamped to a single frame, dumping the
        // reader at the bottom instantly.
        val shortest = noticeDwellMs(WatchTogetherNoticeKind.ADMIN_MESSAGE, 0)
        assertTrue(
            "lead-in plus tail must fit inside even the shortest admin dwell",
            READING_LEAD_IN_MS + READING_TAIL_MS < shortest,
        )
    }

    @Test
    fun `hidden chrome is reachable from remote keys`() {
        assertEquals(
            PlayerRemoteAction.SHOW_CONTROLS,
            playerRemoteAction(true, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.ACTION_DOWN),
        )
        assertEquals(
            PlayerRemoteAction.SHOW_CONTROLS,
            playerRemoteAction(true, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.ACTION_DOWN),
        )
        assertEquals(
            PlayerRemoteAction.SEEK_BACK,
            playerRemoteAction(true, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.ACTION_DOWN),
        )
        assertEquals(
            PlayerRemoteAction.SEEK_FORWARD,
            playerRemoteAction(true, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.ACTION_DOWN),
        )
        assertEquals(
            PlayerRemoteAction.NONE,
            playerRemoteAction(false, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.ACTION_DOWN),
        )
        assertEquals(
            PlayerRemoteAction.NONE,
            playerRemoteAction(true, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.ACTION_UP),
        )
    }

    @Test
    fun `hardware media keys work regardless of chrome focus`() {
        assertEquals(
            PlayerMediaAction.TOGGLE_PLAYBACK,
            playerMediaAction(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.ACTION_DOWN),
        )
        assertEquals(
            PlayerMediaAction.PLAY,
            playerMediaAction(KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.ACTION_DOWN),
        )
        assertEquals(
            PlayerMediaAction.PAUSE,
            playerMediaAction(KeyEvent.KEYCODE_MEDIA_PAUSE, KeyEvent.ACTION_DOWN),
        )
        assertEquals(
            PlayerMediaAction.SEEK_BACK,
            playerMediaAction(KeyEvent.KEYCODE_MEDIA_REWIND, KeyEvent.ACTION_DOWN),
        )
        assertEquals(
            PlayerMediaAction.SEEK_FORWARD,
            playerMediaAction(KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, KeyEvent.ACTION_DOWN),
        )
        assertEquals(
            PlayerMediaAction.NONE,
            playerMediaAction(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.ACTION_UP),
        )
    }

    @Test
    fun `ended playback restarts instead of toggling an ineffective play intent`() {
        assertEquals(
            PlayerToggleAction.PAUSE,
            playerToggleAction(playWhenReady = true, ended = false),
        )
        assertEquals(
            PlayerToggleAction.PLAY,
            playerToggleAction(playWhenReady = false, ended = false),
        )
        assertEquals(
            PlayerToggleAction.RESTART,
            playerToggleAction(playWhenReady = false, ended = true),
        )
        assertEquals(
            PlayerToggleAction.RESTART,
            playerToggleAction(playWhenReady = true, ended = true),
        )
    }

    @Test
    fun `blank provider track labels fall back to a visible language`() {
        assertEquals("French", visibleTrackLabel(" ", "French"))
        assertEquals("Director commentary", visibleTrackLabel("Director commentary", "English"))
    }

    private companion object {
        val LOCAL_VOD_TARGET = PlayerTarget.LocalUrl(
            url = "https://provider.test/movie",
            title = "Movie",
            playlistId = 1,
            tvgId = null,
            live = false,
        )
        val HUB_VOD_TARGET = PlayerTarget.HubContent(
            hubId = 1,
            playlistId = 2,
            contentId = "movie",
            title = "Movie",
            live = false,
        )
    }
}
