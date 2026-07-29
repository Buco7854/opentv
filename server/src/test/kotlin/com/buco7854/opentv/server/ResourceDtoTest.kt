package com.buco7854.opentv.server

import com.buco7854.opentv.contract.*
import com.buco7854.opentv.core.model.Channel
import com.buco7854.opentv.core.model.ChannelKind
import com.buco7854.opentv.core.model.Metadata
import com.buco7854.opentv.core.model.XtreamSeries
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json

class ResourceDtoTest {
    private val cipher = StreamCipher(
        Base64.getEncoder().encodeToString(ByteArray(32) { it.toByte() })
    )

    @Test
    fun `channel DTO hides provider and artwork URLs`() {
        val source = "https://provider.example/live/user/password/42.ts"
        val logo = "https://provider.example/images/42.png"
        val catchup = "https://user:password@provider.example/timeshift/{start}/{duration}"
        val dto = Channel(
            playlistId = 1,
            name = "News",
            url = source,
            logo = logo,
            groupTitle = "Live",
            tvgId = "news",
            kind = ChannelKind.LIVE,
            seriesKey = null,
            season = null,
            episode = null,
            position = 0,
            catchupSource = catchup,
        ).toDto(cipher, "content-42", "user-1")

        val encoded = Json.encodeToString(ChannelDto.serializer(), dto)
        assertTrue(source !in encoded)
        assertTrue(catchup !in encoded)
        assertTrue("catchupSource" !in encoded)
        assertTrue(dto.hasCatchup)
        assertEquals("content-42", dto.contentId)
        assertNotEquals(logo, dto.logo)
        assertEquals(null, dto.logo?.let(cipher::tryDecryptStream))
        val image = dto.logo?.let(cipher::tryDecryptImage)
        assertEquals(logo, image?.url)
        assertEquals("user-1", image?.userId)
        assertEquals(1, image?.playlistId)
        val streamToken = cipher.encryptStream(source, "lease-1")
        assertEquals(null, cipher.tryDecryptImage(streamToken))
    }

    @Test
    fun `provider ids above the javascript safe integer cross the wire as exact strings`() {
        val providerId = 9_007_199_254_740_993L
        val channel = Channel(
            playlistId = 1,
            name = "Precise",
            url = "https://provider.example/live.ts",
            logo = null,
            groupTitle = "Live",
            tvgId = null,
            kind = ChannelKind.LIVE,
            seriesKey = null,
            season = null,
            episode = null,
            position = 0,
            xtreamStreamId = providerId,
        ).toDto(cipher, "content-live", "user-1")
        val series = XtreamSeries(
            playlistId = 1,
            seriesId = providerId,
            name = "Precise series",
            categoryName = "Drama",
            cover = null,
            plot = null,
            castNames = null,
            genre = null,
            rating = null,
        ).toDto(cipher, "content-series", "user-1")
        val metadata = Metadata(
            cacheKey = "series:Precise",
            sourceId = providerId,
            fetchedAtMs = 1,
        ).toDto(cipher, "user-1")

        assertEquals(providerId.toString(), channel.xtreamStreamId)
        assertEquals(providerId.toString(), series.seriesId)
        assertEquals(providerId.toString(), metadata.sourceId)
        assertTrue(
            """"xtreamStreamId":"$providerId"""" in
                Json.encodeToString(ChannelDto.serializer(), channel),
        )
        assertTrue(
            """"seriesId":"$providerId"""" in
                Json.encodeToString(XtreamSeriesDto.serializer(), series),
        )
        assertTrue(
            """"sourceId":"$providerId"""" in
                Json.encodeToString(MetadataDto.serializer(), metadata),
        )
    }

    @Test
    fun `remux diagnostic provider label strips URI user info`() {
        val dto = RemuxService.RemuxDiagnostics(
            videoCodec = "h264",
            transcodeVideo = false,
            videoEncoder = "libx264",
            nativeVideoCopy = true,
            audioCodec = "aac",
            audioChannels = 2,
            audioLabel = "English",
            subtitleCount = 0,
            segmentCount = 1,
            timeshift = false,
            providerKey = "user:password@provider.example:443",
            connectionLimit = 2,
            ffmpegRunning = true,
            durationSec = null,
            lastLog = null,
        ).toDto()

        assertEquals("provider.example:443", dto.providerKey)
    }

    @Test
    fun `account DTO does not expose the provider login`() {
        val providerUsername = "provider-login"
        val dto = com.buco7854.opentv.core.xtream.AccountInfo(
            activeConnections = 1,
            maxConnections = 2,
            status = "Active",
            expiresAtMs = null,
            username = providerUsername,
            isTrial = false,
            createdAtMs = null,
            timezone = null,
        ).toDto()

        assertTrue(providerUsername !in Json.encodeToString(AccountInfoDto.serializer(), dto))
    }

    @Test
    fun `image capability is bound to a user and expires`() {
        var now = 10L
        val expiringCipher = StreamCipher(
            Base64.getEncoder().encodeToString(ByteArray(32) { it.toByte() }),
            clock = { now },
        )
        val token = expiringCipher.encryptImage(
            "https://provider.example/poster.jpg",
            "user-1",
            playlistId = 7,
        )

        val capability = expiringCipher.tryDecryptImage(token)
        assertEquals("user-1", capability?.userId)
        assertEquals(7, capability?.playlistId)
        now = requireNotNull(capability).expiresAtMs
        assertNull(expiringCipher.tryDecryptImage(token))
    }

    @Test
    fun `download file capability is purpose bound and expires`() {
        var now = 10L
        val expiringCipher = StreamCipher(
            Base64.getEncoder().encodeToString(ByteArray(32) { it.toByte() }),
            clock = { now },
        )
        val token = expiringCipher.encryptDownloadFile("user-1", "download-1")

        val capability = expiringCipher.tryDecryptDownloadFile(token.token)
        assertEquals("user-1", capability?.userId)
        assertEquals("download-1", capability?.downloadId)
        assertNull(expiringCipher.tryDecryptImage(token.token))
        assertNull(expiringCipher.tryDecryptStream(token.token))
        now = token.expiresAtMs
        assertNull(expiringCipher.tryDecryptDownloadFile(token.token))
    }

    @Test
    fun `every capability purpose is cryptographically separated`() {
        val stream = cipher.encryptStream("https://provider.example/live.ts", "lease-1")
        val image = cipher.encryptImage("https://provider.example/poster.jpg", "user-1")
        val socket = cipher.encryptWebSocket("session-1", "lease-1").token
        val file = cipher.encryptDownloadFile("user-1", "download-1").token

        assertTrue(cipher.tryDecryptStream(stream) != null)
        assertTrue(cipher.tryDecryptImage(image) != null)
        assertTrue(cipher.tryDecryptWebSocket(socket) != null)
        assertTrue(cipher.tryDecryptDownloadFile(file) != null)

        listOf(image, socket, file).forEach { assertNull(cipher.tryDecryptStream(it)) }
        listOf(stream, socket, file).forEach { assertNull(cipher.tryDecryptImage(it)) }
        listOf(stream, image, file).forEach { assertNull(cipher.tryDecryptWebSocket(it)) }
        listOf(stream, image, socket).forEach { assertNull(cipher.tryDecryptDownloadFile(it)) }
    }

    @Test
    fun `websocket capability expires at its advertised deadline`() {
        var now = 20L
        val expiringCipher = StreamCipher(
            Base64.getEncoder().encodeToString(ByteArray(32) { it.toByte() }),
            clock = { now },
        )
        val token = expiringCipher.encryptWebSocket("session-1", "lease-1")

        assertTrue(expiringCipher.tryDecryptWebSocket(token.token) != null)
        now = token.expiresAtMs
        assertNull(expiringCipher.tryDecryptWebSocket(token.token))
    }
}
