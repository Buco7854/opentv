package com.buco7854.opentv.serverdata

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import com.buco7854.opentv.data.db.PlaylistRow
import com.buco7854.opentv.serverdata.db.UserRow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class OpenTvServerDatabaseSchemaTest {
    private val schema: Path = Path.of(
        requireNotNull(System.getProperty("opentv.schemaDirectory")) {
            "opentv.schemaDirectory is not set; see server-data/build.gradle.kts"
        },
        "com.buco7854.opentv.serverdata.db.OpenTvServerDatabase",
        "1.json",
    )

    @Test
    fun versionOneSchemaExportIsTheMigrationBaseline() {
        val database = Json.parseToJsonElement(Files.readString(schema))
            .jsonObject.getValue("database").jsonObject
        assertEquals(1, database.getValue("version").jsonPrimitive.content.toInt())
        val tables = database.getValue("entities").jsonArray.map {
            it.jsonObject.getValue("tableName").jsonPrimitive.content
        }
        assertTrue("playlists" in tables)
        assertTrue("channels" in tables)
        assertTrue("users" in tables)
        assertTrue("content_identities" in tables)
        assertFalse("channels_fts" in tables)
        assertFalse("xtream_series_fts" in tables)
        assertFalse("content_series_locators" in tables)
    }

    @Test
    fun serverScaleSidecarsExistAndHotQueriesUseThem() = runTest {
        val dir = Files.createTempDirectory("opentv-server-scale-indexes")
        val path = dir.resolve("opentv.db")
        val db = createOpenTvServerDatabase(path.toString())
        try {
            // Force Room to open the file and run both callback-managed sidecar installers.
            db.users().all()
            BundledSQLiteDriver().open(path.toString()).use { connection ->
                assertTrue(connection.tableExists("content_series_locators"))
                val indexes = connection.indexNames()
                assertTrue("opentv_programmes_playlist_start" in indexes)
                assertTrue("opentv_channels_playlist_kind_series" in indexes)
                assertTrue("opentv_user_favorites_user_added" in indexes)
                assertTrue("opentv_user_downloads_user_created" in indexes)

                assertPlanUses(
                    connection,
                    "opentv_programmes_playlist_start",
                    "SELECT id FROM programmes " +
                        "WHERE playlistId = 1 AND startMs >= 0 ORDER BY startMs LIMIT 10000",
                )
                assertPlanUses(
                    connection,
                    "opentv_user_favorites_user_added",
                    "SELECT content_identities.contentId FROM user_favorites " +
                        "JOIN content_identities USING(contentId) " +
                        "WHERE user_favorites.userId = 'u' " +
                        "ORDER BY user_favorites.addedAtMs DESC",
                )
                assertPlanUses(
                    connection,
                    "opentv_user_downloads_user_created",
                    "SELECT download_blobs.id FROM user_downloads " +
                        "JOIN download_blobs ON download_blobs.id = user_downloads.blobId " +
                        "WHERE user_downloads.userId = 'u' " +
                        "ORDER BY user_downloads.createdAtMs DESC",
                )
                val favoriteSeriesPlan = connection.queryPlan(
                    "SELECT favorites.contentId FROM user_favorites AS favorites " +
                        "JOIN content_identities AS identities " +
                        "ON identities.contentId = favorites.contentId " +
                        "JOIN content_series_locators AS locators " +
                        "ON locators.contentId = favorites.contentId " +
                        "LEFT JOIN xtream_series AS panel " +
                        "ON locators.sourceKind = 'xtream' " +
                        "AND panel.playlistId = locators.playlistId " +
                        "AND panel.seriesId = CAST(locators.sourceKey AS INTEGER) " +
                        "LEFT JOIN channels AS episodes " +
                        "ON locators.sourceKind = 'm3u' " +
                        "AND episodes.playlistId = locators.playlistId " +
                        "AND episodes.kind = 2 AND episodes.seriesKey = locators.sourceKey " +
                        "WHERE favorites.userId = 'u' AND identities.kind = 2 " +
                        "GROUP BY favorites.contentId",
                )
                assertTrue(
                    favoriteSeriesPlan.any { "sqlite_autoindex_content_series_locators_1" in it },
                    favoriteSeriesPlan.joinToString("\n"),
                )
                assertTrue(
                    favoriteSeriesPlan.any { "sqlite_autoindex_xtream_series_1" in it },
                    favoriteSeriesPlan.joinToString("\n"),
                )
                assertTrue(
                    favoriteSeriesPlan.any { "opentv_channels_playlist_kind_series" in it },
                    favoriteSeriesPlan.joinToString("\n"),
                )
                assertPlanUses(
                    connection,
                    "index_channels_playlistId_kind_searchName_position_id",
                    "SELECT * FROM channels WHERE playlistId = 1 AND kind = 0 " +
                        "AND searchName >= 'news' AND searchName < 'newt' " +
                        "ORDER BY searchName, position, id LIMIT 50",
                )
                assertPlanUses(
                    connection,
                    "index_xtream_series_playlistId_searchName_seriesId",
                    "SELECT * FROM xtream_series WHERE playlistId = 1 " +
                        "AND searchName >= 'news' AND searchName < 'newt' " +
                        "ORDER BY searchName, seriesId LIMIT 50",
                )
                assertTrue(
                    connection.queryPlan(
                        "SELECT rowid FROM channels_fts " +
                            "WHERE channels_fts MATCH 'name:news*' LIMIT 50",
                    ).any { "VIRTUAL TABLE INDEX" in it },
                )
            }
        } finally {
            db.close()
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun schemaMismatchDestructivelyRecreatesCatalogAndAccounts() = runTest {
        val dir = Files.createTempDirectory("opentv-server-destructive-schema")
        val path = dir.resolve("opentv.db")
        try {
            val original = createOpenTvServerDatabase(path.toString())
            try {
                val db = original
                db.playlistDao().insert(PlaylistRow(name = "Provider", url = null))
                db.users().insert(
                    UserRow(
                        "u1", "Alice", "alice", "Alice", UserStatus.ACTIVE, UserRole.ADMIN,
                        false, 1, 1, null,
                    ),
                )
            } finally {
                original.close()
            }
            BundledSQLiteDriver().open(path.toString()).use { connection ->
                connection.execSQL("CREATE TABLE legacy_marker (value TEXT NOT NULL)")
                connection.execSQL("INSERT INTO legacy_marker VALUES ('must be dropped')")
                connection.execSQL("PRAGMA user_version = 2")
            }

            val recreated = createOpenTvServerDatabase(path.toString())
            try {
                val db = recreated
                assertTrue(db.playlistDao().getAll().isEmpty())
                assertTrue(db.users().all().isEmpty())
                assertEquals(
                    1L,
                    db.playlistDao().insert(PlaylistRow(name = "Recreated", url = null)),
                )
            } finally {
                recreated.close()
            }

            BundledSQLiteDriver().open(path.toString()).use { connection ->
                assertEquals(1, connection.userVersion())
                assertFalse(connection.tableExists("legacy_marker"))
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

    private fun SQLiteConnection.tableExists(name: String): Boolean =
        prepare("SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?").use {
            it.bindText(1, name)
            it.step()
        }

    private fun SQLiteConnection.indexNames(): Set<String> =
        prepare("SELECT name FROM sqlite_master WHERE type = 'index'").use { statement ->
            buildSet {
                while (statement.step()) add(statement.getText(0))
            }
        }

    private fun SQLiteConnection.queryPlan(sql: String): List<String> =
        prepare("EXPLAIN QUERY PLAN $sql").use { statement ->
            buildList {
                while (statement.step()) add(statement.getText(3))
            }
        }

    private fun assertPlanUses(
        connection: SQLiteConnection,
        index: String,
        sql: String,
    ) {
        val plan = connection.queryPlan(sql)
        assertNotNull(plan.firstOrNull { index in it }, plan.joinToString("\n"))
    }
}
