package com.buco7854.opentv.hub.playback

import android.app.Application
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class HubMediaDataSourceFactoryTest {

    @Test
    fun replacesOnlyTheGrantQueryParameter() {
        val resolved = requireNotNull(
            replaceMediaGrant(
                "https://hub.test/api/v1/remux/r1/main.m3u8?sid=lease-1&g=old&part=2",
                "new grant",
            ),
        )

        assertTrue(resolved.contains("sid=lease-1"))
        assertTrue(resolved.contains("part=2"))
        assertTrue(resolved.contains("g=new%20grant") || resolved.contains("g=new+grant"))
        assertFalse(resolved.contains("g=old"))
    }

    @Test
    fun leavesNoDuplicateGrantParameter() {
        assertEquals(
            "https://hub.test/api/v1/stream?u=source&g=current",
            replaceMediaGrant(
                "https://hub.test/api/v1/stream?u=source&g=old&g=older",
                "current",
            ),
        )
    }

    @OptIn(UnstableApi::class)
    @Test
    fun capabilityAuthorizedMediaRequestsDoNotCarryABearer() {
        val upstream = RecordingDataSource()
        val source = HubMediaDataSourceFactory(
            DataSource.Factory { upstream },
            grantProvider = { "current-grant" },
        ).createDataSource()

        source.open(
            DataSpec(Uri.parse("https://hub.test/api/v1/stream?u=source&g=old")),
        )
        source.close()

        assertEquals(
            "https://hub.test/api/v1/stream?u=source&g=current-grant",
            upstream.opened.uri.toString(),
        )
        assertNull(upstream.opened.httpRequestHeaders["Authorization"])
    }

    @Test
    fun capabilityAuthorizedImageCallSitesDoNotAttachBearerHeaders() {
        val imageCallSites = listOf(
            "com/buco7854/opentv/ui/components/Common.kt",
            "com/buco7854/opentv/ui/components/PosterGrid.kt",
            "com/buco7854/opentv/ui/components/CastRow.kt",
            "com/buco7854/opentv/ui/details/DetailScreens.kt",
        ).map(::appMainSource)

        assertTrue(
            "Image call sites must pass only signed capability URLs to Coil: " +
                imageCallSites.filter { "Authorization" in it.readText() }
                    .joinToString { it.name },
            imageCallSites.none { "Authorization" in it.readText() },
        )
    }
}

private class RecordingDataSource : DataSource {
    lateinit var opened: DataSpec

    override fun addTransferListener(transferListener: TransferListener) = Unit

    override fun open(dataSpec: DataSpec): Long {
        opened = dataSpec
        return 0
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        C.RESULT_END_OF_INPUT

    override fun getUri(): Uri? = if (::opened.isInitialized) opened.uri else null

    override fun close() = Unit
}

private fun appMainSource(relative: String): File =
    sequenceOf(
        File("src/main/java", relative),
        File("app/src/main/java", relative),
    ).firstOrNull(File::isFile)
        ?: error("Cannot find app source: $relative")
