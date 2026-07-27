package com.buco7854.opentv.data

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import com.buco7854.opentv.core.model.ChannelKind
import com.buco7854.opentv.core.model.Playlist
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OpenTvDatabaseSchemaTest {
    private val schemas: Path = Path.of(
        requireNotNull(System.getProperty("opentv.schemaDirectory")) {
            "opentv.schemaDirectory is not set; see data/build.gradle.kts"
        },
        "com.buco7854.opentv.data.db.OpenTvDatabase",
    )

    private fun exportedVersions(): List<Int> =
        Files.list(schemas).use { files ->
            files.map { it.fileName.toString() }
                .filter { it.endsWith(".json") }
                .map { it.removeSuffix(".json").toInt() }
                .sorted()
                .toList()
        }

    private fun createDatabaseAtVersion(path: Path, version: Int) {
        val database = Json.parseToJsonElement(
            Files.readString(schemas.resolve("$version.json")),
        ).jsonObject.getValue("database").jsonObject
        val statements = buildList {
            database["setupQueries"]?.jsonArray?.forEach { add(it.jsonPrimitive.content) }
            database.getValue("entities").jsonArray.forEach { entity ->
                val table = entity.jsonObject.getValue("tableName").jsonPrimitive.content
                fun expand(sql: String) = sql.replace("\${TABLE_NAME}", table)
                add(expand(entity.jsonObject.getValue("createSql").jsonPrimitive.content))
                entity.jsonObject["indices"]?.jsonArray?.forEach {
                    add(expand(it.jsonObject.getValue("createSql").jsonPrimitive.content))
                }
            }
            database["views"]?.jsonArray?.forEach {
                add(it.jsonObject.getValue("createSql").jsonPrimitive.content)
            }
            add("PRAGMA user_version = $version")
        }
        BundledSQLiteDriver().open(path.toString()).use { connection ->
            statements.forEach(connection::execSQL)
        }
    }

    @Test
    fun every_exported_schema_still_opens_with_the_current_code() = runTest {
        val versions = exportedVersions()
        assertTrue(versions.isNotEmpty(), "no exported schema found in $schemas")
        versions.forEach { version ->
            val dir = Files.createTempDirectory("opentv-catalog-v$version")
            try {
                val path = dir.resolve("opentv.db")
                createDatabaseAtVersion(path, version)

                val storage = createRoomStorage(path.toString())
                try {
                    assertEquals(emptyList(), storage.playlists.getAll())
                    val id = storage.playlists.insert(Playlist(name = "P", url = null))
                    assertEquals("P", storage.playlists.get(id)?.name)
                } finally {
                    storage.close()
                }
            } finally {
                dir.toFile().deleteRecursively()
            }
        }
    }

    @Test
    fun migration_9_10_backfills_ranked_search_and_adds_measured_indices() = runTest {
        val dir = Files.createTempDirectory("opentv-catalog-search-migration")
        val path = dir.resolve("opentv.db")
        try {
            createDatabaseAtVersion(path, 9)
            BundledSQLiteDriver().open(path.toString()).use { connection ->
                connection.execSQL(
                    "INSERT INTO playlists " +
                        "(id, name, lastRefreshedMs, epgLastRefreshedMs, channelCount) " +
                        "VALUES (1, 'Migration fixture', 0, 0, 0)"
                )
                listOf(
                    Triple(1, "Central", 0),
                    Triple(2, "Central News", 1),
                    Triple(3, "World Central", 2),
                    Triple(4, "Decentralized", 3),
                    Triple(5, "Central😀", 4),
                ).forEach { (id, name, position) ->
                    connection.execSQL(
                        "INSERT INTO channels " +
                            "(id, playlistId, name, url, groupTitle, kind, position, catchupDays) " +
                            "VALUES ($id, 1, '$name', 'https://fixture.invalid/$id', " +
                            "'News', ${ChannelKind.LIVE}, $position, 0)"
                    )
                }
                listOf(
                    Triple(1, "Central", "Drama"),
                    Triple(2, "Central Stories", "Drama"),
                    Triple(3, "World Central", "Drama"),
                    Triple(4, "Decentralized", "Drama"),
                    Triple(5, "100% Hits", "Music"),
                    Triple(6, "100x Hits", "Music"),
                ).forEach { (id, name, category) ->
                    connection.execSQL(
                        "INSERT INTO xtream_series " +
                            "(playlistId, seriesId, name, categoryName, episodesFetchedAtMs) " +
                            "VALUES (1, $id, '$name', '$category', 0)"
                    )
                }
            }

            val storage = createRoomStorage(path.toString())
            try {
                assertEquals(
                    listOf("Central", "Central News", "Central😀", "World Central", "Decentralized"),
                    storage.channels.search(1, "central", 10).map { it.name },
                )
                assertEquals(
                    listOf("Central", "Central Stories", "World Central", "Decentralized"),
                    storage.xtreamSeries.search(1, "central", 10).map { it.name },
                )
                assertEquals(
                    listOf("100% Hits"),
                    storage.xtreamSeries.search(1, "100%", 10).map { it.name },
                )
                assertEquals(2, storage.channels.search(1, "central", 2).size)
                assertEquals(2, storage.xtreamSeries.search(1, "central", 2).size)
            } finally {
                storage.close()
            }

            BundledSQLiteDriver().open(path.toString()).use { connection ->
                assertEquals(
                    REQUIRED_SEARCH_SCHEMA_OBJECTS,
                    connection.prepare(
                        "SELECT name FROM sqlite_master WHERE name IN (" +
                            REQUIRED_SEARCH_SCHEMA_OBJECTS.joinToString { "'$it'" } +
                            ") ORDER BY name"
                    ).use { rows ->
                        buildList {
                            while (rows.step()) add(rows.getText(0))
                        }
                    },
                )
            }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    private companion object {
        val REQUIRED_SEARCH_SCHEMA_OBJECTS = listOf(
            "channels_fts",
            "channels_words_fts",
            "index_channels_playlistId_kind_groupTitle_position",
            "index_channels_playlistId_kind_groupTitle_seriesKey",
            "index_channels_playlistId_kind_searchName_position_id",
            "index_xtream_series_playlistId_categoryName_name_seriesId",
            "index_xtream_series_playlistId_searchName_seriesId",
            "xtream_series_fts",
            "xtream_series_words_fts",
        ).sorted()
    }
}
