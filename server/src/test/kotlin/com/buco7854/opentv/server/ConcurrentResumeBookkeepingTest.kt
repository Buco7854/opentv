package com.buco7854.opentv.server

import com.buco7854.opentv.core.model.ChannelKind
import com.buco7854.opentv.data.db.PlaylistRow
import com.buco7854.opentv.serverdata.UserRole
import com.buco7854.opentv.serverdata.UserStatus
import com.buco7854.opentv.serverdata.createOpenTvServerDatabase
import com.buco7854.opentv.serverdata.db.ContentIdentityRow
import com.buco7854.opentv.serverdata.db.UserResumeRow
import com.buco7854.opentv.serverdata.db.UserRow
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class ConcurrentResumeBookkeepingTest {
    @Test
    fun `one user's concurrent titles retain separate resume rows by content id`() = runBlocking {
        val root = Files.createTempDirectory("concurrent-resume")
        val database = createOpenTvServerDatabase(root.resolve("opentv.db").toString())
        try {
            database.playlistDao().insert(PlaylistRow(id = 1, name = "Provider", url = null))
            database.users().insert(
                UserRow(
                    id = "one-user",
                    username = "viewer",
                    normalizedUsername = "viewer",
                    displayName = "Viewer",
                    status = UserStatus.ACTIVE,
                    manualRole = UserRole.USER,
                    oidcAdmin = false,
                    createdAtMs = 1,
                    updatedAtMs = 1,
                    lastLoginAtMs = null,
                ),
            )
            database.content().upsert(
                ContentIdentityRow("movie-a", 1, ChannelKind.MOVIE, "movie-a", null, 1, false),
            )
            database.content().upsert(
                ContentIdentityRow("movie-b", 1, ChannelKind.MOVIE, "movie-b", null, 1, false),
            )

            database.activity().upsertResume(
                UserResumeRow("one-user", "movie-a", 20_000, 100_000, 10),
            )
            database.activity().upsertResume(
                UserResumeRow("one-user", "movie-b", 55_000, 120_000, 11),
            )

            val rows = database.activity().resumeForUser("one-user").associateBy { it.contentId }
            assertEquals(setOf("movie-a", "movie-b"), rows.keys)
            assertEquals(20_000, rows.getValue("movie-a").positionMs)
            assertEquals(55_000, rows.getValue("movie-b").positionMs)
        } finally {
            database.close()
            root.toFile().deleteRecursively()
        }
    }
}
