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
}
