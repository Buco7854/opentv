package com.buco7854.opentv.data

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import com.buco7854.opentv.core.model.Channel
import com.buco7854.opentv.core.model.ChannelKind
import com.buco7854.opentv.core.model.Download
import com.buco7854.opentv.core.model.DownloadStatus
import com.buco7854.opentv.core.model.Playlist
import com.buco7854.opentv.core.model.XtreamSeries
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class OpenTvDatabaseSchemaTest {
    @Test
    fun fresh_database_has_current_schema_and_working_search_indices() = runTest {
        val dir = Files.createTempDirectory("opentv-catalog-fresh")
        val path = dir.resolve("opentv.db")
        try {
            val storage = createRoomStorage(path.toString())
            try {
                val playlistId = storage.playlists.insert(Playlist(name = "Fresh", url = null))
                storage.channels.insertAll(
                    listOf(
                        Channel(
                            playlistId = playlistId,
                            name = "World Central",
                            url = "https://fixture.invalid/channel",
                            logo = null,
                            groupTitle = "News",
                            tvgId = null,
                            kind = ChannelKind.LIVE,
                            seriesKey = null,
                            season = null,
                            episode = null,
                            position = 0,
                        )
                    )
                )
                storage.xtreamSeries.insertAll(
                    listOf(
                        XtreamSeries(
                            playlistId = playlistId,
                            seriesId = 1,
                            name = "Central Stories",
                            categoryName = "Drama",
                            cover = null,
                            plot = null,
                            castNames = null,
                            genre = null,
                            rating = null,
                        )
                    )
                )

                assertEquals(
                    listOf("World Central"),
                    storage.channels.search(playlistId, "central", 10).map { it.name },
                )
                assertEquals(
                    listOf("Central Stories"),
                    storage.xtreamSeries.search(playlistId, "central", 10).map { it.name },
                )
            } finally {
                storage.close()
            }

            BundledSQLiteDriver().open(path.toString()).use { connection ->
                assertEquals(CURRENT_VERSION, connection.userVersion())
                assertEquals(EXPECTED_TABLES, connection.schemaObjects("table", EXPECTED_TABLES))
                assertEquals(EXPECTED_INDICES, connection.schemaObjects("index", EXPECTED_INDICES))
                assertEquals(EXPECTED_TRIGGERS, connection.schemaObjects("trigger", EXPECTED_TRIGGERS))
            }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun older_database_is_recreated_empty_and_remains_usable() = runTest {
        val dir = Files.createTempDirectory("opentv-catalog-old")
        val path = dir.resolve("opentv.db")
        try {
            BundledSQLiteDriver().open(path.toString()).use { connection ->
                connection.execSQL("CREATE TABLE legacy_marker (value TEXT NOT NULL)")
                connection.execSQL("INSERT INTO legacy_marker VALUES ('must be dropped')")
                connection.execSQL("PRAGMA user_version = ${CURRENT_VERSION - 1}")
            }

            val storage = createRoomStorage(path.toString())
            try {
                assertEquals(emptyList(), storage.playlists.getAll())
                assertEquals(0, storage.channels.count(1, ChannelKind.LIVE))
                val playlistId = storage.playlists.insert(Playlist(name = "Recreated", url = null))
                assertEquals("Recreated", storage.playlists.get(playlistId)?.name)
                storage.channels.insertAll(
                    listOf(
                        Channel(
                            playlistId = playlistId,
                            name = "Recreated Search",
                            url = "https://fixture.invalid/recreated",
                            logo = null,
                            groupTitle = "News",
                            tvgId = null,
                            kind = ChannelKind.LIVE,
                            seriesKey = null,
                            season = null,
                            episode = null,
                            position = 0,
                        ),
                    ),
                )
                assertEquals(
                    listOf("Recreated Search"),
                    storage.channels.search(playlistId, "search", 10).map { it.name },
                )
            } finally {
                storage.close()
            }

            BundledSQLiteDriver().open(path.toString()).use { connection ->
                assertEquals(CURRENT_VERSION, connection.userVersion())
                assertFalse(connection.hasSchemaObject("table", "legacy_marker"))
                assertEquals(EXPECTED_TRIGGERS, connection.schemaObjects("trigger", EXPECTED_TRIGGERS))
            }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun hub_download_identity_and_rotating_url_round_trip_independently() = runTest {
        val dir = Files.createTempDirectory("opentv-download-row")
        val path = dir.resolve("opentv.db")
        try {
            val storage = createRoomStorage(path.toString())
            try {
                val id = storage.downloads.insert(
                    Download(
                        title = "Movie",
                        url = "https://hub.invalid/file?token=old",
                        filePath = "/tmp/movie",
                        status = DownloadStatus.RUNNING,
                        totalBytes = 100,
                        downloadedBytes = 25,
                        hubSourceId = 7,
                        contentId = "content-1",
                        serverDownloadId = "server-1",
                    ),
                )
                assertEquals(
                    id,
                    storage.downloads.findByHubContentWithStatus(
                        7,
                        "content-1",
                        listOf(DownloadStatus.RUNNING),
                    )?.id,
                )

                assertEquals(
                    true,
                    storage.downloads.updateUrlIfStatus(
                        id,
                        "https://hub.invalid/file?token=new",
                        listOf(DownloadStatus.RUNNING),
                    ),
                )
                val stored = storage.downloads.get(id)!!
                assertEquals("https://hub.invalid/file?token=new", stored.url)
                assertEquals(7, stored.hubSourceId)
                assertEquals("content-1", stored.contentId)
                assertEquals("server-1", stored.serverDownloadId)
                assertEquals(
                    null,
                    storage.downloads.findByUrlWithStatus(
                        "https://hub.invalid/file?token=old",
                        listOf(DownloadStatus.RUNNING),
                    ),
                )
            } finally {
                storage.close()
            }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    private fun SQLiteConnection.userVersion(): Int =
        prepare("PRAGMA user_version").use { statement ->
            check(statement.step())
            statement.getLong(0).toInt()
        }

    private fun SQLiteConnection.schemaObjects(type: String, names: List<String>): List<String> =
        prepare(
            "SELECT name FROM sqlite_master WHERE type = ? AND name IN (" +
                names.joinToString { "?" } +
                ") ORDER BY name"
        ).use { statement ->
            statement.bindText(1, type)
            names.forEachIndexed { index, name -> statement.bindText(index + 2, name) }
            buildList {
                while (statement.step()) add(statement.getText(0))
            }
        }

    private fun SQLiteConnection.hasSchemaObject(type: String, name: String): Boolean =
        prepare("SELECT 1 FROM sqlite_master WHERE type = ? AND name = ?").use { statement ->
            statement.bindText(1, type)
            statement.bindText(2, name)
            statement.step()
        }

    private companion object {
        const val CURRENT_VERSION = 12

        val EXPECTED_TABLES = listOf(
            "channels",
            "channels_fts",
            "channels_words_fts",
            "downloads",
            "favorites",
            "group_overrides",
            "hub_sources",
            "metadata",
            "playlists",
            "programmes",
            "resume_points",
            "xtream_series",
            "xtream_series_fts",
            "xtream_series_words_fts",
        ).sorted()

        val EXPECTED_INDICES = listOf(
            "index_channels_playlistId",
            "index_channels_playlistId_kind_groupTitle_position",
            "index_channels_playlistId_kind_groupTitle_seriesKey",
            "index_channels_playlistId_kind_searchName_position_id",
            "index_channels_playlistId_seriesKey",
            "index_downloads_url",
            "index_programmes_playlistId",
            "index_programmes_playlistId_endMs_startMs",
            "index_programmes_playlistId_tvgId_startMs",
            "index_xtream_series_playlistId_categoryName_name_seriesId",
            "index_xtream_series_playlistId_searchName_seriesId",
        ).sorted()

        val EXPECTED_TRIGGERS = listOf(
            "opentv_channels_fts_ad",
            "opentv_channels_fts_ai",
            "opentv_channels_fts_au",
            "opentv_channels_words_fts_ad",
            "opentv_channels_words_fts_ai",
            "opentv_channels_words_fts_au",
            "opentv_xtream_series_fts_ad",
            "opentv_xtream_series_fts_ai",
            "opentv_xtream_series_fts_au",
            "opentv_xtream_series_words_fts_ad",
            "opentv_xtream_series_words_fts_ai",
            "opentv_xtream_series_words_fts_au",
        ).sorted()
    }
}
