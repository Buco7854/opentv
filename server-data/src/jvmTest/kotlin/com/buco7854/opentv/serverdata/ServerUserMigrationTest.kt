package com.buco7854.opentv.serverdata

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
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
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ServerUserMigrationTest {
    private val schemas: Path = Path.of(
        requireNotNull(System.getProperty("opentv.schemaDirectory")) {
            "opentv.schemaDirectory is not set; see server-data/build.gradle.kts"
        },
        "com.buco7854.opentv.serverdata.db.ServerUserDatabase",
    )

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

    private fun SQLiteConnection.tableExists(name: String): Boolean =
        prepare("SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = ?").use {
            it.bindText(1, name)
            it.step() && it.getInt(0) > 0
        }

    @Test
    fun a_version_1_database_upgrades_in_place_and_keeps_its_accounts() = runTest {
        val dir = Files.createTempDirectory("opentv-server-users-migration")
        val path = dir.resolve("server-users.db")
        try {
            createDatabaseAtVersion(path, 1)
            BundledSQLiteDriver().open(path.toString()).use { connection ->
                assertTrue(connection.tableExists("security_events"), "fixture is not version 1")
                connection.execSQL(
                    """
                    INSERT INTO users VALUES
                    ('u1','Alice','alice','Alice','ACTIVE','ADMIN',0,1,1,NULL)
                    """.trimIndent(),
                )
                connection.execSQL(
                    "INSERT INTO security_events VALUES ('e1','u1','u1','logout','',NULL,1)",
                )
            }

            val db = createServerUserDatabase(path.toString())
            try {
                val user: UserRow = assertNotNull(db.users().get("u1"))
                assertEquals("alice", user.normalizedUsername)
                assertEquals(1, db.users().activeAdminCount())
            } finally {
                db.close()
            }

            BundledSQLiteDriver().open(path.toString()).use { connection ->
                assertTrue(!connection.tableExists("security_events"), "table was not dropped")
            }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
}
