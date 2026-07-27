package com.buco7854.opentv.core.download

import kotlin.test.Test
import kotlin.test.assertEquals

class DownloadFilesTest {
    @Test
    fun sanitizesTitleAndUsesOnlyLastUrlSegment() {
        val name = DownloadFileName.from(
            title = "../A: Show?",
            sourceUrl = "https://provider.example/vod/user/123.MKV?token=.ts",
            id = 42,
        )

        assertEquals(".._A_ Show_-42", name.baseName)
        assertEquals("MKV", name.extension)
        assertEquals(".._A_ Show_-42.MKV", name.fileName)
    }

    @Test
    fun defaultsUnknownExtension() {
        assertEquals("Movie-1.mp4", DownloadFileName.from("Movie", "https://host/vod/123", 1).fileName)
    }
}
