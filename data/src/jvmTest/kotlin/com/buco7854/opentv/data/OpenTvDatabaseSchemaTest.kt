package com.buco7854.opentv.data

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
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
}
