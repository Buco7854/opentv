package com.buco7854.opentv.server

import com.buco7854.opentv.contract.*
import java.net.URI
import java.net.URLDecoder
import java.nio.file.Files
import java.util.Base64
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MediaCapabilityTest {
    private val cipher = StreamCipher(
        Base64.getEncoder().encodeToString(ByteArray(32) { it.toByte() })
    )
    private val connections = ProviderConnections()
    private val gate = StreamGate(connections)
    private val proxy = StreamProxy(ServerHttp(), cipher, gate) { 1 }
    private val sessions = PlaybackSessionRegistry(reapInBackground = false)
    private val grants = PlaybackMediaGrants(sessions)

    @AfterTest
    fun tearDown() {
        sessions.close()
        gate.close()
        connections.closeAll()
    }

    private fun actor(id: String) =
        Actor(id, "auth-$id", id, id, setOf("USER"), "PASSWORD", "BROWSER")

    private fun queryParameter(url: String, name: String): String? =
        URI(url).query?.split('&')?.firstNotNullOfOrNull { pair ->
            pair.substringBefore('=').takeIf { it == name }
                ?.let { URLDecoder.decode(pair.substringAfter('='), Charsets.UTF_8) }
        }

    private val manifest = URI("https://provider.example/live/user/pass/42.m3u8")

    @Test
    fun `rewritten HLS child stays playable by the lease that fetched the manifest`() {
        val owner = actor("owner")
        val lease = sessions.create(owner, 1, "content", manifest.toString(), "", "")
        val grant = grants.issue(owner, lease.id)

        val rewritten = proxy.rewriteHls(
            "#EXTM3U\n#EXTINF:6.0,\nsegment1.ts\n",
            manifest,
            lease.id,
            grant.token,
        )

        val child = rewritten.lineSequence().first { it.startsWith("/api/v1/stream") }
        val capability = assertNotNull(
            cipher.tryDecryptStream(assertNotNull(queryParameter(child, "u")))
        )
        assertEquals("https://provider.example/live/user/pass/segment1.ts", capability.url)
        assertEquals(lease.id, capability.leaseId)
        assertTrue(capability.hlsResource)

        assertTrue(capability.url != lease.sourceUrl)
        assertFailsWith<PlaybackRevokedException> {
            grants.validateSource(lease.id, grant.token, capability.url)
        }
        grants.validateCapability(lease.id, grant.token, capability)
    }

    @Test
    fun `a capability cannot be replayed by another lease`() {
        val owner = actor("owner")
        val other = actor("other")
        val lease = sessions.create(owner, 1, "content", manifest.toString(), "", "")
        val otherLease = sessions.create(other, 1, "content", manifest.toString(), "", "")
        val otherGrant = grants.issue(other, otherLease.id)

        val capability = assertNotNull(
            cipher.tryDecryptStream(cipher.encryptStream("https://provider.example/x.ts", lease.id))
        )

        assertFailsWith<PlaybackRevokedException> {
            grants.validateCapability(otherLease.id, otherGrant.token, capability)
        }
    }

    @Test
    fun `off-origin playlist URIs are refused instead of proxied`() {
        val lease = sessions.create(actor("owner"), 1, "content", manifest.toString(), "", "")

        val rewritten = proxy.rewriteHls(
            """
            #EXTM3U
            #EXT-X-KEY:METHOD=AES-128,URI="http://169.254.169.254/latest/meta-data/"
            #EXTINF:6.0,
            https://evil.example/segment1.ts
            #EXTINF:6.0,
            http://provider.example:8080/segment2.ts
            #EXTINF:6.0,
            segment3.ts
            """.trimIndent(),
            manifest,
            lease.id,
            "grant",
        )

        assertTrue("169.254.169.254" !in rewritten)
        assertTrue("evil.example" !in rewritten)
        assertTrue("8080" !in rewritten)

        val minted = rewritten.lineSequence()
            .filter { it.trimStart().startsWith("/api/v1/stream?") }
            .mapNotNull { queryParameter(it.trim(), "u") }
            .mapNotNull(cipher::tryDecryptStream)
            .toList()
        assertEquals(
            listOf("https://provider.example/live/user/pass/segment3.ts"),
            minted.map { it.url },
        )
        assertEquals(3, rewritten.lines().count { it.startsWith("#EXTINF") })
    }

    @Test
    fun `default and explicit ports are the same origin`() {
        val lease = sessions.create(actor("owner"), 1, "content", manifest.toString(), "", "")

        val rewritten = proxy.rewriteHls(
            "#EXTM3U\nhttps://provider.example:443/segment1.ts\n",
            manifest,
            lease.id,
            "grant",
        )

        val capability = assertNotNull(
            rewritten.lineSequence().first { it.startsWith("/api/v1/stream") }
                .let { queryParameter(it, "u") }
                ?.let(cipher::tryDecryptStream)
        )
        assertEquals("https://provider.example:443/segment1.ts", capability.url)
    }

    @Test
    fun `a stream token without a lease is not a capability`() {
        assertNull(cipher.tryDecryptStream("h.not-a-token"))
        assertFailsWith<IllegalArgumentException> {
            cipher.encryptStream("https://provider.example/x.ts", "")
        }
    }

    @Test
    fun `missing capability report pins the browser baseline`() {
        val capabilities = MediaCapabilities.from(null)

        assertEquals(setOf("h264"), capabilities.video)
        assertEquals(setOf("aac", "mp3", "opus", "flac", "vorbis"), capabilities.audio)
        assertFalse(capabilities.selectsTracksInBand)
        assertTrue(capabilities.videoDecodable("H264"))
        assertTrue(capabilities.audioDecodable("aac"))
        assertFalse(capabilities.videoDecodable("hevc"))
        assertFalse(capabilities.audioDecodable("eac3"))

        withRemux("h264", listOf("aac")) { remux ->
            assertFailsWith<RemuxService.NoExtraTracksException> {
                remux.start("https://provider.example/browser-h264.mp4", 0, capabilities,
                    false, 4, "browser", emptySet())
            }
        }
        withRemux("hevc", listOf("aac")) { remux ->
            val result = remux.start("https://provider.example/browser-hevc.mkv", 0, capabilities,
                false, 4, "browser", emptySet())
            assertTrue(assertNotNull(remux.diagnostics(result.id)).transcodeVideo)
        }
        withRemux("h264", listOf("eac3")) { remux ->
            val result = remux.start("https://provider.example/browser-eac3.mkv", 0, capabilities,
                false, 4, "browser", emptySet())
            assertFalse(assertNotNull(remux.diagnostics(result.id)).transcodeVideo)
        }
    }

    @Test
    fun `browser clients still remux muxed audio and subtitle choices`() {
        val absent = MediaCapabilities.from(null)
        val explicit = MediaCapabilities.from(
            ClientCapabilitiesDto(
                videoCodecs = listOf("h264"),
                audioCodecs = listOf("aac"),
                selectsTracksInBand = false,
            )
        )

        listOf(absent, explicit).forEachIndexed { index, capabilities ->
            withRemux("h264", listOf("aac", "aac")) { remux ->
                val result = remux.start(
                    "https://provider.example/browser-multi-$index.mkv",
                    0, capabilities, false, 4, "browser-$index", emptySet(),
                )
                assertEquals(2, result.audioTracks.size)
            }
            withRemux("h264", listOf("aac"), listOf("subrip")) { remux ->
                val result = remux.start(
                    "https://provider.example/browser-subs-$index.mkv",
                    0, capabilities, false, 4, "browser-$index", emptySet(),
                )
                assertEquals(1, result.subtitleTracks.size)
            }
        }
    }

    @Test
    fun `in-band client directs multi-audio and subtitles when every codec is decodable`() {
        val capabilities = MediaCapabilities.from(
            ClientCapabilitiesDto(
                videoCodecs = listOf("h264"),
                audioCodecs = listOf("aac", "eac3"),
                selectsTracksInBand = true,
            )
        )

        withRemux("h264", listOf("aac", "eac3"), listOf("subrip")) { remux ->
            assertFailsWith<RemuxService.NoExtraTracksException> {
                remux.start(
                    "https://provider.example/native-tracks.mkv",
                    0, capabilities, false, 4, "native", emptySet(),
                )
            }
        }
    }

    @Test
    fun `in-band client remuxes rather than hiding one undecodable audio language`() {
        val capabilities = MediaCapabilities.from(
            ClientCapabilitiesDto(
                videoCodecs = listOf("h264"),
                audioCodecs = listOf("aac"),
                selectsTracksInBand = true,
            )
        )

        withRemux("h264", listOf("aac", "eac3")) { remux ->
            val result = remux.start(
                "https://provider.example/native-mixed-audio.mkv",
                0, capabilities, false, 4, "native", emptySet(),
            )
            assertEquals(2, result.audioTracks.size)
        }
    }

    @Test
    fun `timeshift still remuxes for an in-band client`() {
        val capabilities = MediaCapabilities.from(
            ClientCapabilitiesDto(
                videoCodecs = listOf("h264"),
                audioCodecs = listOf("aac"),
                selectsTracksInBand = true,
            )
        )

        withRemux("h264", listOf("aac")) { remux ->
            val result = remux.start(
                "https://provider.example/catchup.ts",
                0, capabilities, true, 4, "native", emptySet(),
            )
            assertNotNull(remux.diagnostics(result.id))
        }
    }

    @Test
    fun `room intersection directs all-native members and remuxes a mixed room`() {
        val native = MediaCapabilities.from(
            ClientCapabilitiesDto(
                videoCodecs = listOf("h264"),
                audioCodecs = listOf("aac"),
                selectsTracksInBand = true,
            )
        )
        val first = sessions.create(
            actor("native-1"), 1, "room-content", "https://provider.example/movie.mkv",
            "", "", native,
        ).id
        val second = sessions.create(
            actor("native-2"), 1, "room-content", "https://provider.example/movie.mkv",
            "", "", native,
        ).id
        val nativeJoin = assertNotNull(
            sessions.requestJoin(first, second, "Native 2", "room-content")
        )
        assertTrue(sessions.answerJoin(first, nativeJoin, true))

        listOf(first, second).forEach { member ->
            val effective = sessions.roomCapabilities(member)
            assertTrue(effective.selectsTracksInBand)
            withRemux("h264", listOf("aac", "aac"), listOf("subrip")) { remux ->
                assertFailsWith<RemuxService.NoExtraTracksException> {
                    remux.start(
                        "https://provider.example/$member.mkv",
                        0, effective, false, 4, sessions.shareGroup(member), emptySet(),
                    )
                }
            }
        }

        val browser = sessions.create(
            actor("browser"), 1, "room-content", "https://provider.example/movie.mkv",
            "", "", MediaCapabilities.BROWSER,
        ).id
        val browserJoin = assertNotNull(
            sessions.requestJoin(first, browser, "Browser", "room-content")
        )
        assertTrue(sessions.answerJoin(first, browserJoin, true))

        listOf(first, second, browser).forEach { member ->
            val effective = sessions.roomCapabilities(member)
            assertFalse(effective.selectsTracksInBand)
            withRemux("h264", listOf("aac", "aac"), listOf("subrip")) { remux ->
                val result = remux.start(
                    "https://provider.example/mixed-$member.mkv",
                    0, effective, false, 4, sessions.shareGroup(member), emptySet(),
                )
                assertEquals(2, result.audioTracks.size)
                assertEquals(1, result.subtitleTracks.size)
            }
        }
    }

    @Test
    fun `hevc capable lease copies hevc and directs simple files`() {
        val capabilities = MediaCapabilities.from(
            ClientCapabilitiesDto(
                videoCodecs = listOf("h264", "hevc"),
                audioCodecs = listOf("aac"),
            )
        )

        withRemux("hevc", listOf("aac")) { remux ->
            assertFailsWith<RemuxService.NoExtraTracksException> {
                remux.start("https://provider.example/native-hevc.mkv", 0, capabilities,
                    false, 4, "native", emptySet())
            }
        }
        withRemux("hevc", listOf("aac", "aac")) { remux ->
            val result = remux.start("https://provider.example/native-hevc-tracks.mkv", 0, capabilities,
                false, 4, "native", emptySet())
            assertTrue(result.nativeVideoCopy)
            assertFalse(assertNotNull(remux.diagnostics(result.id)).transcodeVideo)
        }
    }

    @Test
    fun `advanced video and audio capabilities direct play without extra tracks`() {
        val capabilities = MediaCapabilities.from(
            ClientCapabilitiesDto(
                videoCodecs = listOf("av1"),
                audioCodecs = listOf("eac3"),
            )
        )

        withRemux("av1", listOf("eac3")) { remux ->
            assertFailsWith<RemuxService.NoExtraTracksException> {
                remux.start("https://provider.example/native-av1-eac3.mkv", 0, capabilities,
                    false, 4, "native", emptySet())
            }
        }
    }

    @Test
    fun `client codec reports are normalized whitelisted and bounded`() {
        val normalized = MediaCapabilities.from(
            ClientCapabilitiesDto(
                videoCodecs = listOf(" HEVC ", "not-a-codec", "AV1"),
                audioCodecs = listOf(" EAC3 ", "made_up_audio"),
            )
        )
        assertEquals(setOf("hevc", "av1"), normalized.video)
        assertEquals(setOf("eac3"), normalized.audio)

        val oversized = MediaCapabilities.from(
            ClientCapabilitiesDto(
                videoCodecs = List(64) { "h264" } + "hevc",
                audioCodecs = listOf("aac"),
            )
        )
        assertEquals(setOf("h264"), oversized.video)
        assertFalse(oversized.videoDecodable("hevc"))
    }

    @Test
    fun `capability fingerprints and remux ids are stable and capability specific`() {
        val first = MediaCapabilities.from(
            ClientCapabilitiesDto(
                videoCodecs = listOf("HEVC", "h264"),
                audioCodecs = listOf("EAC3", "aac"),
            )
        )
        val reordered = MediaCapabilities.from(
            ClientCapabilitiesDto(
                videoCodecs = listOf("h264", "hevc"),
                audioCodecs = listOf("aac", "eac3"),
            )
        )
        val browser = MediaCapabilities.BROWSER
        val inBand = first.copy(selectsTracksInBand = true)

        assertEquals(first.fingerprint, reordered.fingerprint)
        assertTrue(first.fingerprint.matches(Regex("[0-9a-f]{12}")))
        assertEquals(
            remuxSessionId("https://provider.example/movie.mkv", first, 0, "lease"),
            remuxSessionId("https://provider.example/movie.mkv", reordered, 0, "lease"),
        )
        assertTrue(
            remuxSessionId("https://provider.example/movie.mkv", first, 0, "lease") !=
                remuxSessionId("https://provider.example/movie.mkv", browser, 0, "lease")
        )
        assertTrue(first.fingerprint != inBand.fingerprint)
        assertTrue(
            remuxSessionId("https://provider.example/movie.mkv", first, 0, "lease") !=
                remuxSessionId("https://provider.example/movie.mkv", inBand, 0, "lease")
        )
    }

    private fun <T> withRemux(
        videoCodec: String,
        audioCodecs: List<String>,
        subtitleCodecs: List<String> = emptyList(),
        block: (RemuxService) -> T,
    ): T {
        val streams = buildList {
            add("""{"index": 0, "codec_type": "video", "codec_name": "$videoCodec"}""")
            audioCodecs.forEachIndexed { index, codec ->
                add(
                    """{"index": ${index + 1}, "codec_type": "audio", """ +
                        """"codec_name": "$codec", "channels": 2}"""
                )
            }
            subtitleCodecs.forEachIndexed { index, codec ->
                add(
                    """{"index": ${audioCodecs.size + index + 1}, "codec_type": "subtitle", """ +
                        """"codec_name": "$codec"}"""
                )
            }
        }
        val fixture = """
            {
              "streams": [
                ${streams.joinToString(",\n")}
              ],
              "format": {"duration": "120.0"}
            }
        """.trimIndent()
        val runner = MediaProcessRunner { request ->
            request.stdoutFile?.let { Files.writeString(it, fixture) }
            ProcessBuilder("true").start()
        }
        val localConnections = ProviderConnections()
        val remux = RemuxService(ServerHttp(), localConnections, processRunner = runner)
        return try {
            block(remux)
        } finally {
            remux.close()
            localConnections.closeAll()
        }
    }
}
